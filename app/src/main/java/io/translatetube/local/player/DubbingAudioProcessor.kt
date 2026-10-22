package io.translatetube.local.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Mixes normalized dubbing PCM with decoded source audio.
 * The sink wrapper supplies the real presentationTimeUs for each input buffer.
 */
class DubbingAudioProcessor(private val bus: DubbingTrackBus) : BaseAudioProcessor() {

    /** 0 = explicit source mute, 1 = source audio allowed with smooth speech ducking. */
    @Volatile
    var originalGain: Float = 1f

    @Volatile private var requestedBufferTimeUs = INVALID_TIME_US
    @Volatile private var requestedBufferSerial = 0L
    private var activeBufferSerial = Long.MIN_VALUE
    private var activeBufferTimeUs = INVALID_TIME_US
    private var framesConsumedFromBuffer = 0L
    private var consumedFramesTotal = 0L
    private var duckGain = 1f
    private var appliedOriginalGain = 1f
    private var voiceScratch = FloatArray(0)

    fun setInputBufferPresentationTimeUs(presentationTimeUs: Long) {
        requestedBufferTimeUs = if (presentationTimeUs >= 0L) presentationTimeUs else INVALID_TIME_US
        requestedBufferSerial += 1L
    }

    fun clearInputBufferTimestamp() {
        requestedBufferTimeUs = INVALID_TIME_US
        requestedBufferSerial += 1L
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) return AudioProcessor.AudioFormat.NOT_SET
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        bus.configure(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun onFlush() {
        bus.setStreamStartUs(-1L)
        clearInputBufferTimestamp()
        activeBufferSerial = Long.MIN_VALUE
        activeBufferTimeUs = INVALID_TIME_US
        framesConsumedFromBuffer = 0L
        consumedFramesTotal = 0L
        duckGain = 1f
        appliedOriginalGain = originalGain.coerceIn(0f, 1f)
    }

    override fun onReset() {
        bus.reset()
        clearInputBufferTimestamp()
        activeBufferSerial = Long.MIN_VALUE
        activeBufferTimeUs = INVALID_TIME_US
        framesConsumedFromBuffer = 0L
        consumedFramesTotal = 0L
        duckGain = 1f
        appliedOriginalGain = originalGain.coerceIn(0f, 1f)
        voiceScratch = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val encoding = inputAudioFormat.encoding
        val sampleRate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameBytes = bytesPerSample * channels
        val frames = remaining / frameBytes
        if (frames <= 0) return

        val serial = requestedBufferSerial
        if (serial != activeBufferSerial) {
            activeBufferSerial = serial
            activeBufferTimeUs = requestedBufferTimeUs
            framesConsumedFromBuffer = 0L
        }

        val fallbackHeadUs = bus.streamStart()
        val windowStartUs = when {
            activeBufferTimeUs >= 0L ->
                activeBufferTimeUs + framesConsumedFromBuffer * 1_000_000L / sampleRate
            fallbackHeadUs >= 0L ->
                fallbackHeadUs + consumedFramesTotal * 1_000_000L / sampleRate
            else -> INVALID_TIME_US
        }
        if (voiceScratch.size < frames) voiceScratch = FloatArray(frames)
        if (windowStartUs >= 0L) {
            bus.mixInto(windowStartUs, frames, voiceScratch)
        } else {
            java.util.Arrays.fill(voiceScratch, 0, frames, 0f)
        }

        val duckAttack = smoothingFactor(sampleRate, DUCK_ATTACK_MS)
        val duckRelease = smoothingFactor(sampleRate, DUCK_RELEASE_MS)
        val sourceAttack = smoothingFactor(sampleRate, SOURCE_ATTACK_MS)
        val sourceRelease = smoothingFactor(sampleRate, SOURCE_RELEASE_MS)
        val output = replaceOutputBuffer(remaining)
        val requestedOriginalGain = originalGain.coerceIn(0f, 1f)

        var frame = 0
        while (frame < frames) {
            val voiceSample = voiceScratch[frame]
            val voiceTarget = if (abs(voiceSample) > VOICE_ACTIVITY_THRESHOLD) DUCK_LEVEL else 1f
            // Envelope follower: a zero crossing cannot instantly restore the source audio.
            duckGain += (voiceTarget - duckGain) *
                if (voiceTarget < duckGain) duckAttack else duckRelease
            appliedOriginalGain += (requestedOriginalGain - appliedOriginalGain) *
                if (requestedOriginalGain < appliedOriginalGain) sourceAttack else sourceRelease

            var channel = 0
            while (channel < channels) {
                val sourceSample = if (encoding == C.ENCODING_PCM_FLOAT) {
                    inputBuffer.float.let { if (it.isFinite()) it else 0f }
                } else {
                    inputBuffer.short.toFloat() / PCM16_DENOMINATOR
                }
                val mixed = sourceSample * (duckGain * appliedOriginalGain) + voiceSample
                val limited = if (abs(voiceSample) > 1e-6f || abs(mixed) > 1f) softLimit(mixed) else mixed
                if (encoding == C.ENCODING_PCM_FLOAT) {
                    output.putFloat(limited)
                } else {
                    output.putShort((limited * PCM16_SCALE).toInt().coerceIn(-32768, 32767).toShort())
                }
                channel++
            }
            frame++
        }

        framesConsumedFromBuffer += frame.toLong()
        consumedFramesTotal += frame.toLong()
        output.flip()
    }

    private fun smoothingFactor(sampleRate: Int, timeMs: Double): Float {
        val denominator = sampleRate.toDouble() * timeMs / 1_000.0
        return if (denominator <= 0.0) 1f else (1.0 - exp(-1.0 / denominator)).toFloat()
    }

    private fun softLimit(value: Float): Float {
        val safe = value.coerceIn(-8f, 8f)
        val magnitude = abs(safe)
        if (magnitude <= LIMIT_THRESHOLD) return safe
        val over = (magnitude - LIMIT_THRESHOLD) / LIMIT_RANGE
        val limited = LIMIT_THRESHOLD + LIMIT_RANGE * tanh(over.toDouble()).toFloat()
        return if (safe < 0f) -limited else limited
    }

    private companion object {
        const val DUCK_LEVEL = 0.15f
        const val DUCK_ATTACK_MS = 10.0
        const val DUCK_RELEASE_MS = 250.0
        const val SOURCE_ATTACK_MS = 8.0
        const val SOURCE_RELEASE_MS = 25.0
        const val VOICE_ACTIVITY_THRESHOLD = 1e-4f
        const val PCM16_DENOMINATOR = 32768f
        const val PCM16_SCALE = 32767f
        const val LIMIT_THRESHOLD = 0.92f
        const val LIMIT_RANGE = 0.08f
        const val INVALID_TIME_US = -1L
    }
}
