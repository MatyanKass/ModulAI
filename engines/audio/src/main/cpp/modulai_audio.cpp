// JNI bridge between com.modulai.engine.audio.WhisperNative and whisper.cpp.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"

#define TAG "modulai-audio"
#define JNI_FN(name) Java_com_modulai_engine_audio_WhisperNative_##name
#define EXPORT extern "C" JNIEXPORT

namespace {

constexpr jint DONE = 0;
constexpr jint CANCELLED = 1;
constexpr jint ERR_FAILED = -1;
constexpr jint ERR_LANGUAGE = -2;

struct Recognizer {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
};

struct Call {
    JNIEnv *env;
    jobject callback;
    jmethodID onProgress;
    jmethodID onSegment;
};

std::mutex g_error_mutex;
std::string g_last_error;

void log_sink(enum ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        // whisper.cpp reports one failure as several lines; the first one names the cause.
        if (g_last_error.empty()) {
            g_last_error = text;
            while (!g_last_error.empty() && g_last_error.back() == '\n') g_last_error.pop_back();
        }
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, TAG, text);
    }
}

void clear_error() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error.clear();
}

std::string to_string(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Whisper may cut a multi-byte character between two segments; raw bytes survive the trip
// through JNI where NewStringUTF would reject them.
jbyteArray to_bytes(JNIEnv *env, const char *text) {
    const auto n = static_cast<jsize>(strlen(text));
    jbyteArray out = env->NewByteArray(n);
    env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte *>(text));
    return out;
}

void on_segment(whisper_context *ctx, whisper_state *, int n_new, void *user) {
    auto *call = static_cast<Call *>(user);
    const int n = whisper_full_n_segments(ctx);
    for (int i = n - n_new; i < n; i++) {
        jbyteArray text = to_bytes(call->env, whisper_full_get_segment_text(ctx, i));
        // Whisper counts time in 10 ms steps.
        call->env->CallVoidMethod(call->callback, call->onSegment,
                                  static_cast<jlong>(whisper_full_get_segment_t0(ctx, i) * 10),
                                  static_cast<jlong>(whisper_full_get_segment_t1(ctx, i) * 10), text);
        call->env->DeleteLocalRef(text);
    }
}

void on_progress(whisper_context *, whisper_state *, int progress, void *user) {
    auto *call = static_cast<Call *>(user);
    call->env->CallVoidMethod(call->callback, call->onProgress, static_cast<jint>(progress));
}

} // namespace

EXPORT jstring JNICALL JNI_FN(systemInfo)(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

EXPORT jstring JNICALL JNI_FN(lastError)(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

EXPORT jlong JNICALL JNI_FN(load)(JNIEnv *env, jobject, jstring jpath) {
    whisper_log_set(log_sink, nullptr);
    clear_error();
    whisper_context_params params = whisper_context_default_params();
    params.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(to_string(env, jpath).c_str(), params);
    if (!ctx) return 0;
    auto *rec = new Recognizer();
    rec->ctx = ctx;
    return reinterpret_cast<jlong>(rec);
}

EXPORT void JNICALL JNI_FN(free)(JNIEnv *, jobject, jlong handle) {
    auto *rec = reinterpret_cast<Recognizer *>(handle);
    if (!rec) return;
    whisper_free(rec->ctx);
    delete rec;
}

EXPORT jboolean JNICALL JNI_FN(isMultilingual)(JNIEnv *, jobject, jlong handle) {
    return whisper_is_multilingual(reinterpret_cast<Recognizer *>(handle)->ctx) != 0;
}

EXPORT void JNICALL JNI_FN(cancel)(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Recognizer *>(handle)->cancel = true;
}

EXPORT void JNICALL JNI_FN(clearCancel)(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Recognizer *>(handle)->cancel = false;
}

EXPORT jstring JNICALL JNI_FN(detectedLanguage)(JNIEnv *env, jobject, jlong handle) {
    const int id = whisper_full_lang_id(reinterpret_cast<Recognizer *>(handle)->ctx);
    const char *code = id >= 0 ? whisper_lang_str(id) : nullptr;
    return env->NewStringUTF(code ? code : "");
}

EXPORT jint JNICALL JNI_FN(transcribe)(JNIEnv *env, jobject, jlong handle, jfloatArray jsamples,
                                       jstring jlanguage, jboolean translate, jint threads,
                                       jobject callback) {
    auto *rec = reinterpret_cast<Recognizer *>(handle);
    clear_error();

    const std::string language = to_string(env, jlanguage);
    const bool detect = language.empty() || language == "auto";
    if (!detect && whisper_lang_id(language.c_str()) < 0) return ERR_LANGUAGE;

    jclass cls = env->GetObjectClass(callback);
    Call call{env, callback,
              env->GetMethodID(cls, "onProgress", "(I)V"),
              env->GetMethodID(cls, "onSegment", "(JJ[B)V")};
    if (!call.onProgress || !call.onSegment) return ERR_FAILED;

    const jsize n = env->GetArrayLength(jsamples);
    std::vector<float> samples(static_cast<size_t>(n));
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads > 0 ? threads : 4;
    params.translate = translate;
    params.language = detect ? "auto" : language.c_str();
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.no_context = true;
    params.new_segment_callback = on_segment;
    params.new_segment_callback_user_data = &call;
    params.progress_callback = on_progress;
    params.progress_callback_user_data = &call;
    params.encoder_begin_callback = [](whisper_context *, whisper_state *, void *user) {
        return !static_cast<Recognizer *>(user)->cancel.load();
    };
    params.encoder_begin_callback_user_data = rec;
    params.abort_callback = [](void *user) { return static_cast<Recognizer *>(user)->cancel.load(); };
    params.abort_callback_user_data = rec;

    const int rc = whisper_full(rec->ctx, params, samples.data(), static_cast<int>(samples.size()));
    if (rec->cancel.load()) return CANCELLED;
    return rc == 0 ? DONE : ERR_FAILED;
}
