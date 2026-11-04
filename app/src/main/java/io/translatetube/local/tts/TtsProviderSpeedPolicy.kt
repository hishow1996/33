package io.translatetube.local.tts

import java.util.Locale

/**
 * Normalizes provider-specific speech-rate behavior while keeping the shared dubbing rate contract.
 */
internal object TtsProviderSpeedPolicy {
    fun normalize(speed: Float): Float = TtsSpeechRatePolicy.normalize(speed)

    /** Google Translate TTS is kept at or below its normal rate; faster rates use pitch-preserving PCM fitting. */
    fun googleNativeSpeed(speed: Float): Float =
        normalize(speed).coerceAtMost(1.0f)

    /** Ratio passed to WSOLA after Google's own native-rate adjustment. */
    fun googlePostStretchRatio(speed: Float): Float {
        val requested = normalize(speed)
        return (googleNativeSpeed(requested) / requested).coerceIn(0.01f, 1.0f)
    }

    /** Online Youdao/Baidu endpoints do not accept the app's rate slider, so fit their PCM locally. */
    fun webPostStretchRatio(speed: Float): Float = 1.0f / normalize(speed)

    fun geminiPaceInstruction(speed: Float): String {
        val requested = normalize(speed)
        val rate = String.format(Locale.US, "%.2f", requested)
        val pace = when {
            requested < 0.75f -> "明显慢于正常语速、从容清晰地"
            requested < 0.95f -> "略慢于正常语速、清晰自然地"
            requested <= 1.10f -> "以自然正常语速"
            requested <= 1.35f -> "略快于正常语速、保持清晰地"
            requested <= 1.75f -> "以较快语速、保持清晰地"
            else -> "以尽可能快但每个字仍清晰的语速"
        }
        return "${pace}朗读，目标速度约为正常语速的 ${rate} 倍"
    }
}
