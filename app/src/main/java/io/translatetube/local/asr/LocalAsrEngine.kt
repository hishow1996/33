package io.translatetube.local.asr

/**
 * Common contract for on-device transcription engines used by the video subtitle pipeline.
 * SenseVoice reports VAD-segment-level timing; Whisper may refine cues with token timestamps.
 */
interface LocalAsrEngine : AutoCloseable {
    val lastLang: String
    val lastEmotion: String
    val lastEvent: String

    fun clearDetectedLanguage()

    fun transcribeBySegments(
        pcmFile: AudioExtract.PcmFile,
        segs: List<Pair<Long, Long>>,
        checkActive: () -> Unit = {},
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Triple<Long, Long, String>>
}
