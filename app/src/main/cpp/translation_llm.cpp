#include <jni.h>
#include <android/log.h>
#include <ggml-backend.h>
#include <ggml-cpu.h>
#include <llama.h>

#include <sched.h>
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {
constexpr const char * LOG_TAG = "MihonLocalLlm";
constexpr int32_t MAX_BATCH_TOKENS = 1024;
constexpr int32_t MAX_UBATCH_TOKENS = 512;
constexpr int32_t MAX_MODEL_THREADS = 4;
// GGML threadpool tuning knobs. Benchmark: 4 vs 3 threads, strict vs soft pinning, poll 0 vs 50.
constexpr bool POOL_STRICT_PINNING = true;
constexpr uint32_t POOL_POLL_LEVEL = 75; // keeps CPU performance cores at active frequency between autoregressive decode steps
#ifndef MIHON_BUILD_TYPE
#define MIHON_BUILD_TYPE "unknown"
#endif

bool llamaAbortCallback(void * data) {
    auto * cancelled = static_cast<std::atomic_bool *>(data);
    return cancelled != nullptr && cancelled->load(std::memory_order_relaxed);
}

struct Engine {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    llama_sampler * sampler = nullptr;
    const llama_vocab * vocab = nullptr;
    struct ggml_threadpool * threadpool = nullptr;
    decltype(ggml_threadpool_free) * threadpool_free_fn = nullptr;
    float temperature = 0.0f;
    std::vector<int> perfCores;
    int threads = MAX_MODEL_THREADS;
    bool threadpoolTried = false;
    pid_t threadpoolOwnerTid = 0;
    // Tokens currently resident in KV sequence 0 (prompt + every generated token that was decoded).
    // Lets consecutive calls reuse the static system-prompt prefix instead of re-evaluating it.
    std::vector<llama_token> kvTokens;
    std::mutex mutex;
    std::atomic_bool cancelled = false;
    std::atomic_bool destroyed = false;
};

std::mutex backendMutex;
bool backendInitialized = false;

static void llamaLogCallback(ggml_log_level level, const char * text, void * /*user_data*/) {
    if (text == nullptr || text[0] == '\0') return;
    int priority = ANDROID_LOG_DEBUG;
    if (level == GGML_LOG_LEVEL_ERROR) priority = ANDROID_LOG_ERROR;
    else if (level == GGML_LOG_LEVEL_WARN) priority = ANDROID_LOG_WARN;
    else if (level == GGML_LOG_LEVEL_INFO) priority = ANDROID_LOG_INFO;
    __android_log_write(priority, "MihonLlama", text);
}

void logMessage(int priority, const std::string &message) {
    __android_log_write(priority, LOG_TAG, message.c_str());
}

static std::vector<int> getPerformanceCores() {
    std::vector<std::pair<int, int>> cpuFreqs; // <cpu_id, max_freq>
    int numCpus = sysconf(_SC_NPROCESSORS_CONF);
    if (numCpus <= 0) numCpus = static_cast<int>(std::thread::hardware_concurrency());
    for (int i = 0; i < numCpus; ++i) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/cpuinfo_max_freq";
        FILE *f = fopen(path.c_str(), "r");
        if (f != nullptr) {
            int freq = 0;
            if (fscanf(f, "%d", &freq) == 1) {
                cpuFreqs.push_back({i, freq});
            }
            fclose(f);
        }
    }
    if (cpuFreqs.empty()) {
        std::vector<int> fallback;
        const int start = numCpus >= 8 ? 4 : std::max(0, numCpus / 2);
        for (int i = start; i < numCpus; ++i) fallback.push_back(i);
        return fallback;
    }
    std::sort(cpuFreqs.begin(), cpuFreqs.end(), [](const auto &a, const auto &b) {
        return a.second > b.second;
    });
    // Pick cores with max frequency >= 80% of peak frequency (Performance/Prime cluster)
    const int maxFreq = cpuFreqs[0].second;
    const int cutoffFreq = (maxFreq * 8) / 10;
    std::vector<int> perfCores;
    for (const auto &pair : cpuFreqs) {
        if (pair.second >= cutoffFreq && perfCores.size() < 4) {
            perfCores.push_back(pair.first);
        }
    }
    if (perfCores.empty()) {
        for (size_t i = 0; i < std::min<size_t>(4, cpuFreqs.size()); ++i) {
            perfCores.push_back(cpuFreqs[i].first);
        }
    }
    return perfCores;
}

static void applyThreadAffinity(const std::vector<int> &cores) {
    if (cores.empty()) return;
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    for (int core : cores) {
        if (core >= 0 && core < CPU_SETSIZE) {
            CPU_SET(core, &cpuset);
        }
    }
    sched_setaffinity(0, sizeof(cpu_set_t), &cpuset);
}

void throwIllegalState(JNIEnv *env, const std::string &message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

std::string fromJavaString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

Engine *fromHandle(JNIEnv *env, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(static_cast<intptr_t>(handle));
    if (engine == nullptr) throwIllegalState(env, "The local translation model is not loaded");
    return engine;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    const int32_t capacity = static_cast<int32_t>(text.size()) + 16;
    std::vector<llama_token> tokens(static_cast<size_t>(capacity));
    int32_t count = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.size()),
        tokens.data(),
        capacity,
        false,
        true
    );
    if (count < 0) {
        tokens.resize(static_cast<size_t>(-count));
        count = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.size()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            false,
            true
        );
    }
    if (count < 0) throw std::runtime_error("Qwen prompt could not be tokenized");
    tokens.resize(static_cast<size_t>(count));
    return tokens;
}

int decodePrompt(Engine *engine, const llama_token *tokens, size_t total) {
    for (size_t offset = 0; offset < total; offset += MAX_BATCH_TOKENS) {
        if (engine->cancelled.load(std::memory_order_relaxed)) return 2;
        const auto count = static_cast<int32_t>(std::min<size_t>(MAX_BATCH_TOKENS, total - offset));
        llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens + offset), count);
        const int32_t status = llama_decode(engine->context, batch);
        if (status != 0) return status;
    }
    return 0;
}

// Length of the shared prefix between what is already in the KV cache and the next prompt.
// Always leaves >= 1 token to decode so llama_decode() produces logits for the sampler.
size_t commonPrefixLength(const std::vector<llama_token> &cached, const std::vector<llama_token> &next) {
    if (next.empty()) return 0;
    const size_t limit = std::min(cached.size(), next.size() - 1);
    size_t n = 0;
    while (n < limit && cached[n] == next[n]) ++n;
    return n;
}

void resetKv(Engine *engine) {
    engine->kvTokens.clear();
    if (engine->context != nullptr) llama_memory_clear(llama_get_memory(engine->context), false);
}

// If a call fails or is cancelled the KV contents are unknown: force a cold start on the next call.
struct KvGuard {
    Engine *engine;
    bool ok = false;
    explicit KvGuard(Engine *e) : engine(e) {}
    ~KvGuard() { if (!ok) resetKv(engine); }
};

pid_t currentTid() {
    return static_cast<pid_t>(syscall(__NR_gettid));
}

std::string currentThreadCpusAllowed() {
    FILE *f = fopen("/proc/thread-self/status", "r");
    if (f == nullptr) return "?";
    char line[256];
    std::string result = "?";
    while (fgets(line, sizeof(line), f) != nullptr) {
        if (strncmp(line, "Cpus_allowed_list:", 18) == 0) {
            result = line + 18;
            while (!result.empty() && (result.front() == '\t' || result.front() == ' ')) result.erase(result.begin());
            while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) result.pop_back();
            break;
        }
    }
    fclose(f);
    return result;
}

// Creates the GGML threadpool on the CALLING thread. ggml pins its creator as "worker 0" (the thread that
// really runs graph compute) and spawns workers 1..n-1 that inherit the creator's nice value, cgroup and
// thread name. It therefore MUST run on the same thread that later calls llama_decode() - i.e. the
// "mihon-local-llm" executor thread, not whichever coroutine thread happened to call nativeCreate().
void ensureThreadpool(Engine *engine) {
    if (engine->threadpool != nullptr || engine->threadpoolTried) return;
    engine->threadpoolTried = true;

    auto *cpuDev = ggml_backend_dev_by_type(GGML_BACKEND_DEVICE_TYPE_CPU);
    if (cpuDev == nullptr) {
        logMessage(ANDROID_LOG_WARN, "No CPU backend device; using llama.cpp's default threadpool");
        return;
    }
    auto *reg = ggml_backend_dev_backend_reg(cpuDev);
    auto *newFn = (decltype(ggml_threadpool_new) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_new");
    engine->threadpool_free_fn = (decltype(ggml_threadpool_free) *) ggml_backend_reg_get_proc_address(reg, "ggml_threadpool_free");
    if (newFn == nullptr || engine->threadpool_free_fn == nullptr) {
        logMessage(ANDROID_LOG_WARN, "ggml_threadpool_new/free not found in CPU backend reg; using default threadpool");
        return;
    }

    struct ggml_threadpool_params tpp = ggml_threadpool_params_default(engine->threads);
    for (int i = 0; i < GGML_MAX_N_THREADS; ++i) tpp.cpumask[i] = false;
    for (int core : engine->perfCores) {
        if (core >= 0 && core < GGML_MAX_N_THREADS) tpp.cpumask[core] = true;
    }
    tpp.strict_cpu = POOL_STRICT_PINNING;
    // NORMAL: HIGH maps to SCHED_FIFO/80, which untrusted Android apps are not allowed to request. Priority
    // is inherited from this thread (Kotlin sets THREAD_PRIORITY_FOREGROUND on it) instead.
    tpp.prio = GGML_SCHED_PRIO_NORMAL;
    tpp.poll = POOL_POLL_LEVEL;
    engine->threadpool = newFn(&tpp);
    if (engine->threadpool == nullptr) {
        logMessage(ANDROID_LOG_WARN, "ggml_threadpool_new failed; using default threadpool");
        return;
    }
    engine->threadpoolOwnerTid = currentTid();
    llama_attach_threadpool(engine->context, engine->threadpool, engine->threadpool);
    logMessage(ANDROID_LOG_INFO, "GGML threadpool: " + std::to_string(engine->threads) + " threads, strict=" +
        std::to_string(POOL_STRICT_PINNING ? 1 : 0) + ", poll=" + std::to_string(POOL_POLL_LEVEL) +
        ", owner tid=" + std::to_string(engine->threadpoolOwnerTid) +
        ", owner Cpus_allowed_list=" + currentThreadCpusAllowed());
}

std::string tokenPiece(const llama_vocab *vocab, llama_token token) {
    std::vector<char> buffer(128);
    int32_t count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    if (count < 0) {
        buffer.resize(static_cast<size_t>(-count));
        count = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (count <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(count));
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_mihon_feature_translation_pipeline_LocalLlmRuntime_nativeCreate(
    JNIEnv *env,
    jobject /*thisObject*/,
    jstring jModelPath,
    jstring jNativeLibraryPath,
    jfloat requestedTemperature,
    jint requestedContextSize,
    jint requestedThreads
) {
    const std::string modelPath = fromJavaString(env, jModelPath);
    const std::string nativeLibraryPath = fromJavaString(env, jNativeLibraryPath);
    if (modelPath.empty()) {
        throwIllegalState(env, "The bundled Qwen model path is empty");
        return 0;
    }

    auto engine = std::unique_ptr<Engine>(new (std::nothrow) Engine());
    if (!engine) {
        throwIllegalState(env, "Not enough memory to create the local translation runtime");
        return 0;
    }

    try {
        {
            std::lock_guard<std::mutex> backendLock(backendMutex);
            if (!backendInitialized) {
                llama_log_set(llamaLogCallback, nullptr);
                if (!nativeLibraryPath.empty()) ggml_backend_load_all_from_path(nativeLibraryPath.c_str());
                llama_backend_init();
                backendInitialized = true;
            }
        }

        llama_model_params modelParams = llama_model_default_params();
        modelParams.n_gpu_layers = 0;
        modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;
        engine->model = llama_model_load_from_file(modelPath.c_str(), modelParams);
        if (engine->model == nullptr) throw std::runtime_error("Local Index-Translate 2B model failed to load");

        engine->perfCores = getPerformanceCores();
        std::string coresStr;
        for (int c : engine->perfCores) {
            if (!coresStr.empty()) coresStr += ",";
            coresStr += std::to_string(c);
        }
        logMessage(ANDROID_LOG_INFO, "Target performance CPU cores: [" + coresStr + "]");

        const int perfCount = !engine->perfCores.empty() ? static_cast<int>(engine->perfCores.size()) : MAX_MODEL_THREADS;
    const int threads = std::max(1, std::min(perfCount, requestedThreads > 0 ? static_cast<int>(requestedThreads) : perfCount));
    engine->threads = threads;
        llama_context_params contextParams = llama_context_default_params();
        contextParams.n_ctx = static_cast<uint32_t>(std::max(1024, static_cast<int>(requestedContextSize)));
        contextParams.n_batch = MAX_BATCH_TOKENS;
        contextParams.n_ubatch = MAX_UBATCH_TOKENS;
        contextParams.n_seq_max = 1;
        contextParams.n_threads = threads;
        contextParams.n_threads_batch = threads;
        contextParams.offload_kqv = false;
        contextParams.no_perf = true;
        contextParams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;
        engine->context = llama_init_from_model(engine->model, contextParams);
        if (engine->context == nullptr) throw std::runtime_error("Index-Translate context allocation failed");
        llama_set_abort_callback(engine->context, llamaAbortCallback, &engine->cancelled);

        // The dedicated GGML threadpool is created lazily in nativeGenerate() -> ensureThreadpool(), on the
        // thread that actually runs llama_decode(). Creating it here pinned the wrong (coroutine) thread.
        // Diagnostics: which CPU kernel variant was selected, and was the native stack built optimised?
        logMessage(ANDROID_LOG_INFO, std::string("Native build type: ") + MIHON_BUILD_TYPE);
        logMessage(ANDROID_LOG_INFO, std::string("llama system info: ") + llama_print_system_info());

        engine->vocab = llama_model_get_vocab(engine->model);
        if (engine->vocab == nullptr) throw std::runtime_error("Index-Translate vocabulary is unavailable");

        const int32_t nVocab = llama_vocab_n_tokens(engine->vocab);
        (void)nVocab;

        engine->temperature = requestedTemperature;
        engine->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (engine->sampler != nullptr) {
            // Official Index-Translate-2B decoding parameters:
            // temperature = 0.0 (Greedy), repetition_penalty = 1.0 (Disabled so repeated character names,
            // honorifics, pronouns, stutters, and line numbers across whole-page batches are never penalized).
            if (requestedTemperature <= 0.0f) {
                llama_sampler_chain_add(engine->sampler, llama_sampler_init_greedy());
            } else {
                llama_sampler_chain_add(engine->sampler, llama_sampler_init_temp(requestedTemperature));
                llama_sampler_chain_add(engine->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
            }
        }
        if (engine->sampler == nullptr) throw std::runtime_error("Index-Translate sampler allocation failed");

        logMessage(ANDROID_LOG_INFO, "Loaded local Index-Translate 2B model with " + std::to_string(threads) + " CPU threads");
        return static_cast<jlong>(reinterpret_cast<intptr_t>(engine.release()));
    } catch (const std::exception &error) {
        if (engine->context != nullptr) llama_detach_threadpool(engine->context);
        if (engine->threadpool != nullptr && engine->threadpool_free_fn != nullptr) {
            engine->threadpool_free_fn(engine->threadpool);
            engine->threadpool = nullptr;
        }
        if (engine->sampler != nullptr) llama_sampler_free(engine->sampler);
        if (engine->context != nullptr) llama_free(engine->context);
        if (engine->model != nullptr) llama_model_free(engine->model);
        throwIllegalState(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_mihon_feature_translation_pipeline_LocalLlmRuntime_nativeGenerate(
    JNIEnv *env,
    jobject /*thisObject*/,
    jlong handle,
    jstring jSystemPrompt,
    jstring jPrompt,
    jint maxTokens,
    jint lineCount
) {
    Engine *engine = fromHandle(env, handle);
    if (engine == nullptr) return nullptr;

    const std::string systemPrompt = fromJavaString(env, jSystemPrompt);
    const std::string userPrompt = fromJavaString(env, jPrompt);
    if (systemPrompt.empty()) {
        throwIllegalState(env, "The local translation system prompt is empty");
        return nullptr;
    }
    if (userPrompt.empty()) {
        throwIllegalState(env, "Cannot translate an empty dialogue batch");
        return nullptr;
    }

    std::lock_guard<std::mutex> lock(engine->mutex);
    if (engine->destroyed.load(std::memory_order_relaxed)) {
        return env->NewStringUTF("__MIHON_CANCELLED__");
    }
    // Clear per-call abort flag under mutex so a stray cancel from a completed/timed-out call
    // never poisons subsequent generate() calls on the same runtime instance (Bug 2 fix).
    engine->cancelled.store(false, std::memory_order_relaxed);

    // Boost thread priority and pin calling thread ("worker 0") to performance cores [4-7]
    // BEFORE creating or running the GGML threadpool.
    setpriority(PRIO_PROCESS, 0, -2);
    applyThreadAffinity(engine->perfCores);
    ensureThreadpool(engine);

    // If anything below fails or is cancelled, KV contents are unknown -> force a cold start on the next call.
    KvGuard kvGuard(engine);

    // Use the engine's built-in sampler (reset KV cache / state for a fresh generation).
    // NOTE: The GBNF grammar sampler introduced in v1.7.6 was removed because it caused
    // "Unexpected empty grammar stack" crashes on the device. The numbered output format
    // is already enforced by the prompt instructions; a grammar sampler is not needed.
    llama_sampler_reset(engine->sampler);
    llama_sampler * activeSampler = engine->sampler;
    // lineCount is kept as a parameter for API compatibility but is no longer used here.
    (void)lineCount;

    // Match GGUF chat template with thinking disabled (`enable_thinking: false`)
    // so the model starts emitting numbered lines immediately on token 0.
    const std::string prompt =
        "<|im_start|>system\n" + systemPrompt +
        "\n<|im_end|>\n<|im_start|>user\n" + userPrompt +
        "\n<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n";

    std::string output;
    try {
        const auto inputTokens = tokenize(engine->vocab, prompt);
        const uint32_t contextSize = llama_n_ctx(engine->context);
        if (inputTokens.size() + 32 >= contextSize) {
            throw std::runtime_error("Dialogue prompt exceeds the local model context window");
        }
        const int32_t availableGenTokens = static_cast<int32_t>(contextSize - inputTokens.size() - 8);
        const int32_t outputLimit = std::clamp(
            std::min(static_cast<int32_t>(maxTokens), availableGenTokens),
            1,
            2048
        );

        // ---- KV prefix reuse ---------------------------------------------------------------------
        // The system prompt (and the "<|im_start|>user\n..." lead-in) is identical on every call, so keep its
        // K/V in the cache and evaluate only the tokens after the shared prefix. This subsumes an explicit
        // n_past_system: on the first call nothing is cached (cold); afterwards nReuse >= system-prompt length.
        llama_memory_t memory = llama_get_memory(engine->context);
        size_t nReuse = commonPrefixLength(engine->kvTokens, inputTokens);
        if (nReuse > 0 && !llama_memory_seq_rm(memory, 0, static_cast<llama_pos>(nReuse), -1)) {
            nReuse = 0; // memory type cannot drop a suffix (recurrent / SWA) -> cold start
        }
        if (nReuse == 0) {
            llama_memory_clear(memory, false);
        }
        engine->kvTokens.resize(nReuse);
        const size_t evalTokens = inputTokens.size() - nReuse;

        const auto promptStartTime = std::chrono::steady_clock::now();
        const int promptStatus = decodePrompt(engine, inputTokens.data() + nReuse, evalTokens);
        if (promptStatus == 2 || engine->cancelled.load(std::memory_order_relaxed)) {
            return env->NewStringUTF("__MIHON_CANCELLED__");
        }
        if (promptStatus != 0) throw std::runtime_error("Index-Translate failed while reading the dialogue batch");
        engine->kvTokens.insert(engine->kvTokens.end(), inputTokens.begin() + static_cast<std::ptrdiff_t>(nReuse), inputTokens.end());
        const auto promptDurationMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - promptStartTime
        ).count();

        const auto genStartTime = std::chrono::steady_clock::now();
        int32_t generatedCount = 0;
        for (int32_t step = 0; step < outputLimit; ++step) {
            if (engine->cancelled.load(std::memory_order_relaxed)) {
                return env->NewStringUTF("__MIHON_CANCELLED__");
            }
            const llama_token token = llama_sampler_sample(activeSampler, engine->context, -1);
            if (llama_vocab_is_eog(engine->vocab, token) || llama_vocab_is_control(engine->vocab, token)) break;
            const std::string piece = tokenPiece(engine->vocab, token);
            if (piece.find("<|im_end|>") != std::string::npos ||
                piece.find("<|endoftext|>") != std::string::npos ||
                piece.find("<|im_start|>") != std::string::npos) {
                break;
            }
            output += piece;
            generatedCount++;

            llama_batch tokenBatch = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
            if (llama_decode(engine->context, tokenBatch) != 0) {
                throw std::runtime_error("Index-Translate failed while generating the translation");
            }
            engine->kvTokens.push_back(token);
        }

        const auto genElapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now() - genStartTime
        ).count();
        const int64_t promptTokPerSec = promptDurationMs > 0 ? (static_cast<int64_t>(evalTokens) * 1000 / promptDurationMs) : 0;
        const int64_t genTokPerSec = genElapsedMs > 0 ? (static_cast<int64_t>(generatedCount) * 1000 / genElapsedMs) : 0;
        logMessage(ANDROID_LOG_INFO, "Index-Translate prompt: " + std::to_string(inputTokens.size()) +
            " tokens (" + std::to_string(nReuse) + " reused from KV, " + std::to_string(evalTokens) + " evaluated) in " +
            std::to_string(promptDurationMs) + "ms (" + std::to_string(promptTokPerSec) + " tok/s); " +
            "generated " + std::to_string(generatedCount) + " tokens in " + std::to_string(genElapsedMs) + "ms (" +
            std::to_string(genTokPerSec) + " tok/s); decode thread Cpus_allowed_list=" + currentThreadCpusAllowed());
        kvGuard.ok = true;
    } catch (const std::exception &error) {
        throwIllegalState(env, error.what());
        return nullptr;
    }

    return env->NewStringUTF(output.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_translation_pipeline_LocalLlmRuntime_nativeCancel(
    JNIEnv *env,
    jobject /*thisObject*/,
    jlong handle
) {
    Engine *engine = fromHandle(env, handle);
    if (engine != nullptr) engine->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_translation_pipeline_LocalLlmRuntime_nativeReset(
    JNIEnv *env,
    jobject /*thisObject*/,
    jlong handle
) {
    Engine *engine = fromHandle(env, handle);
    if (engine != nullptr) {
        std::lock_guard<std::mutex> lock(engine->mutex);
        engine->cancelled.store(false, std::memory_order_relaxed);
        resetKv(engine);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_mihon_feature_translation_pipeline_LocalLlmRuntime_nativeDestroy(
    JNIEnv *env,
    jobject /*thisObject*/,
    jlong handle
) {
    Engine *engine = fromHandle(env, handle);
    if (engine == nullptr) return;

    engine->destroyed.store(true, std::memory_order_relaxed);
    engine->cancelled.store(true, std::memory_order_relaxed);
    {
        std::lock_guard<std::mutex> lock(engine->mutex);
        if (engine->context != nullptr) {
            llama_detach_threadpool(engine->context);
        }
        if (engine->threadpool != nullptr && engine->threadpool_free_fn != nullptr) {
            engine->threadpool_free_fn(engine->threadpool);
            engine->threadpool = nullptr;
        }
        if (engine->sampler != nullptr) llama_sampler_free(engine->sampler);
        if (engine->context != nullptr) llama_free(engine->context);
        if (engine->model != nullptr) llama_model_free(engine->model);
    }
    delete engine;
}
