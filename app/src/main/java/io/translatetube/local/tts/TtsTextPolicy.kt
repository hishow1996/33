package io.translatetube.local.tts

/** Bounds one subtitle cue before a TTS provider can allocate an unbounded audio buffer. */
internal object TtsTextPolicy {
    const val MAX_CODE_POINTS = 1_000

    fun validate(text: String) {
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints > MAX_CODE_POINTS) {
            throw TtsInputLimitException(
                "单条字幕超过 ${MAX_CODE_POINTS} 个字符。为避免配音合成占用过多内存，请先拆分这条字幕。"
            )
        }
    }
}

internal class TtsInputLimitException(message: String) : IllegalArgumentException(message)
