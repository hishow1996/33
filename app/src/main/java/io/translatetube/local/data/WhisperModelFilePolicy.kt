package io.translatetube.local.data

/** File-size checks for the Whisper files that sherpa-onnx actually opens. */
internal object WhisperModelFilePolicy {
    const val MIN_ONNX_BYTES = 1L * 1024L * 1024L
    const val MIN_TOKENS_BYTES = 512L
    const val MIN_VAD_BYTES = 512L * 1024L

    fun isCoreComplete(files: Map<String, Long>): Boolean =
        (files["base-encoder.int8.onnx"] ?: 0L) >= MIN_ONNX_BYTES &&
            (files["base-decoder.int8.onnx"] ?: 0L) >= MIN_ONNX_BYTES &&
            (files["base-tokens.txt"] ?: 0L) >= MIN_TOKENS_BYTES

    fun isComplete(files: Map<String, Long>, vadBytes: Long): Boolean =
        isCoreComplete(files) && vadBytes >= MIN_VAD_BYTES
}
