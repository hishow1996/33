#include <jni.h>
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <exception>
#include <mutex>
#include <string>
#include <vector>

namespace {
std::mutex g_mutex;
std::atomic<bool> g_cancelled{false};
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
const llama_vocab * g_vocab = nullptr;
std::string g_model_path;
uint32_t g_seed = 42;
bool g_backend_initialized = false;

bool abort_callback(void *) {
    return g_cancelled.load(std::memory_order_relaxed);
}

void close_locked() {
    if (g_context != nullptr) {
        llama_free(g_context);
        g_context = nullptr;
    }
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    g_vocab = nullptr;
    g_model_path.clear();
}

jstring to_java_string(JNIEnv * env, const std::string & value) {
    return env->NewStringUTF(value.c_str());
}

std::string from_java_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string wrap_hunyuan_chat(const std::string & prompt) {
    // This mirrors Tencent's published chat_template.jinja for a single user message
    // and starts generation at the assistant role marker.
    return "<｜hy_begin▁of▁sentence｜><｜hy_User｜>" + prompt + "<｜hy_Assistant｜>";
}

std::vector<llama_token> tokenize_prompt(const std::string & prompt) {
    const int32_t needed = llama_tokenize(
        g_vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        nullptr,
        0,
        false,
        true
    );
    if (needed == INT32_MIN || needed == 0) {
        throw std::runtime_error("HY-MT1.5 tokenizer could not measure the prompt.");
    }
    const int32_t count = needed < 0 ? -needed : needed;
    if (count <= 0) throw std::runtime_error("HY-MT1.5 prompt is empty after tokenization.");

    std::vector<llama_token> tokens(static_cast<size_t>(count));
    const int32_t written = llama_tokenize(
        g_vocab,
        prompt.data(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        count,
        false,
        true
    );
    if (written < 0) {
        throw std::runtime_error("HY-MT1.5 tokenizer could not encode the prompt.");
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::string token_piece(llama_token token) {
    std::vector<char> buffer(1024);
    int32_t length = llama_token_to_piece(g_vocab, token, buffer.data(),
                                          static_cast<int32_t>(buffer.size()), 0, false);
    if (length < 0) {
        buffer.resize(static_cast<size_t>(-length) + 8U);
        length = llama_token_to_piece(g_vocab, token, buffer.data(),
                                      static_cast<int32_t>(buffer.size()), 0, false);
    }
    if (length <= 0) return {};
    return std::string(buffer.data(), static_cast<size_t>(length));
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_translatetube_local_translate_HyMtNative_nativeOpen(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jint threads,
    jint context_tokens
) {
    std::lock_guard<std::mutex> guard(g_mutex);
    try {
        const std::string path = from_java_string(env, model_path);
        if (path.empty()) return to_java_string(env, "模型文件路径为空。");

        if (g_model != nullptr && g_context != nullptr && g_model_path == path) {
            llama_set_n_threads(g_context, std::max(1, static_cast<int>(threads)),
                                std::max(1, static_cast<int>(threads)));
            return nullptr;
        }
        close_locked();
        if (!g_backend_initialized) {
            llama_backend_init();
            g_backend_initialized = true;
        }

        auto model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        model_params.use_mmap = true;
        model_params.use_mlock = false;
        model_params.check_tensors = true;
        g_model = llama_model_load_from_file(path.c_str(), model_params);
        if (g_model == nullptr) {
            return to_java_string(env, "HY-MT1.5 GGUF 加载失败。请检查模型文件是否完整，或确认此版本包含 Q2_0C 内核。");
        }

        auto context_params = llama_context_default_params();
        context_params.n_ctx = static_cast<uint32_t>(std::clamp(
            static_cast<int>(context_tokens), 1024, 8192));
        context_params.n_batch = 512;
        context_params.n_ubatch = 128;
        context_params.n_threads = std::max(1, static_cast<int>(threads));
        context_params.n_threads_batch = std::max(1, static_cast<int>(threads));
        g_context = llama_init_from_model(g_model, context_params);
        if (g_context == nullptr) {
            close_locked();
            return to_java_string(env, "HY-MT1.5 推理上下文创建失败，可能是设备可用内存不足。");
        }

        g_vocab = llama_model_get_vocab(g_model);
        if (g_vocab == nullptr) {
            close_locked();
            return to_java_string(env, "HY-MT1.5 模型词表无法读取。");
        }
        g_model_path = path;
        llama_set_abort_callback(g_context, abort_callback, nullptr);
        return nullptr;
    } catch (const std::exception & error) {
        close_locked();
        return to_java_string(env, std::string("HY-MT1.5 加载失败：") + error.what());
    } catch (...) {
        close_locked();
        return to_java_string(env, "HY-MT1.5 加载失败：未知原生异常。");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_io_translatetube_local_translate_HyMtNative_nativeTranslate(
    JNIEnv * env,
    jobject,
    jstring user_prompt,
    jint max_tokens
) {
    std::lock_guard<std::mutex> guard(g_mutex);
    try {
        if (g_model == nullptr || g_context == nullptr || g_vocab == nullptr) {
            return to_java_string(env, "HY-MT1.5 模型尚未加载。");
        }
        const std::string raw = from_java_string(env, user_prompt);
        if (raw.empty()) return to_java_string(env, "");

        g_cancelled.store(false, std::memory_order_relaxed);
        llama_memory_clear(llama_get_memory(g_context), true);
        const std::string prompt = wrap_hunyuan_chat(raw);
        auto tokens = tokenize_prompt(prompt);
        const int32_t limit = std::clamp(static_cast<int>(max_tokens), 32, 768);
        const uint32_t context_limit = llama_n_ctx(g_context);
        if (tokens.size() + static_cast<size_t>(limit) + 8U > context_limit) {
            return to_java_string(env, "字幕内容过长，超过 HY-MT1.5 当前上下文长度。");
        }

        llama_batch batch = llama_batch_init(static_cast<int32_t>(tokens.size()), 0, 1);
        if (batch.token == nullptr || batch.pos == nullptr || batch.n_seq_id == nullptr ||
            batch.seq_id == nullptr || batch.logits == nullptr) {
            llama_batch_free(batch);
            return to_java_string(env, "无法为 HY-MT1.5 分配输入缓冲区。");
        }
        batch.n_tokens = static_cast<int32_t>(tokens.size());
        for (int32_t i = 0; i < batch.n_tokens; ++i) {
            batch.token[i] = tokens[static_cast<size_t>(i)];
            batch.pos[i] = i;
            batch.n_seq_id[i] = 1;
            batch.seq_id[i][0] = 0;
            batch.logits[i] = (i == batch.n_tokens - 1) ? 1 : 0;
        }
        const int32_t decode_result = llama_decode(g_context, batch);
        llama_batch_free(batch);
        if (decode_result != 0) {
            if (g_cancelled.load(std::memory_order_relaxed)) {
                return to_java_string(env, "__HYMT_CANCELLED__");
            }
            return to_java_string(env, "HY-MT1.5 输入编码失败，推理已停止。");
        }

        auto sampler_params = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
        if (sampler == nullptr) {
            return to_java_string(env, "HY-MT1.5 采样器初始化失败。");
        }
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.6f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(g_seed++));

        std::string output;
        output.reserve(raw.size() * 3U);
        for (int step = 0; step < limit; ++step) {
            if (g_cancelled.load(std::memory_order_relaxed)) {
                llama_sampler_free(sampler);
                return to_java_string(env, "__HYMT_CANCELLED__");
            }
            const llama_token token = llama_sampler_sample(sampler, g_context, -1);
            if (llama_vocab_is_eog(g_vocab, token)) break;
            output += token_piece(token);
            llama_sampler_accept(sampler, token);
            llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
            const int32_t result = llama_decode(g_context, next);
            if (result != 0) {
                llama_sampler_free(sampler);
                if (g_cancelled.load(std::memory_order_relaxed)) {
                    return to_java_string(env, "__HYMT_CANCELLED__");
                }
                return to_java_string(env, "HY-MT1.5 生成译文时解码失败。");
            }
        }
        llama_sampler_free(sampler);
        return to_java_string(env, output);
    } catch (const std::exception & error) {
        return to_java_string(env, std::string("HY-MT1.5 推理失败：") + error.what());
    } catch (...) {
        return to_java_string(env, "HY-MT1.5 推理失败：未知原生异常。");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_translatetube_local_translate_HyMtNative_nativeClose(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_mutex);
    close_locked();
    g_cancelled.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_io_translatetube_local_translate_HyMtNative_nativeCancel(JNIEnv *, jobject) {
    g_cancelled.store(true, std::memory_order_relaxed);
}
