#include <jni.h>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "ctranslate2/translator.h"
#include "sentencepiece_processor.h"

namespace {
struct Engine {
    std::unique_ptr<ctranslate2::Translator> translator;
    sentencepiece::SentencePieceProcessor source_pieces;
    sentencepiece::SentencePieceProcessor target_pieces;
};
void throwJava(JNIEnv* env, const char* message) {
    if (env->ExceptionCheck()) return;
    jclass error = env->FindClass("java/lang/IllegalStateException");
    if (error != nullptr) env->ThrowNew(error, message);
}
std::string javaString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
std::vector<std::string> readUtf8(JNIEnv* env, jobjectArray input) {
    const jsize count = env->GetArrayLength(input);
    std::vector<std::string> result;
    result.reserve(static_cast<size_t>(count));
    for (jsize i = 0; i < count; ++i) {
        auto bytes = static_cast<jbyteArray>(env->GetObjectArrayElement(input, i));
        if (bytes == nullptr) { result.emplace_back(); continue; }
        const jsize size = env->GetArrayLength(bytes);
        std::string text(static_cast<size_t>(size), '\0');
        if (size > 0) env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(&text[0]));
        env->DeleteLocalRef(bytes);
        result.push_back(std::move(text));
    }
    return result;
}
jobjectArray toUtf8(JNIEnv* env, const std::vector<std::string>& texts) {
    jclass byteArrayClass = env->FindClass("[B");
    if (byteArrayClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(texts.size()), byteArrayClass, nullptr);
    env->DeleteLocalRef(byteArrayClass);
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < texts.size(); ++i) {
        const auto& text = texts[i];
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
        if (bytes == nullptr) return nullptr;
        if (!text.empty()) env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
        env->SetObjectArrayElement(result, static_cast<jsize>(i), bytes);
        env->DeleteLocalRef(bytes);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}
Engine* engine(jlong handle) {
    if (handle == 0) throw std::runtime_error("OPUS-MT native model handle is empty");
    return reinterpret_cast<Engine*>(handle);
}
}  // namespace

extern "C" {
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    ctranslate2::set_log_level(ctranslate2::LogLevel::Off);
    return JNI_VERSION_1_6;
}
JNIEXPORT jlong JNICALL
Java_io_translatetube_local_translate_OpusMtCt2Native_nativeLoad(
        JNIEnv* env, jobject, jstring modelDirectory, jstring sourceSentencePiecePath,
        jstring targetSentencePiecePath, jint threads) {
    try {
        const std::string directory = javaString(env, modelDirectory);
        const std::string sourcePath = javaString(env, sourceSentencePiecePath);
        const std::string targetPath = javaString(env, targetSentencePiecePath);
        if (directory.empty() || sourcePath.empty() || targetPath.empty()) throw std::runtime_error("OPUS-MT model paths must not be empty");
        auto value = std::make_unique<Engine>();
        const auto sourceStatus = value->source_pieces.Load(sourcePath);
        if (!sourceStatus.ok()) throw std::runtime_error("Cannot load source SentencePiece model: " + sourceStatus.ToString());
        const auto targetStatus = value->target_pieces.Load(targetPath);
        if (!targetStatus.ok()) throw std::runtime_error("Cannot load target SentencePiece model: " + targetStatus.ToString());
        ctranslate2::ReplicaPoolConfig config;
        config.num_threads_per_replica = static_cast<size_t>(std::max(1, static_cast<int>(threads)));
        value->translator = std::make_unique<ctranslate2::Translator>(
            directory, ctranslate2::Device::CPU, ctranslate2::ComputeType::INT8, std::vector<int>{0}, false, config);
        return reinterpret_cast<jlong>(value.release());
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) { throwJava(env, "Unknown error loading OPUS-MT CTranslate2 model"); }
    return 0;
}
JNIEXPORT void JNICALL
Java_io_translatetube_local_translate_OpusMtCt2Native_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
JNIEXPORT jobjectArray JNICALL
Java_io_translatetube_local_translate_OpusMtCt2Native_nativeTranslate(JNIEnv* env, jobject, jlong handle, jobjectArray texts) {
    try {
        Engine* loaded = engine(handle);
        const std::vector<std::string> inputTexts = readUtf8(env, texts);
        std::vector<std::vector<std::string>> tokenized;
        tokenized.reserve(inputTexts.size());
        for (const auto& text : inputTexts) {
            std::vector<std::string> pieces;
            const auto status = loaded->source_pieces.Encode(text, &pieces);
            if (!status.ok()) throw std::runtime_error("Source SentencePiece encoding failed: " + status.ToString());
            tokenized.push_back(std::move(pieces));
        }
        ctranslate2::TranslationOptions options;
        options.beam_size = 1;
        options.num_hypotheses = 1;
        options.max_input_length = 512;
        options.max_decoding_length = 256;
        const auto results = loaded->translator->translate_batch(tokenized, options, 8, ctranslate2::BatchType::Examples);
        if (results.size() != inputTexts.size()) throw std::runtime_error("CTranslate2 returned an unexpected number of translations");
        std::vector<std::string> output;
        output.reserve(results.size());
        for (const auto& result : results) {
            if (result.hypotheses.empty()) { output.emplace_back(); continue; }
            std::string text;
            const auto status = loaded->target_pieces.Decode(result.hypotheses.front(), &text);
            if (!status.ok()) throw std::runtime_error("Target SentencePiece decoding failed: " + status.ToString());
            output.push_back(std::move(text));
        }
        return toUtf8(env, output);
    } catch (const std::exception& error) {
        throwJava(env, error.what());
    } catch (...) { throwJava(env, "Unknown error running OPUS-MT CTranslate2 inference"); }
    return nullptr;
}
}  // extern "C"
