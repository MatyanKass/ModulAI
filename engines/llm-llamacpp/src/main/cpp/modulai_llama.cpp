// JNI bridge between LlamaNative.kt and llama.cpp.
//
// Generation is stateless from the caller's point of view: every call passes the
// whole conversation. The session remembers which tokens are already in the KV
// cache and only evaluates the part of the prompt that changed.

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "llama.h"
#include "sampling.h"

#define TAG "modulai-llama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

// Return codes of nativeGenerate, mirrored in LlamaNative.kt.
constexpr int STOP_EOS = 0;
constexpr int STOP_LENGTH = 1;
constexpr int STOP_CANCELLED = 2;
constexpr int ERR_TEMPLATE = -1;
constexpr int ERR_PROMPT_TOO_LONG = -2;
constexpr int ERR_DECODE = -3;

// Device kinds of nativeLoad, mirrored in LlamaNative.kt.
constexpr int DEVICE_CPU = 0;
constexpr int DEVICE_GPU = 1;
constexpr int DEVICE_NPU = 2;

// The oldest Hexagon the HTP skels are built for (ggml/src/ggml-hexagon/CMakeLists.txt builds
// v73 and newer); older DSPs (Snapdragon 8 Gen 1, v69) would be handed a v73 skel by upstream.
constexpr int MIN_NPU_ARCH = 73;
constexpr int CDSP_DOMAIN = 3; // CDSP_DOMAIN_ID in the Hexagon SDK's remote.h

constexpr int BATCH_SIZE = 512;

struct Session {
    llama_model * model = nullptr;
    llama_context * ctx = nullptr;
    common_chat_templates_ptr templates;
    std::vector<llama_token> cached; // tokens currently in the KV cache, in order
    std::atomic<bool> cancel{false};
};

// Errors llama.cpp reported since the last load started, shown to the user when it fails.
std::mutex g_error_mutex;
std::string g_last_error;

void clear_errors() {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error.clear();
}

void add_error(const std::string & text) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    // The first lines name the cause; later ones only repeat that the load failed.
    if (g_last_error.size() < 600) g_last_error += text;
}

void log_callback(ggml_log_level level, const char * text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_write(ANDROID_LOG_ERROR, TAG, text);
        add_error(text);
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_write(ANDROID_LOG_WARN, TAG, text);
    }
}

// The Hexagon backend registers as "HTP" and calls its devices GPUs (they take -ngl layers the
// same way), so the registry name is what tells an NPU from a GPU.
constexpr const char * NPU_REG_NAME = "HTP";

bool is_npu(ggml_backend_dev_t dev) {
    ggml_backend_reg_t reg = ggml_backend_dev_backend_reg(dev);
    return reg && strcmp(ggml_backend_reg_name(reg), NPU_REG_NAME) == 0;
}

std::vector<ggml_backend_dev_t> gpu_devices() {
    std::vector<ggml_backend_dev_t> out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        const auto type = ggml_backend_dev_type(dev);
        if ((type == GGML_BACKEND_DEVICE_TYPE_GPU || type == GGML_BACKEND_DEVICE_TYPE_IGPU) && !is_npu(dev)) {
            out.push_back(dev);
        }
    }
    return out;
}

// Set by loadNpuBackend once the DSP answered with an architecture the skels support. Until
// then the HTP devices, even if registered, are never handed to llama.cpp.
std::atomic<int> g_npu_arch{0};

std::vector<ggml_backend_dev_t> npu_devices() {
    std::vector<ggml_backend_dev_t> out;
    if (g_npu_arch.load() == 0) return out;
    for (size_t i = 0; i < ggml_backend_dev_count(); i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (is_npu(dev)) out.push_back(dev);
    }
    return out;
}

jobjectArray to_string_array(JNIEnv * env, const std::vector<std::string> & items) {
    jobjectArray out = env->NewObjectArray((jsize) items.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < items.size(); i++) {
        std::string line = items[i];
        for (auto & c : line) if ((unsigned char) c >= 0x80) c = '?';
        jstring js = env->NewStringUTF(line.c_str());
        env->SetObjectArrayElement(out, (jsize) i, js);
        env->DeleteLocalRef(js);
    }
    return out;
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

// Length of the longest prefix of `s` that does not end inside a UTF-8 sequence.
size_t complete_utf8_prefix(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; back++) {
        const unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue; // continuation byte, keep looking for the lead
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return need > back ? n - back : n;
    }
    return n;
}

struct Rendered {
    std::vector<llama_token> tokens;
    common_chat_params params;
    bool open_thinking = false; // the prompt itself already opened a thinking block
    bool has_tools = false;
};

jbyteArray to_bytes(JNIEnv * env, const std::string & text) {
    jbyteArray arr = env->NewByteArray((jsize) text.size());
    env->SetByteArrayRegion(arr, 0, (jsize) text.size(), (const jbyte *) text.data());
    return arr;
}

bool render(JNIEnv * env, Session * s, jobjectArray roles, jobjectArray contents, jobjectArray tools,
            bool thinking, Rendered & out) {
    common_chat_templates_inputs inputs;
    // Tools arrive flattened as [name, description, JSON schema, name, ...].
    const jsize n_tools = tools ? env->GetArrayLength(tools) / 3 : 0;
    for (jsize i = 0; i < n_tools; i++) {
        common_chat_tool tool;
        std::string * fields[3] = {&tool.name, &tool.description, &tool.parameters};
        for (int f = 0; f < 3; f++) {
            auto js = (jstring) env->GetObjectArrayElement(tools, i * 3 + f);
            *fields[f] = to_string(env, js);
            env->DeleteLocalRef(js);
        }
        inputs.tools.push_back(std::move(tool));
    }
    out.has_tools = n_tools > 0;
    const jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto role = (jstring) env->GetObjectArrayElement(roles, i);
        auto content = (jstring) env->GetObjectArrayElement(contents, i);
        common_chat_msg msg;
        msg.role = to_string(env, role);
        msg.content = to_string(env, content);
        inputs.messages.push_back(std::move(msg));
        env->DeleteLocalRef(role);
        env->DeleteLocalRef(content);
    }
    inputs.add_generation_prompt = true;
    inputs.use_jinja = true;
    inputs.enable_thinking = thinking;

    try {
        out.params = common_chat_templates_apply(s->templates.get(), inputs);
    } catch (const std::exception & e) {
        LOGE("chat template failed: %s", e.what());
        return false;
    }

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    bool first = true;
    for (const auto & part : out.params.prompt.parts) {
        // User text must not be able to inject control tokens, template text must.
        auto toks = common_tokenize(vocab, part.text, first, !part.is_input);
        out.tokens.insert(out.tokens.end(), toks.begin(), toks.end());
        first = false;
    }

    const std::string & start = out.params.thinking_start_tag;
    if (thinking && out.params.supports_thinking && !start.empty()) {
        std::string text = out.params.prompt.str();
        while (!text.empty() && isspace((unsigned char) text.back())) text.pop_back();
        out.open_thinking = text.size() >= start.size() &&
                            text.compare(text.size() - start.size(), start.size(), start) == 0;
    }
    return true;
}

// Evaluates `tokens` starting at position `pos`; logits are produced for the last one.
bool decode(Session * s, const std::vector<llama_token> & tokens, size_t from, llama_pos pos) {
    common_batch batch(s->ctx);
    for (size_t i = from; i < tokens.size(); i += BATCH_SIZE) {
        if (s->cancel.load()) return true;
        const size_t end = std::min(tokens.size(), i + BATCH_SIZE);
        batch.clear();
        for (size_t j = i; j < end; j++) {
            batch.add(tokens[j], pos + (llama_pos) (j - from), 0, j == tokens.size() - 1);
        }
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) return false;
        s->cached.insert(s->cached.end(), tokens.begin() + i, tokens.begin() + end);
    }
    return true;
}

jlong load_model(const std::string & file, jint nCtx, jint nThreads, jint device, int nLayers,
                 bool useMlock, bool useMmap) {
    llama_model_params mp = llama_model_default_params();
    // An explicit device list, empty for a CPU load: with the default (all devices) llama.cpp
    // would initialise the GPU backend and compile its kernels, or open a DSP session, even
    // with no layer offloaded. An accelerator load lists exactly one device.
    ggml_backend_dev_t devices[2] = {nullptr, nullptr};
    if (device == DEVICE_GPU || device == DEVICE_NPU) {
        const auto found = device == DEVICE_GPU ? gpu_devices() : npu_devices();
        if (found.empty()) {
            add_error(device == DEVICE_GPU ? "no GPU device is available" : "no NPU device is available");
            return 0;
        }
        devices[0] = found[0];
    } else {
        nLayers = 0;
    }
    mp.devices = devices;
    mp.n_gpu_layers = nLayers;
    if (useMmap && useMlock) mp.load_mode = LLAMA_LOAD_MODE_MMAP_MLOCK;
    else if (useMmap) mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    else if (useMlock) mp.load_mode = LLAMA_LOAD_MODE_MLOCK;
    else mp.load_mode = LLAMA_LOAD_MODE_NONE;

    llama_model * model = llama_model_load_from_file(file.c_str(), mp);
    if (!model) {
        LOGE("failed to load model %s", file.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = nCtx;
    cp.n_batch = BATCH_SIZE;
    cp.n_ubatch = BATCH_SIZE;
    cp.n_threads = nThreads;
    cp.n_threads_batch = nThreads;
    llama_context * ctx = nullptr;
    try {
        ctx = llama_init_from_model(model, cp);
    } catch (...) {
        llama_model_free(model);
        throw;
    }
    if (!ctx) {
        LOGE("failed to create context (n_ctx=%d)", nCtx);
        llama_model_free(model);
        return 0;
    }

    auto * s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->templates = common_chat_templates_init(model, "");
    return (jlong) s;
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_init(JNIEnv * env, jobject, jstring libDir) {
    llama_log_set(log_callback, nullptr);
    const std::string dir = to_string(env, libDir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    LOGI("backends loaded from %s: %s", dir.c_str(), llama_print_system_info());
    if (ggml_backend_reg_count() == 0) {
        LOGE("no ggml backend found in %s", dir.c_str());
    }
}

// Loads the OpenCL backend, which init() cannot see (see CMakeLists.txt), and returns one line
// per usable GPU. A missing or broken driver makes dlopen fail and leaves the list empty;
// lastError() then tells why.
JNIEXPORT jobjectArray JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_loadGpuBackend(
        JNIEnv * env, jobject, jstring libDir, jstring kernelCacheDir) {
    clear_errors();
    // Compiling the kernels takes many seconds, so the backend caches the binaries. Its
    // default location derives from TMPDIR, which app processes do not have.
    setenv("GGML_OPENCL_KERNEL_CACHE_DIR", to_string(env, kernelCacheDir).c_str(), 1);
    if (!ggml_backend_reg_by_name("OpenCL")) {
        ggml_backend_load((to_string(env, libDir) + "/libmodulai-ggml-opencl.so").c_str());
    }
    std::vector<std::string> lines;
    for (ggml_backend_dev_t dev : gpu_devices()) {
        size_t free = 0, total = 0;
        ggml_backend_dev_memory(dev, &free, &total);
        lines.push_back(std::string(ggml_backend_dev_description(dev)) + " (" +
                        std::to_string(total / (1024 * 1024)) + " MiB)");
        LOGI("GPU device: %s", lines.back().c_str());
    }
    return to_string_array(env, lines);
}

// Loads the Hexagon (HTP) NPU backend, which init() cannot see either (see CMakeLists.txt), and
// returns one line per NPU session llama.cpp may use; empty with the reason in lastError()
// when there is none. Registering the backend loads the vendor's libcdsprpc.so and asks the
// DSP for its architecture over FastRPC, but opens no DSP session: that happens on the first
// NPU load, which is where the skel (the DSP half, libggml-htp-v<arch>.so) is loaded from
// skelDir. The caller must put the skel for npuArch() there before that load.
JNIEXPORT jobjectArray JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_loadNpuBackend(
        JNIEnv * env, jobject, jstring libDir, jstring skelDir) {
    clear_errors();
    std::vector<std::string> lines;
    const std::string lib = to_string(env, libDir) + "/libmodulai-ggml-hexagon.so";
    const std::string skels = to_string(env, skelDir);
    if (access(lib.c_str(), F_OK) != 0) {
        add_error("this build has no Hexagon NPU backend (" + lib + " is missing)");
        return to_string_array(env, lines);
    }
    // Where the DSP looks for the skel. Upstream first opens it by absolute path under
    // GGML_HEXAGON_LIBHTP_DIR, then falls back to the FastRPC search path and finally to
    // ADSP_LIBRARY_PATH, which replaces the vendor's default search path, so the default
    // directories are kept after the app's own. Both must be set before libcdsprpc.so loads.
    const std::string search = skels + ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;"
                                       "/system/vendor/lib/rfsa/adsp;/odm/lib/rfsa/adsp;/dsp";
    setenv("ADSP_LIBRARY_PATH", search.c_str(), 1);
    setenv("GGML_HEXAGON_LIBHTP_DIR", skels.c_str(), 1);
    // Upstream's default ("burst") pins the DSP at maximum clocks with DCVS off for as long
    // as the session lives, and a session lives until the process ends, even after the app
    // goes back to the CPU. This mode keeps DCVS on, so an idle DSP can clock down.
    setenv("GGML_HEXAGON_POWER_MODE", "sustained_high_performance", 0);
    if (!ggml_backend_reg_by_name(NPU_REG_NAME)) {
        ggml_backend_load(lib.c_str());
    }
    if (!ggml_backend_reg_by_name(NPU_REG_NAME)) {
        // The usual cause, libcdsprpc.so not loadable, is already in lastError().
        add_error("the Hexagon backend did not start");
        return to_string_array(env, lines);
    }
    // Upstream assumes v73 when the DSP does not answer and runs v73 code on anything older,
    // so ask the DSP again and refuse both cases here, before any DSP session exists.
    int arch = 0;
    int err = -1;
    void * handle = dlopen(lib.c_str(), RTLD_NOW | RTLD_NOLOAD);
    auto get_arch = handle ? (int (*)(int, int *)) dlsym(handle, "htpdrv_get_arch") : nullptr;
    if (get_arch) err = get_arch(CDSP_DOMAIN, &arch);
    if (handle) dlclose(handle);
    if (!get_arch) {
        add_error("the Hexagon backend does not export htpdrv_get_arch");
    } else if (err != 0) {
        add_error("the NPU did not answer over FastRPC (error " + std::to_string(err) + ")");
    } else if (arch < MIN_NPU_ARCH) {
        add_error("Hexagon v" + std::to_string(arch) + " is older than v" + std::to_string(MIN_NPU_ARCH) +
                  ", the oldest NPU llama.cpp supports");
    } else {
        g_npu_arch.store(arch);
    }
    for (ggml_backend_dev_t dev : npu_devices()) {
        lines.push_back(std::string(ggml_backend_dev_name(dev)) + " (Hexagon v" + std::to_string(arch) + ")");
        LOGI("NPU device: %s", lines.back().c_str());
    }
    if (lines.empty() && g_npu_arch.load() != 0) add_error("the Hexagon backend registered no device");
    return to_string_array(env, lines);
}

// Hexagon architecture of the NPU (73, 75, 79, ...), 0 until loadNpuBackend accepted it.
JNIEXPORT jint JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_npuArch(JNIEnv *, jobject) {
    return (jint) g_npu_arch.load();
}

JNIEXPORT jint JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_backendCount(JNIEnv *, jobject) {
    return (jint) ggml_backend_reg_count();
}

JNIEXPORT jstring JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_lastError(JNIEnv * env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    // Error text is ASCII from llama.cpp plus a file path; replace anything else so NewStringUTF is safe.
    std::string safe = g_last_error;
    while (!safe.empty() && isspace((unsigned char) safe.back())) safe.pop_back();
    for (auto & c : safe) if ((unsigned char) c >= 0x80) c = '?';
    return env->NewStringUTF(safe.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_systemInfo(JNIEnv * env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_load(
        JNIEnv * env, jobject, jstring path, jint nCtx, jint nThreads, jint device, jint nLayers,
        jboolean useMlock, jboolean useMmap) {
    clear_errors();
    const std::string file = to_string(env, path);
    // llama.cpp catches its own exceptions, but the Hexagon backend throws from places llama.cpp
    // calls without a handler of its own (opening the DSP session, for one). One escaping into
    // the JVM would abort the process, so it ends here as a failed load.
    try {
        return load_model(file, nCtx, nThreads, device, nLayers, useMlock, useMmap);
    } catch (const std::exception & e) {
        add_error(std::string(e.what()) + "\n");
    } catch (...) {
        add_error("unknown C++ exception while loading the model\n");
    }
    LOGE("failed to load model %s", file.c_str());
    return 0;
}

JNIEXPORT void JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto * s = (Session *) handle;
    if (!s) return;
    s->templates.reset();
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

JNIEXPORT jint JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_contextSize(JNIEnv *, jobject, jlong handle) {
    return (jint) llama_n_ctx(((Session *) handle)->ctx);
}

JNIEXPORT void JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    ((Session *) handle)->cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_countTokens(
        JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jobjectArray tools,
        jboolean thinking) {
    Rendered r;
    if (!render(env, (Session *) handle, roles, contents, tools, thinking, r)) return ERR_TEMPLATE;
    return (jint) r.tokens.size();
}

static jint generate_impl(
        JNIEnv * env, jlong handle, jobjectArray roles, jobjectArray contents, jobjectArray tools,
        jfloat temperature, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
        jint maxTokens, jint seed, jboolean thinking, jint thinkingBudget, jobject callback) {
    auto * s = (Session *) handle;
    s->cancel.store(false);

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbClass, "onPiece", "([B)V");
    if (!onPiece) {
        // Leaves NoSuchMethodError pending for the caller instead of crashing the process.
        LOGE("PieceCallback.onPiece is missing; was it stripped by the shrinker?");
        return ERR_DECODE;
    }

    Rendered r;
    if (!render(env, s, roles, contents, tools, thinking, r)) return ERR_TEMPLATE;

    const int n_ctx = (int) llama_n_ctx(s->ctx);
    if ((int) r.tokens.size() + 8 > n_ctx) return ERR_PROMPT_TOO_LONG;

    // Reuse the part of the KV cache that matches the new prompt.
    size_t keep = 0;
    while (keep < s->cached.size() && keep < r.tokens.size() && s->cached[keep] == r.tokens[keep]) keep++;
    if (keep == r.tokens.size()) keep--; // the last token must be re-evaluated to get logits
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (keep < s->cached.size() && !llama_memory_seq_rm(mem, 0, (llama_pos) keep, -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    s->cached.resize(keep);

    if (!decode(s, r.tokens, keep, (llama_pos) keep)) return ERR_DECODE;
    if (s->cancel.load()) return STOP_CANCELLED;

    common_params_sampling sp;
    sp.temp = temperature;
    sp.top_p = topP;
    sp.top_k = topK;
    sp.min_p = minP;
    sp.penalty_repeat = repeatPenalty;
    if (seed >= 0) sp.seed = (uint32_t) seed;
    common_sampler * sampler = common_sampler_init(s->model, sp);
    if (!sampler) return ERR_DECODE;

    std::string pending; // bytes not yet delivered because they end mid-codepoint
    auto emit = [&](const std::string & piece) {
        pending += piece;
        const size_t n = complete_utf8_prefix(pending);
        if (n == 0) return;
        jbyteArray arr = env->NewByteArray((jsize) n);
        env->SetByteArrayRegion(arr, 0, (jsize) n, (const jbyte *) pending.data());
        env->CallVoidMethod(callback, onPiece, arr);
        env->DeleteLocalRef(arr);
        pending.erase(0, n);
    };

    const llama_vocab * vocab = llama_model_get_vocab(s->model);
    const std::string & think_start = r.params.thinking_start_tag;
    const auto & think_ends = r.params.thinking_end_tags;
    bool in_thinking = r.open_thinking;
    int thinking_tokens = 0;
    if (in_thinking) emit(think_start);

    std::string output;
    common_batch batch(s->ctx);
    int result = STOP_LENGTH;

    for (int produced = 0; maxTokens <= 0 || produced < maxTokens; produced++) {
        if (s->cancel.load()) { result = STOP_CANCELLED; break; }
        if ((int) s->cached.size() + 1 >= n_ctx) { result = STOP_LENGTH; break; }

        std::vector<llama_token> next;
        std::string piece;
        if (in_thinking && thinkingBudget > 0 && thinking_tokens >= thinkingBudget && !think_ends.empty()) {
            // Reasoning budget spent: close the block for the model and let it answer.
            piece = "\n" + think_ends[0] + "\n\n";
            next = common_tokenize(vocab, piece, false, true);
            in_thinking = false;
        } else {
            const llama_token tok = common_sampler_sample(sampler, s->ctx, -1);
            common_sampler_accept(sampler, tok, true);
            if (llama_vocab_is_eog(vocab, tok)) { result = STOP_EOS; break; }
            piece = common_token_to_piece(vocab, tok, true);
            next.push_back(tok);
        }

        output += piece;
        emit(piece);

        if (in_thinking) {
            thinking_tokens++;
            for (const auto & end : think_ends) {
                if (!end.empty() && output.find(end) != std::string::npos) in_thinking = false;
            }
        } else if (!think_start.empty() && thinking_tokens == 0 && output.find(think_start) != std::string::npos) {
            in_thinking = true;
            for (const auto & end : think_ends) {
                if (!end.empty() && output.find(end) != std::string::npos) in_thinking = false;
            }
            if (in_thinking) thinking_tokens = 1;
        }

        bool stop = false;
        for (const auto & word : r.params.additional_stops) {
            if (!word.empty() && output.size() >= word.size() &&
                output.find(word, output.size() > word.size() + piece.size() ? output.size() - word.size() - piece.size() : 0) != std::string::npos) {
                stop = true;
            }
        }
        if (stop) { result = STOP_EOS; break; }

        batch.clear();
        const llama_pos pos = (llama_pos) s->cached.size();
        for (size_t i = 0; i < next.size(); i++) {
            batch.add(next[i], pos + (llama_pos) i, 0, i == next.size() - 1);
        }
        if (llama_process(s->ctx, LLAMA_PROCESS_TYPE_DECODE, batch.get()) != 0) { result = ERR_DECODE; break; }
        s->cached.insert(s->cached.end(), next.begin(), next.end());
    }

    common_sampler_free(sampler);

    // Let llama.cpp's per-template parser extract tool calls; every model family
    // marks them up differently.
    if (r.has_tools && result >= 0 && result != STOP_CANCELLED) {
        try {
            common_chat_parser_params pp(r.params);
            pp.parser = r.params.parser;
            const common_chat_msg msg = common_chat_parse(common_chat_input(output), false, pp);
            jmethodID onToolCall = env->GetMethodID(cbClass, "onToolCall", "([B[B)V");
            for (const auto & call : msg.tool_calls) {
                if (!onToolCall) break;
                jbyteArray name = to_bytes(env, call.name);
                jbyteArray args = to_bytes(env, call.arguments);
                env->CallVoidMethod(callback, onToolCall, name, args);
                env->DeleteLocalRef(name);
                env->DeleteLocalRef(args);
            }
        } catch (const std::exception & e) {
            LOGE("could not parse tool calls: %s", e.what());
        }
    }
    return result;
}

JNIEXPORT jint JNICALL
Java_com_modulai_engine_llamacpp_LlamaNative_generate(
        JNIEnv * env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jobjectArray tools,
        jfloat temperature, jfloat topP, jint topK, jfloat minP, jfloat repeatPenalty,
        jint maxTokens, jint seed, jboolean thinking, jint thinkingBudget, jobject callback) {
    // As in load(): an accelerator backend's exception must not reach the JVM.
    try {
        return generate_impl(env, handle, roles, contents, tools, temperature, topP, topK, minP, repeatPenalty,
                             maxTokens, seed, thinking, thinkingBudget, callback);
    } catch (const std::exception & e) {
        LOGE("generation failed: %s", e.what());
    } catch (...) {
        LOGE("generation failed with an unknown C++ exception");
    }
    return ERR_DECODE;
}

} // extern "C"
