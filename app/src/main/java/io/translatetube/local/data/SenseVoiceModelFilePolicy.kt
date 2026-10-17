package io.translatetube.local.data

/** Basic integrity checks for the sherpa-onnx SenseVoice INT8 model files. */
internal object SenseVoiceModelFilePolicy {
    const val MIN_MODEL_BYTES = 200L * 1024L * 1024L
    const val MIN_TOKENS_BYTES = 256L * 1024L

    fun isCoreComplete(files: Map<String, Long>): Boolean =
        (files["model.int8.onnx"] ?: 0L) >= MIN_MODEL_BYTES &&
            (files["tokens.txt"] ?: 0L) >= MIN_TOKENS_BYTES
}
