package io.translatetube.local.asr

/** Distinguishes real codec/sample progress from repeated empty buffers. */
internal object CodecProgressPolicy {
    fun inputMakesProgress(sampleBytes: Int, hasNextSample: Boolean): Boolean =
        sampleBytes > 0 || hasNextSample

    fun outputMakesProgress(outputBytes: Int, endOfStream: Boolean): Boolean =
        outputBytes > 0 || endOfStream
}
