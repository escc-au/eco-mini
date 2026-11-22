// Thin JNI bridge to llama.cpp: load model, embed text, generate with a token callback, stop, free.
// All calls except requestStop() must come from one thread (the Kotlin side uses a single-thread
// dispatcher), so no locking is needed here.
#include <jni.h>
#include <android/log.h>
#include <dirent.h>
#include <dlfcn.h>
#include <atomic>
#include <cmath>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "EcoNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    llama_sampler *smpl = nullptr;
    const llama_vocab *vocab = nullptr;
    std::vector<llama_token> cached;  // tokens currently in the KV cache (for prefix reuse)
    std::atomic<bool> stop{false};
    bool embedder = false;
};

void log_cb(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN
             : level == GGML_LOG_LEVEL_DEBUG ? ANDROID_LOG_DEBUG
                                             : ANDROID_LOG_INFO;
    if (prio >= ANDROID_LOG_INFO) __android_log_write(prio, "llama", text);
}

bool abort_cb(void *data) { return static_cast<Session *>(data)->stop.load(); }

std::string jstr(JNIEnv *env, jstring s) {
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Java's GetStringUTFChars gives "modified UTF-8"; use real UTF-8 bytes for the prompt.
std::string jbytes(JNIEnv *env, jbyteArray a) {
    jsize n = env->GetArrayLength(a);
    std::string out(size_t(n), '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special,
                                  bool parse_special) {
    int n = -llama_tokenize(vocab, text.data(), int32_t(text.size()), nullptr, 0, add_special, parse_special);
    std::vector<llama_token> toks(n > 0 ? n : 0);
    if (n > 0) llama_tokenize(vocab, text.data(), int32_t(text.size()), toks.data(), n, add_special, parse_special);
    return toks;
}

std::string piece(const llama_vocab *vocab, llama_token t) {
    char buf[256];
    int n = llama_token_to_piece(vocab, t, buf, sizeof buf, 0, false);
    if (n < 0) {
        std::string big(size_t(-n), '\0');
        n = llama_token_to_piece(vocab, t, big.data(), int32_t(big.size()), 0, false);
        return n > 0 ? big.substr(0, size_t(n)) : std::string();
    }
    return std::string(buf, size_t(n));
}

// Length of the longest prefix of s that does not end inside a multi-byte UTF-8 sequence.
size_t utf8_complete_len(const std::string &s) {
    size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        auto c = static_cast<unsigned char>(s[n - back]);
        if ((c & 0xC0) == 0x80) continue;  // continuation byte, keep looking back
        size_t need = (c & 0x80) == 0 ? 1 : (c & 0xE0) == 0xC0 ? 2 : (c & 0xF0) == 0xE0 ? 3 : (c & 0xF8) == 0xF0 ? 4 : 1;
        return back >= need ? n : n - back;
    }
    return n;
}

int decode_tokens(Session *s, const llama_token *toks, int n) {
    const int n_batch = int(llama_n_batch(s->ctx));
    for (int i = 0; i < n; i += n_batch) {
        int len = std::min(n_batch, n - i);
        int rc = llama_decode(s->ctx, llama_batch_get_one(const_cast<llama_token *>(toks + i), len));
        if (rc != 0) return rc;
        s->cached.insert(s->cached.end(), toks + i, toks + i + len);
    }
    return 0;
}

void free_session(Session *s) {
    if (!s) return;
    // Order matters: sampler, then context, then model.
    if (s->smpl) llama_sampler_free(s->smpl);
    if (s->ctx) llama_free(s->ctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

Session *load(const std::string &path, const std::string &device, int n_threads, int n_ctx, bool embedder) {
    llama_model_params mp = llama_model_default_params();
    ggml_backend_dev_t devs[2] = {nullptr, nullptr};
    if (!device.empty()) {
        devs[0] = ggml_backend_dev_by_name(device.c_str());
        if (!devs[0]) {
            LOGE("device %s not found", device.c_str());
            return nullptr;
        }
        mp.n_gpu_layers = -1;
    } else {
        mp.n_gpu_layers = 0;
    }
    mp.devices = devs;  // {nullptr} alone means: no GPU devices at all
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;  // map the file in place (works for /proc/self/fd paths too)

    auto *s = new Session();
    s->embedder = embedder;
    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (!s->model) {
        LOGE("model load failed: %s", path.c_str());
        free_session(s);
        return nullptr;
    }
    s->vocab = llama_model_get_vocab(s->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = uint32_t(n_ctx);
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.no_perf = true;
    if (device.empty()) cp.op_offload = false;  // CPU means CPU only
    if (embedder) {
        cp.embeddings = true;
        cp.pooling_type = LLAMA_POOLING_TYPE_CLS;
    }
    s->ctx = llama_init_from_model(s->model, cp);
    if (!s->ctx) {
        LOGE("context creation failed");
        free_session(s);
        return nullptr;
    }
    llama_set_abort_callback(s->ctx, abort_cb, s);

    if (!embedder) {
        s->smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(s->smpl, llama_sampler_init_greedy());

        // Sanity check: a broken GPU driver can load fine but produce NaNs.
        auto toks = tokenize(s->vocab, "Hello", false, false);
        if (toks.empty() || llama_decode(s->ctx, llama_batch_get_one(toks.data(), int32_t(toks.size()))) != 0) {
            LOGE("warm-up decode failed");
            free_session(s);
            return nullptr;
        }
        const float *logits = llama_get_logits_ith(s->ctx, -1);
        const int n_vocab = llama_vocab_n_tokens(s->vocab);
        for (int i = 0; i < n_vocab; ++i) {
            if (!std::isfinite(logits[i])) {
                LOGE("warm-up produced non-finite logits");
                free_session(s);
                return nullptr;
            }
        }
        llama_memory_clear(llama_get_memory(s->ctx), true);
    }
    return s;
}

}  // namespace

extern "C" {

// Loads the CPU variant with the best score for this chip (dotprod, i8mm, SVE...), like ggml's
// own loader, and the GPU backends only if allowed. GPU drivers initialise while their backend
// registers, so a driver that crashes must not be touched again on the next start.
JNIEXPORT void JNICALL
Java_org_escc_ecomini_engine_LlamaNative_initBackends(JNIEnv *env, jclass, jstring libDir, jboolean gpu) {
    static bool done = false;
    if (done) return;
    done = true;
    llama_log_set(log_cb, nullptr);
    const std::string dir = jstr(env, libDir);

    if (gpu) {
        for (const char *name : {"libggml-opencl.so", "libggml-vulkan.so"}) {
            // Fails quietly if the phone has no such driver (e.g. no libOpenCL.so on Pixel).
            if (!ggml_backend_load((dir + "/" + name).c_str())) LOGI("%s not usable on this phone", name);
        }
    }

    std::string best;
    int best_score = 0;
    if (DIR *d = opendir(dir.c_str())) {
        while (dirent *e = readdir(d)) {
            std::string f = e->d_name;
            if (f.rfind("libggml-cpu-", 0) != 0) continue;
            std::string path = dir + "/" + f;
            void *h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
            if (!h) continue;
            using score_fn = int (*)();
            auto score = reinterpret_cast<score_fn>(dlsym(h, "ggml_backend_score"));
            int sc = score ? score() : 0;
            dlclose(h);
            LOGI("%s score %d", f.c_str(), sc);
            if (sc > best_score) { best_score = sc; best = path; }
        }
        closedir(d);
    }
    if (best.empty() || !ggml_backend_load(best.c_str())) LOGE("no usable CPU backend in %s", dir.c_str());
    else LOGI("CPU backend: %s", best.c_str());
    llama_backend_init();
}

// Each entry: "name\tdescription\ttype" with type = cpu|gpu|igpu|other.
JNIEXPORT jobjectArray JNICALL
Java_org_escc_ecomini_engine_LlamaNative_listDevices(JNIEnv *env, jclass) {
    size_t n = ggml_backend_dev_count();
    jobjectArray arr = env->NewObjectArray(jsize(n), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < n; ++i) {
        ggml_backend_dev_t d = ggml_backend_dev_get(i);
        auto t = ggml_backend_dev_type(d);
        const char *ts = t == GGML_BACKEND_DEVICE_TYPE_CPU ? "cpu"
                       : t == GGML_BACKEND_DEVICE_TYPE_GPU ? "gpu"
                       : t == GGML_BACKEND_DEVICE_TYPE_IGPU ? "igpu" : "other";
        std::string row = std::string(ggml_backend_dev_name(d)) + "\t" + ggml_backend_dev_description(d) + "\t" + ts;
        jstring js = env->NewStringUTF(row.c_str());
        env->SetObjectArrayElement(arr, jsize(i), js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

JNIEXPORT jlong JNICALL
Java_org_escc_ecomini_engine_LlamaNative_loadModel(JNIEnv *env, jclass, jstring path, jstring device,
                                                   jint nThreads, jint nCtx) {
    return reinterpret_cast<jlong>(load(jstr(env, path), jstr(env, device), nThreads, nCtx, false));
}

JNIEXPORT jlong JNICALL
Java_org_escc_ecomini_engine_LlamaNative_loadEmbedder(JNIEnv *env, jclass, jstring path, jint nThreads) {
    return reinterpret_cast<jlong>(load(jstr(env, path), "", nThreads, 512, true));
}

JNIEXPORT jint JNICALL
Java_org_escc_ecomini_engine_LlamaNative_countTokens(JNIEnv *env, jclass, jlong h, jbyteArray text) {
    auto *s = reinterpret_cast<Session *>(h);
    return s ? jint(tokenize(s->vocab, jbytes(env, text), false, true).size()) : -1;
}

JNIEXPORT jfloatArray JNICALL
Java_org_escc_ecomini_engine_LlamaNative_embed(JNIEnv *env, jclass, jlong h, jbyteArray text) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s || !s->embedder) return nullptr;
    auto toks = tokenize(s->vocab, jbytes(env, text), true, false);  // adds [CLS] ... [SEP]
    const int n_max = int(llama_n_ctx(s->ctx));
    if (int(toks.size()) > n_max) {
        llama_token last = toks.back();
        toks.resize(size_t(n_max));
        toks.back() = last;
    }
    if (toks.empty()) return nullptr;

    if (auto mem = llama_get_memory(s->ctx)) llama_memory_clear(mem, true);
    llama_batch b = llama_batch_init(int32_t(toks.size()), 0, 1);
    for (size_t i = 0; i < toks.size(); ++i) {
        b.token[i] = toks[i];
        b.pos[i] = llama_pos(i);
        b.n_seq_id[i] = 1;
        b.seq_id[i][0] = 0;
        b.logits[i] = 1;
    }
    b.n_tokens = int32_t(toks.size());
    s->stop = false;
    int rc = llama_decode(s->ctx, b);
    llama_batch_free(b);
    if (rc != 0) return nullptr;

    const float *e = llama_get_embeddings_seq(s->ctx, 0);
    const int n = llama_model_n_embd_out(s->model);
    if (!e) return nullptr;
    double norm = 0;
    for (int i = 0; i < n; ++i) norm += double(e[i]) * e[i];
    float inv = norm > 0 ? float(1.0 / std::sqrt(norm)) : 0.f;
    std::vector<float> out(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) out[size_t(i)] = e[i] * inv;
    jfloatArray arr = env->NewFloatArray(n);
    env->SetFloatArrayRegion(arr, 0, n, out.data());
    return arr;
}

// Returns 0 = finished (end of turn), 1 = hit maxTokens, 2 = stopped, -1 = error, -2 = prompt too long.
JNIEXPORT jint JNICALL
Java_org_escc_ecomini_engine_LlamaNative_generate(JNIEnv *env, jclass, jlong h, jbyteArray prompt,
                                                  jint maxTokens, jobject sink) {
    auto *s = reinterpret_cast<Session *>(h);
    if (!s || s->embedder) return -1;
    jmethodID onToken = env->GetMethodID(env->GetObjectClass(sink), "onToken", "([B)V");
    if (!onToken) return -1;
    // The stop flag is cleared by resetStop() at the start of a question, not here, so a stop
    // pressed between the query rewrite and the answer is not lost.

    auto toks = tokenize(s->vocab, jbytes(env, prompt), false, true);
    if (toks.empty()) return -1;
    if (int(toks.size()) + maxTokens > int(llama_n_ctx(s->ctx))) return -2;

    // Reuse the KV cache for the shared prefix (system prompt and earlier turns).
    size_t keep = 0;
    while (keep < s->cached.size() && keep < toks.size() && s->cached[keep] == toks[keep]) ++keep;
    if (keep == toks.size()) --keep;  // must decode at least one token to get logits
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (!llama_memory_seq_rm(mem, 0, llama_pos(keep), -1)) {
        llama_memory_clear(mem, true);
        keep = 0;
    }
    s->cached.resize(keep);

    auto emit = [&](const std::string &bytes) {
        if (bytes.empty()) return;
        jbyteArray a = env->NewByteArray(jsize(bytes.size()));
        env->SetByteArrayRegion(a, 0, jsize(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
        env->CallVoidMethod(sink, onToken, a);
        env->DeleteLocalRef(a);
    };
    auto fail = [&](int rc) {
        // A failed or aborted decode leaves the cache in an unknown state.
        llama_memory_clear(mem, true);
        s->cached.clear();
        return rc;
    };

    int rc = decode_tokens(s, toks.data() + keep, int(toks.size() - keep));
    if (rc != 0) return fail(s->stop ? 2 : -1);

    std::string pending;
    int result = 1;
    for (int i = 0; i < maxTokens; ++i) {
        if (s->stop) { result = 2; break; }
        llama_token t = llama_sampler_sample(s->smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, t)) { result = 0; break; }
        pending += piece(s->vocab, t);
        size_t ok = utf8_complete_len(pending);
        emit(pending.substr(0, ok));
        pending.erase(0, ok);
        if (env->ExceptionCheck()) return fail(-1);
        if (decode_tokens(s, &t, 1) != 0) {
            emit(pending);
            return fail(s->stop ? 2 : -1);
        }
    }
    emit(pending);
    return result;
}

JNIEXPORT void JNICALL
Java_org_escc_ecomini_engine_LlamaNative_resetStop(JNIEnv *, jclass, jlong h) {
    if (auto *s = reinterpret_cast<Session *>(h)) s->stop = false;
}

JNIEXPORT void JNICALL
Java_org_escc_ecomini_engine_LlamaNative_requestStop(JNIEnv *, jclass, jlong h) {
    if (auto *s = reinterpret_cast<Session *>(h)) s->stop = true;
}

JNIEXPORT void JNICALL
Java_org_escc_ecomini_engine_LlamaNative_free(JNIEnv *, jclass, jlong h) {
    free_session(reinterpret_cast<Session *>(h));
}

}  // extern "C"
