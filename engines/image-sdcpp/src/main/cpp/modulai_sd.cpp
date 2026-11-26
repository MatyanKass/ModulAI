// JNI bridge between SdNative.kt and stable-diffusion.cpp.
//
// Pixels cross the boundary as tightly packed RGB bytes, rows top to bottom. That is also
// the layout of an sd.cpp image tensor ([channels, width, height], channels interleaved),
// so buffers are passed through without conversion.

#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#if defined(__ANDROID__) && defined(__aarch64__)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

#include "stable-diffusion.h"

#define TAG "modulai-sd"

namespace {

// Result codes written to out[0], mirrored in SdNative.kt.
constexpr jint RESULT_OK = 0;
constexpr jint RESULT_CANCELLED = 1;
constexpr jint RESULT_ERROR = -1;

// Progress stages, mirrored in SdNative.kt.
constexpr jint STAGE_LOADING = 0;
constexpr jint STAGE_SAMPLING = 1;
constexpr jint STAGE_TILES = 2;

// The upscaler is fed blocks of at most UPSCALE_BLOCK source pixels per side, each with
// UPSCALE_PAD pixels of surrounding context that are cut off the result. One block is one
// network pass, so memory use does not depend on the image size and a stop request is
// honoured after the current block.
constexpr int UPSCALE_BLOCK = 128;
constexpr int UPSCALE_PAD = 10;

struct Session {
    sd_ctx_t * ctx = nullptr;
    std::atomic<bool> cancel{false};
};

struct Upscaler {
    sd_upscaler_t * model = nullptr;
    int scale = 0;
    std::atomic<bool> cancel{false};
};

// What sd.cpp reported since the last operation started, shown to the user when it fails.
std::mutex g_error_mutex;
std::string g_last_error;

void clear_errors() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error.clear();
}

void add_error(const std::string & text) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    // The first lines name the cause; later ones only repeat that the operation failed.
    if (g_last_error.size() < 600) g_last_error += text;
}

// sd_get_last_error() is thread-local and reset by the next API call, so it has to be
// captured right after the failing call, on the same thread.
void capture_api_error() {
    const char * text = sd_get_last_error();
    if (!text || !*text) return;
    std::lock_guard<std::mutex> lock(g_error_mutex);
    const std::string api(text);
    // Usually the diagnostic is more precise than the log lines collected so far. A failure
    // inside sd.cpp's internal program ("line 14, column 13: upscaler loading failed") is
    // not: then the log lines name the cause and go first.
    const bool generic = api.rfind("line ", 0) == 0 && api.find(", column ") != std::string::npos;
    if (g_last_error.empty()) {
        g_last_error = api;
    } else if (generic) {
        // Log lines arrive with their own line break.
        if (g_last_error.back() != '\n') g_last_error += '\n';
        g_last_error += api;
    } else {
        g_last_error = api + "\n" + g_last_error;
    }
}

void log_callback(sd_log_level_t level, const char * text, void *) {
    if (level == SD_LOG_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
        add_error(text);
    } else if (level == SD_LOG_WARN) {
        __android_log_write(ANDROID_LOG_WARN, TAG, text);
    }
}

std::string to_string(JNIEnv * env, jstring js) {
    if (!js) return "";
    const jsize len = env->GetStringLength(js);
    const jchar * chars = env->GetStringChars(js, nullptr);
    // GetStringUTFChars yields modified UTF-8, which mangles emoji; convert from UTF-16.
    std::string out;
    out.reserve(len);
    for (jsize i = 0; i < len; i++) {
        uint32_t cp = chars[i];
        if (cp >= 0xD800 && cp <= 0xDBFF && i + 1 < len) {
            const uint32_t lo = chars[i + 1];
            if (lo >= 0xDC00 && lo <= 0xDFFF) {
                cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                i++;
            }
        }
        if (cp < 0x80) {
            out += (char) cp;
        } else if (cp < 0x800) {
            out += (char) (0xC0 | (cp >> 6));
            out += (char) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            out += (char) (0xE0 | (cp >> 12));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        } else {
            out += (char) (0xF0 | (cp >> 18));
            out += (char) (0x80 | ((cp >> 12) & 0x3F));
            out += (char) (0x80 | ((cp >> 6) & 0x3F));
            out += (char) (0x80 | (cp & 0x3F));
        }
    }
    env->ReleaseStringChars(js, chars);
    return out;
}

// Collects setter failures so a chain of them needs a single check at the end.
struct Options {
    sd_opt_t * opt;
    bool ok;

    explicit Options(sd_opt_t * o) : opt(o), ok(o != nullptr) {}
    ~Options() { sd_opt_free(opt); }

    void check(sd_status_t status) {
        if (ok && status != SD_STATUS_OK) {
            ok = false;
            capture_api_error();
        }
    }
    void str(const char * key, const std::string & value) {
        if (ok) check(sd_opt_set_string(opt, key, value.c_str()));
    }
    void str_if_set(const char * key, const std::string & value) {
        if (!value.empty()) str(key, value);
    }
    void integer(const char * key, int64_t value) {
        if (ok) check(sd_opt_set_int(opt, key, value));
    }
    void real(const char * key, double value) {
        if (ok) check(sd_opt_set_float(opt, key, value));
    }
};

sd_tensor_t * rgb_tensor(const uint8_t * pixels, int width, int height) {
    const int64_t shape[] = {3, width, height};
    return sd_tensor_create(SD_TENSOR_U8, 3, shape, pixels, (size_t) width * height * 3);
}

struct ProgressSink {
    JNIEnv * env;
    jobject callback;
    jmethodID method;
    Session * session;
};

void report(const ProgressSink & sink, jint stage, jint done, jint total) {
    sink.env->CallVoidMethod(sink.callback, sink.method, stage, done, total);
    if (sink.env->ExceptionCheck()) sink.env->ExceptionClear();
}

void on_generation_progress(const sd_generation_progress_t * p, void * data) {
    auto * sink = (ProgressSink *) data;
    // sd.cpp drops a request that arrives before it has registered the run, so it is
    // repeated from here, where the run is known to be active.
    if (sink->session->cancel.load()) sd_cancel_generation(sink->session->ctx, SD_CANCEL_ALL);
    if (!p->stage || p->total <= 0) return;
    const std::string stage = p->stage;
    if (stage == "sampling") {
        report(*sink, STAGE_SAMPLING, (jint) p->completed, (jint) p->total);
    } else if (stage == "loading") {
        report(*sink, STAGE_LOADING, (jint) p->completed, (jint) p->total);
    }
}

jmethodID progress_method(JNIEnv * env, jobject callback) {
    jclass cls = env->GetObjectClass(callback);
    jmethodID method = env->GetMethodID(cls, "onProgress", "(III)V");
    env->DeleteLocalRef(cls);
    return method;
}

void set_result(JNIEnv * env, jintArray out, jint code, jint width, jint height) {
    const jint values[3] = {code, width, height};
    env->SetIntArrayRegion(out, 0, 3, values);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_init(JNIEnv *, jobject) {
    sd_set_log_callback(log_callback, nullptr);
}

// The CPU backend is compiled for ARMv8.2 with dot product and half-float arithmetic;
// on an older core it would die with SIGILL instead of failing politely.
JNIEXPORT jboolean JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_cpuSupported(JNIEnv *, jobject) {
#if defined(__ANDROID__) && defined(__aarch64__)
    const unsigned long caps = getauxval(AT_HWCAP);
    return (caps & HWCAP_ASIMDDP) && (caps & HWCAP_ASIMDHP) && (caps & HWCAP_FPHP);
#else
    return JNI_TRUE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(sd_get_system_info());
}

JNIEXPORT jbyteArray JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_lastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    // Returned as bytes: file names inside the message may not be valid modified UTF-8.
    jbyteArray arr = env->NewByteArray((jsize) g_last_error.size());
    env->SetByteArrayRegion(arr, 0, (jsize) g_last_error.size(), (const jbyte *) g_last_error.data());
    return arr;
}

JNIEXPORT jlong JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_load(JNIEnv * env, jobject, jstring model_path, jstring vae_path,
                                                 jstring taesd_path, jint n_threads) {
    clear_errors();
    Options opt(sd_ctx_opt_create());
    opt.str("model_path", to_string(env, model_path));
    opt.str_if_set("vae_path", to_string(env, vae_path));
    opt.str_if_set("taesd_path", to_string(env, taesd_path));
    opt.integer("n_threads", n_threads);
    if (!opt.ok) return 0;

    sd_ctx_t * ctx = nullptr;
    if (sd_ctx_create(opt.opt, &ctx) != SD_STATUS_OK) {
        capture_api_error();
        return 0;
    }
    // Creation is lazy; this is the call that actually opens the files and reports a
    // missing or unsupported model.
    if (sd_ctx_prepare(ctx) != SD_STATUS_OK) {
        capture_api_error();
        sd_ctx_free(ctx);
        return 0;
    }
    auto * s = new Session();
    s->ctx = ctx;
    return (jlong) s;
}

JNIEXPORT void JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_free(JNIEnv *, jobject, jlong handle) {
    auto * s = (Session *) handle;
    if (!s) return;
    sd_ctx_free(s->ctx);
    delete s;
}

JNIEXPORT jstring JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_modelVersion(JNIEnv * env, jobject, jlong handle) {
    return env->NewStringUTF(sd_get_model_version_name(((Session *) handle)->ctx));
}

// Safe to call from any thread while generate() runs. sd.cpp checks the request between
// denoising steps; a step in progress and the final VAE decode run to completion.
JNIEXPORT void JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_cancel(JNIEnv *, jobject, jlong handle) {
    auto * s = (Session *) handle;
    if (!s) return;
    s->cancel.store(true);
    sd_cancel_generation(s->ctx, SD_CANCEL_ALL);
}

// Returns width*height*3 RGB bytes, or null. out = [result code, width, height].
JNIEXPORT jbyteArray JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_generate(
        JNIEnv * env, jobject, jlong handle, jstring prompt, jstring negative, jint width, jint height,
        jint steps, jfloat cfg_scale, jstring sampler, jstring scheduler, jlong seed,
        jbyteArray init_rgb, jint init_width, jint init_height, jfloat strength,
        jobjectArray lora_paths, jfloatArray lora_weights, jboolean vae_tiling, jobject callback, jintArray out) {
    auto * s = (Session *) handle;
    clear_errors();
    set_result(env, out, RESULT_ERROR, 0, 0);

    Options opt(sd_img_gen_opt_create());
    opt.str("prompt", to_string(env, prompt));
    opt.str("negative_prompt", to_string(env, negative));
    opt.integer("width", width);
    opt.integer("height", height);
    opt.integer("seed", seed);
    opt.integer("sample.steps", steps);
    opt.real("sample.guidance.txt_cfg", cfg_scale);
    // An unset sampler or scheduler lets sd.cpp pick the default for the loaded model.
    opt.str_if_set("sample.method", to_string(env, sampler));
    opt.str_if_set("sample.scheduler", to_string(env, scheduler));
    if (vae_tiling && opt.ok) opt.check(sd_opt_set_bool(opt.opt, "vae_tiling.enabled", true));

    if (init_rgb && opt.ok) {
        if (env->GetArrayLength(init_rgb) != (jsize) init_width * init_height * 3) {
            add_error("source image buffer does not match its size");
            return nullptr;
        }
        jbyte * pixels = env->GetByteArrayElements(init_rgb, nullptr);
        sd_tensor_t * image = rgb_tensor((const uint8_t *) pixels, init_width, init_height);
        env->ReleaseByteArrayElements(init_rgb, pixels, JNI_ABORT);
        opt.check(image ? sd_opt_set_tensor(opt.opt, "init_image", image) : SD_STATUS_OUT_OF_MEMORY);
        sd_tensor_free(image);
        opt.real("strength", strength);
    }

    const jsize n_loras = lora_paths ? env->GetArrayLength(lora_paths) : 0;
    if (n_loras > 0 && opt.ok) {
        std::vector<std::string> paths(n_loras);
        std::vector<jfloat> weights(n_loras);
        env->GetFloatArrayRegion(lora_weights, 0, n_loras, weights.data());
        std::vector<sd_lora_t> loras(n_loras);
        for (jsize i = 0; i < n_loras; i++) {
            auto js = (jstring) env->GetObjectArrayElement(lora_paths, i);
            paths[i] = to_string(env, js);
            env->DeleteLocalRef(js);
            loras[i] = sd_lora_t{false, weights[i], paths[i].c_str()};
        }
        opt.check(sd_opt_set_loras(opt.opt, "loras", loras.data(), loras.size()));
    }
    if (!opt.ok) return nullptr;

    ProgressSink sink{env, callback, progress_method(env, callback), s};
    sd_set_generation_progress_callback(s->ctx, on_generation_progress, &sink);
    // A stop request only ever refers to the run it arrived during.
    s->cancel.store(false);
    sd_result_t * result = nullptr;
    const sd_status_t status = sd_generate_image(s->ctx, opt.opt, &result);
    if (status != SD_STATUS_OK) capture_api_error();
    sd_set_generation_progress_callback(s->ctx, nullptr, nullptr);
    const bool cancelled = s->cancel.load();

    jbyteArray pixels = nullptr;
    if (status == SD_STATUS_OK && !cancelled) {
        const sd_tensor_t * image = sd_result_get_image(result, 0);
        if (image && sd_tensor_type(image) == SD_TENSOR_U8 && sd_tensor_rank(image) == 3
                && sd_tensor_dim(image, 0) == 3) {
            const size_t size = sd_tensor_byte_size(image);
            pixels = env->NewByteArray((jsize) size);
            if (pixels) {
                env->SetByteArrayRegion(pixels, 0, (jsize) size, (const jbyte *) sd_tensor_data(image));
                set_result(env, out, RESULT_OK, (jint) sd_tensor_dim(image, 1), (jint) sd_tensor_dim(image, 2));
            }
        } else {
            add_error("the model returned no RGB image");
        }
    } else if (cancelled || status == SD_STATUS_CANCELLED) {
        set_result(env, out, RESULT_CANCELLED, 0, 0);
    }
    sd_result_free(result);
    return pixels;
}

JNIEXPORT jlong JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_loadUpscaler(JNIEnv * env, jobject, jstring model_path,
                                                         jint n_threads) {
    clear_errors();
    Options opt(sd_upscaler_opt_create());
    opt.str("model_path", to_string(env, model_path));
    opt.integer("n_threads", n_threads);
    opt.integer("tile_size", UPSCALE_BLOCK);
    if (!opt.ok) return 0;

    sd_upscaler_t * model = nullptr;
    if (sd_upscaler_create(opt.opt, &model) != SD_STATUS_OK) {
        capture_api_error();
        return 0;
    }
    auto * u = new Upscaler();
    u->model = model;
    u->scale = sd_upscaler_get_scale(model);
    return (jlong) u;
}

JNIEXPORT void JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_freeUpscaler(JNIEnv *, jobject, jlong handle) {
    auto * u = (Upscaler *) handle;
    if (!u) return;
    sd_upscaler_free(u->model);
    delete u;
}

JNIEXPORT jint JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_upscalerScale(JNIEnv *, jobject, jlong handle) {
    return ((Upscaler *) handle)->scale;
}

// sd.cpp has no way to interrupt an upscale, so the flag is checked between blocks.
JNIEXPORT void JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_cancelUpscaler(JNIEnv *, jobject, jlong handle) {
    auto * u = (Upscaler *) handle;
    if (u) u->cancel.store(true);
}

// Returns the enlarged RGB image, or null. out = [result code, width, height].
JNIEXPORT jbyteArray JNICALL
Java_com_modulai_engine_imagesdcpp_SdNative_upscale(JNIEnv * env, jobject, jlong handle, jbyteArray rgb,
                                                    jint width, jint height, jobject callback, jintArray out) {
    auto * u = (Upscaler *) handle;
    clear_errors();
    set_result(env, out, RESULT_ERROR, 0, 0);

    const int scale = u->scale;
    const int64_t out_width = (int64_t) width * scale;
    const int64_t out_height = (int64_t) height * scale;
    if (width <= 0 || height <= 0 || env->GetArrayLength(rgb) != (jsize) width * height * 3) {
        add_error("source image buffer does not match its size");
        return nullptr;
    }
    if (out_width * out_height * 3 > INT32_MAX) {
        add_error("the enlarged image would not fit into memory");
        return nullptr;
    }
    jbyteArray result = env->NewByteArray((jsize) (out_width * out_height * 3));
    if (!result) {
        env->ExceptionClear();
        add_error("not enough memory for the enlarged image");
        return nullptr;
    }

    std::vector<uint8_t> source((size_t) width * height * 3);
    env->GetByteArrayRegion(rgb, 0, (jsize) source.size(), (jbyte *) source.data());

    const int core = UPSCALE_BLOCK - 2 * UPSCALE_PAD;
    const int blocks_x = (width + core - 1) / core;
    const int blocks_y = (height + core - 1) / core;
    const int total = blocks_x * blocks_y;
    ProgressSink sink{env, callback, progress_method(env, callback), nullptr};
    u->cancel.store(false);
    report(sink, STAGE_TILES, 0, total);

    std::vector<uint8_t> block;
    int done = 0;
    jint code = RESULT_OK;
    for (int by = 0; by < blocks_y && code == RESULT_OK; by++) {
        for (int bx = 0; bx < blocks_x && code == RESULT_OK; bx++) {
            if (u->cancel.load()) {
                code = RESULT_CANCELLED;
                break;
            }
            // The part of the image this block is responsible for, and the same part
            // widened by the context margin where the image has pixels to offer.
            const int x0 = bx * core, y0 = by * core;
            const int x1 = std::min(x0 + core, width), y1 = std::min(y0 + core, height);
            const int px0 = std::max(x0 - UPSCALE_PAD, 0), py0 = std::max(y0 - UPSCALE_PAD, 0);
            const int px1 = std::min(x1 + UPSCALE_PAD, width), py1 = std::min(y1 + UPSCALE_PAD, height);
            const int bw = px1 - px0, bh = py1 - py0;

            block.resize((size_t) bw * bh * 3);
            for (int y = 0; y < bh; y++) {
                const uint8_t * row = source.data() + ((size_t) (py0 + y) * width + px0) * 3;
                std::copy(row, row + (size_t) bw * 3, block.begin() + (size_t) y * bw * 3);
            }

            Options opt(sd_upscale_opt_create());
            sd_tensor_t * input = rgb_tensor(block.data(), bw, bh);
            opt.check(input ? sd_opt_set_tensor(opt.opt, "image", input) : SD_STATUS_OUT_OF_MEMORY);
            sd_tensor_free(input);
            sd_result_t * enlarged = nullptr;
            if (opt.ok && sd_upscale(u->model, opt.opt, &enlarged) != SD_STATUS_OK) capture_api_error();

            const sd_tensor_t * image = enlarged ? sd_result_get_image(enlarged, 0) : nullptr;
            if (image && sd_tensor_dim(image, 0) >= 3 && sd_tensor_dim(image, 1) == (int64_t) bw * scale
                    && sd_tensor_dim(image, 2) == (int64_t) bh * scale) {
                const int channels = (int) sd_tensor_dim(image, 0);
                const auto * data = (const uint8_t *) sd_tensor_data(image);
                const int skip_x = (x0 - px0) * scale, skip_y = (y0 - py0) * scale;
                const int copy_w = (x1 - x0) * scale, copy_h = (y1 - y0) * scale;
                std::vector<uint8_t> row((size_t) copy_w * 3);
                for (int y = 0; y < copy_h; y++) {
                    const uint8_t * src = data + ((size_t) (skip_y + y) * bw * scale + skip_x) * channels;
                    if (channels == 3) {
                        std::copy(src, src + row.size(), row.begin());
                    } else {
                        for (int x = 0; x < copy_w; x++) std::copy(src + x * channels, src + x * channels + 3, row.begin() + x * 3);
                    }
                    const int64_t offset = ((int64_t) (y0 * scale + y) * out_width + (int64_t) x0 * scale) * 3;
                    env->SetByteArrayRegion(result, (jsize) offset, (jsize) row.size(), (const jbyte *) row.data());
                }
                report(sink, STAGE_TILES, ++done, total);
            } else {
                if (image) add_error("the upscaler returned a block of unexpected size");
                code = RESULT_ERROR;
            }
            sd_result_free(enlarged);
        }
    }

    if (code != RESULT_OK) {
        set_result(env, out, code, 0, 0);
        env->DeleteLocalRef(result);
        return nullptr;
    }
    set_result(env, out, RESULT_OK, (jint) out_width, (jint) out_height);
    return result;
}

} // extern "C"
