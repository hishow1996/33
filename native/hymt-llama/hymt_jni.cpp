#include <jni.h>
#include "llama.h"

#include <algorithm>
#include <atomic>
#include <climits>
#include <cstdint>
#include <exception>
#include <mutex>
#include <memory>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex g_mutex;
std::atomic<bool> g_cancelled{false};
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;
const llama_vocab * g_vocab = nullptr;
std::string g_model_path;
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

void append_utf8(std::string & output, uint32_t codepoint) {
    if (codepoint <= 0x7fU) {
        output.push_back(static_cast<char>(codepoint));
    } else if (codepoint <= 0x7ffU) {
        output.push_back(static_cast<char>(0xc0U | (codepoint >> 6U)));
        output.push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else if (codepoint <= 0xffffU) {
        output.push_back(static_cast<char>(0xe0U | (codepoint >> 12U)));
        output.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
        output.push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    } else {
        output.push_back(static_cast<char>(0xf0U | (codepoint >> 18U)));
        output.push_back(static_cast<char>(0x80U | ((codepoint >> 12U) & 0x3fU)));
        output.push_back(static_cast<char>(0x80U | ((codepoint >> 6U) & 0x3fU)));
        output.push_back(static_cast<char>(0x80U | (codepoint & 0x3fU)));
    }
}

std::u16string decode_utf8_to_utf16(const std::string & input) {
    std::u16string output;
    output.reserve(input.size());
    size_t i = 0;
    while (i < input.size()) {
        const uint8_t first = static_cast<uint8_t>(input[i]);
        uint32_t codepoint = 0xfffdU;
        size_t count = 1;
        if (first <= 0x7fU) {
            codepoint = first;
        } else if (first >= 0xc2U && first <= 0xdfU && i + 1 < input.size()) {
            const uint8_t b1 = static_cast<uint8_t>(input[i + 1]);
            if ((b1 & 0xc0U) == 0x80U) {
                codepoint = ((first & 0x1fU) << 6U) | (b1 & 0x3fU);
                count = 2;
            }
        } else if (first >= 0xe0U && first <= 0xefU && i + 2 < input.size()) {
            const uint8_t b1 = static_cast<uint8_t>(input[i + 1]);
            const uint8_t b2 = static_cast<uint8_t>(input[i + 2]);
            if ((b1 & 0xc0U) == 0x80U && (b2 & 0xc0U) == 0x80U &&
                !(first == 0xe0U && b1 < 0xa0U) &&
                !(first == 0xedU && b1 >= 0xa0U)) {
                codepoint = ((first & 0x0fU) << 12U) |
                    ((b1 & 0x3fU) << 6U) | (b2 & 0x3fU);
                count = 3;
            }
        } else if (first >= 0xf0U && first <= 0xf4U && i + 3 < input.size()) {
            const uint8_t b1 = static_cast<uint8_t>(input[i + 1]);
            const uint8_t b2 = static_cast<uint8_t>(input[i + 2]);
            const uint8_t b3 = static_cast<uint8_t>(input[i + 3]);
            if ((b1 & 0xc0U) == 0x80U && (b2 & 0xc0U) == 0x80U &&
                (b3 & 0xc0U) == 0x80U &&
                !(first == 0xf0U && b1 < 0x90U) &&
                !(first == 0xf4U && b1 > 0x8fU)) {
                codepoint = ((first & 0x07U) << 18U) |
                    ((b1 & 0x3fU) << 12U) |
                    ((b2 & 0x3fU) << 6U) | (b3 & 0x3fU);
                count = 4;
            }
        }

        if (codepoint <= 0xffffU) {
            output.push_back(static_cast<char16_t>(codepoint));
        } else {
            codepoint -= 0x10000U;
            output.push_back(static_cast<char16_t>(0xd800U + (codepoint >> 10U)));
            output.push_back(static_cast<char16_t>(0xdc00U + (codepoint & 0x3ffU)));
        }
        i += count;
    }
    return output;
}

std::string encode_utf16_to_utf8(const jchar * input, jsize length) {
    std::string output;
    output.reserve(static_cast<size_t>(length) * 3U);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = input[i];
        if (codepoint >= 0xd800U && codepoint <= 0xdbffU) {
            if (i + 1 < length && input[i + 1] >= 0xdc00U && input[i + 1] <= 0xdfffU) {
                codepoint = 0x10000U +
                    ((codepoint - 0xd800U) << 10U) +
                    (static_cast<uint32_t>(input[++i]) - 0xdc00U);
            } else {
                codepoint = 0xfffdU;
            }
        } else if (codepoint >= 0xdc00U && codepoint <= 0xdfffU) {
            codepoint = 0xfffdU;
        }
        append_utf8(output, codepoint);
    }
    return output;
}

jstring to_java_string(JNIEnv * env, const std::string & value) {
    const std::u16string utf16 = decode_utf8_to_utf16(value);
    if (utf16.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
    return env->NewString(
        reinterpret_cast<const jchar *>(utf16.data()),
        static_cast<jsize>(utf16.size())
    );
}

jstring throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
    return nullptr;
}

std::string from_java_string(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result = encode_utf16_to_utf8(chars, length);
    env->ReleaseStringChars(value, chars);
    return result;
}

std::vector<llama_token> tokenize_text_piece(
    const std::string & text,
    bool parse_special
) {
    if (text.size() > static_cast<size_t>(std::numeric_limits<int32_t>::max())) {
        throw std::runtime_error("HY-MT1.5 输入文本过长。");
    }
    const int32_t text_length = static_cast<int32_t>(text.size());
    const int32_t needed = llama_tokenize(
        g_vocab, text.data(), text_length, nullptr, 0, false, parse_special
    );
    if (needed == INT32_MIN) {
        throw std::runtime_error("HY-MT1.5 tokenizer 输入长度超出上限。");
    }
    if (needed == 0) return {};
    const int32_t count = needed < 0 ? -needed : needed;
    if (count <= 0) throw std::runtime_error("HY-MT1.5 tokenizer 返回了无效长度。");

    std::vector<llama_token> tokens(static_cast<size_t>(count));
    const int32_t written = llama_tokenize(
        g_vocab, text.data(), text_length, tokens.data(), count, false, parse_special
    );
    if (written < 0 || written > count) {
        throw std::runtime_error("HY-MT1.5 tokenizer 无法完成文本编码。");
    }
    tokens.resize(static_cast<size_t>(written));
    return tokens;
}

std::vector<llama_token> tokenize_prompt(const std::string & user_text) {
    // HY-MT1.5 V1 uses: BOS + USER marker + plain user text + ASSISTANT marker.
    // Tokenize control markers separately so subtitle text containing literal
    // marker spellings cannot inject a new chat role or terminate the prompt.
    const llama_token bos = llama_vocab_bos(g_vocab);
    if (bos < 0) {
        throw std::runtime_error("HY-MT1.5 GGUF 未提供有效 BOS token。");
    }

    const auto user_marker = tokenize_text_piece("<｜hy_User｜>", true);
    const auto assistant_marker = tokenize_text_piece("<｜hy_Assistant｜>", true);
    if (user_marker.size() != 1U || assistant_marker.size() != 1U) {
        throw std::runtime_error("HY-MT1.5 GGUF 缺少 V1 对话控制 token。");
    }

    const auto content_tokens = tokenize_text_piece(user_text, false);
    std::vector<llama_token> tokens;
    tokens.reserve(1U + user_marker.size() + content_tokens.size() + assistant_marker.size());
    tokens.push_back(bos);
    tokens.insert(tokens.end(), user_marker.begin(), user_marker.end());
    tokens.insert(tokens.end(), content_tokens.begin(), content_tokens.end());
    tokens.insert(tokens.end(), assistant_marker.begin(), assistant_marker.end());
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
            return throw_illegal_state(env, "HY-MT1.5 模型尚未加载。");
        }
        const std::string raw = from_java_string(env, user_prompt);
        if (raw.empty()) return to_java_string(env, "");

        if (g_cancelled.load(std::memory_order_relaxed)) {
            return to_java_string(env, "__HYMT_CANCELLED__");
        }
        llama_memory_clear(llama_get_memory(g_context), true);
        auto tokens = tokenize_prompt(raw);
        const int32_t limit = std::clamp(static_cast<int>(max_tokens), 32, 768);
        const uint32_t context_limit = llama_n_ctx(g_context);
        if (tokens.size() + static_cast<size_t>(limit) + 8U > context_limit) {
            return throw_illegal_state(env, "字幕内容过长，超过 HY-MT1.5 当前上下文长度。");
        }

        constexpr size_t kPrefillBatchTokens = 512U;
        for (size_t offset = 0; offset < tokens.size(); offset += kPrefillBatchTokens) {
            if (g_cancelled.load(std::memory_order_relaxed)) {
                return to_java_string(env, "__HYMT_CANCELLED__");
            }
            const size_t chunk_size = std::min(kPrefillBatchTokens, tokens.size() - offset);
            llama_batch batch = llama_batch_init(static_cast<int32_t>(chunk_size), 0, 1);
            if (batch.token == nullptr || batch.pos == nullptr || batch.n_seq_id == nullptr ||
                batch.seq_id == nullptr || batch.logits == nullptr) {
                llama_batch_free(batch);
                return throw_illegal_state(env, "无法为 HY-MT1.5 分配输入缓冲区。");
            }
            batch.n_tokens = static_cast<int32_t>(chunk_size);
            for (int32_t i = 0; i < batch.n_tokens; ++i) {
                const size_t token_index = offset + static_cast<size_t>(i);
                batch.token[i] = tokens[token_index];
                batch.pos[i] = static_cast<llama_pos>(token_index);
                batch.n_seq_id[i] = 1;
                batch.seq_id[i][0] = 0;
                batch.logits[i] = token_index + 1U == tokens.size() ? 1 : 0;
            }
            const int32_t decode_result = llama_decode(g_context, batch);
            llama_batch_free(batch);
            if (decode_result != 0) {
                if (g_cancelled.load(std::memory_order_relaxed)) {
                    return to_java_string(env, "__HYMT_CANCELLED__");
                }
                return throw_illegal_state(env, "HY-MT1.5 输入编码失败，推理已停止。");
            }
        }

        auto sampler_params = llama_sampler_chain_default_params();
        llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
        if (sampler == nullptr) {
            return throw_illegal_state(env, "HY-MT1.5 采样器初始化失败。");
        }
        // Match Tencent's published HY-MT1.5 decoding recommendations.
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.6f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(42));

        std::string output;
        output.reserve(raw.size() * 3U);
        bool reached_end_token = false;
        for (int step = 0; step < limit; ++step) {
            if (g_cancelled.load(std::memory_order_relaxed)) {
                llama_sampler_free(sampler);
                return to_java_string(env, "__HYMT_CANCELLED__");
            }
            const llama_token token = llama_sampler_sample(sampler, g_context, -1);
            // HY-MT1.5 V1 normally ends with token 120020. Some quantized
            // checkpoints can emit the legacy raw EOS (120001) instead.
            if (llama_vocab_is_eog(g_vocab, token) || token == 120001) {
                reached_end_token = true;
                break;
            }
            output += token_piece(token);
            llama_sampler_accept(sampler, token);
            llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
            const int32_t result = llama_decode(g_context, next);
            if (result != 0) {
                llama_sampler_free(sampler);
                if (g_cancelled.load(std::memory_order_relaxed)) {
                    return to_java_string(env, "__HYMT_CANCELLED__");
                }
                return throw_illegal_state(env, "HY-MT1.5 生成译文时解码失败。");
            }
        }
        llama_sampler_free(sampler);
        if (!reached_end_token) {
            return throw_illegal_state(env, "HY-MT1.5 译文达到生成长度上限，未完整结束。");
        }
        return to_java_string(env, output);
    } catch (const std::exception & error) {
        return throw_illegal_state(env, std::string("HY-MT1.5 推理失败：") + error.what());
    } catch (...) {
        return throw_illegal_state(env, "HY-MT1.5 推理失败：未知原生异常。");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_translatetube_local_translate_HyMtNative_nativeResetCancel(JNIEnv *, jobject) {
    g_cancelled.store(false, std::memory_order_relaxed);
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
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM * vm, void *) {
    JNIEnv * env = nullptr;
    if (vm == nullptr ||
        vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK ||
        env == nullptr) {
        return JNI_ERR;
    }

    // Resolve every Kotlin external declaration at library-load time. This prevents
    // a partially compatible ELF from being accepted and failing after model download.
    jclass native_class = env->FindClass("io/translatetube/local/translate/HyMtNative");
    if (native_class == nullptr) {
        return JNI_ERR;
    }

    const JNINativeMethod methods[] = {
        {
            const_cast<char *>("nativeOpen"),
            const_cast<char *>("(Ljava/lang/String;II)Ljava/lang/String;"),
            reinterpret_cast<void *>(Java_io_translatetube_local_translate_HyMtNative_nativeOpen),
        },
        {
            const_cast<char *>("nativeTranslate"),
            const_cast<char *>("(Ljava/lang/String;I)Ljava/lang/String;"),
            reinterpret_cast<void *>(Java_io_translatetube_local_translate_HyMtNative_nativeTranslate),
        },
        {
            const_cast<char *>("nativeClose"),
            const_cast<char *>("()V"),
            reinterpret_cast<void *>(Java_io_translatetube_local_translate_HyMtNative_nativeClose),
        },
        {
            const_cast<char *>("nativeCancel"),
            const_cast<char *>("()V"),
            reinterpret_cast<void *>(Java_io_translatetube_local_translate_HyMtNative_nativeCancel),
        },
        {
            const_cast<char *>("nativeResetCancel"),
            const_cast<char *>("()V"),
            reinterpret_cast<void *>(Java_io_translatetube_local_translate_HyMtNative_nativeResetCancel),
        },
    };

    const jint registration = env->RegisterNatives(
        native_class,
        methods,
        static_cast<jint>(sizeof(methods) / sizeof(methods[0]))
    );
    env->DeleteLocalRef(native_class);
    if (registration != JNI_OK) {
        return JNI_ERR;
    }
    return JNI_VERSION_1_6;
}
