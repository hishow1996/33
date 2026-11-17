package io.translatetube.local.data

/** File-size checks for the Whisper files that sherpa-onnx actually opens. */
internal object WhisperModelFilePolicy {
    const val MIN_ONNX_BYTES = 1L * 1024L * 1024L
    const val MIN_ENCODER_BYTES = 96L * 1024L * 1024L
    const val MIN_DECODER_BYTES = 224L * 1024L * 1024L
    const val MIN_TOKENS_BYTES = 512L
    const val MIN_VAD_BYTES = 512L * 1024L

    fun isCoreComplete(files: Map<String, Long>): Boolean =
        (files["small-encoder.int8.onnx"] ?: 0L) >= MIN_ENCODER_BYTES &&
            (files["small-decoder.int8.onnx"] ?: 0L) >= MIN_DECODER_BYTES &&
            (files["small-tokens.txt"] ?: 0L) >= MIN_TOKENS_BYTES

    fun isComplete(files: Map<String, Long>, vadBytes: Long): Boolean =
        isCoreComplete(files) && vadBytes >= MIN_VAD_BYTES
}
