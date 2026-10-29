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
class DubbingAudioProcessor(
    private val bus: DubbingTrackBus,
    private val onDiagnostic: ((isWarning: Boolean, message: String, details: Map<String, String>) -> Unit)? = null,
) : BaseAudioProcessor() {

    /** Source channel gain: 0 = mute, 1 = full volume. */
    @Volatile
    var originalGain: Float = 1f

    /** 0 = silent Chinese dubbing; 1 = full output, independent of the original track. */
    @Volatile
    var dubbingGain: Float = 1f

    @Volatile private var requestedBufferTimeUs = INVALID_TIME_US
    @Volatile private var requestedBufferSerial = 0L
    private var activeBufferSerial = Long.MIN_VALUE
    private var activeBufferTimeUs = INVALID_TIME_US
    private var framesConsumedFromBuffer = 0L
    private var consumedFramesTotal = 0L
    private var appliedOriginalGain = 1f
    private var voiceScratch = FloatArray(0)
    private var firstInputLogged = false
    private var timestampFallbackLogged = false

    /** Diagnostics only: the renderer stream offset the sink wrapper already removed from PTS. */
    @Volatile
    var sinkStreamOffsetUs: Long = 0L

    /** [presentationTimeUs] must already be on the player's media timeline (offset removed). */
    fun setInputBufferPresentationTimeUs(presentationTimeUs: Long) {
        requestedBufferTimeUs = if (presentationTimeUs >= 0L) presentationTimeUs else INVALID_TIME_US
        requestedBufferSerial += 1L
    }

    fun clearInputBufferTimestamp() {
        requestedBufferTimeUs = INVALID_TIME_US
        requestedBufferSerial += 1L
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val supportedEncoding = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT ||
            inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!supportedEncoding || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            runCatching {
                onDiagnostic?.invoke(
                    true,
                    "配音混流处理器无法处理当前音频格式",
                    mapOf(
                        "PCM 编码值" to inputAudioFormat.encoding.toString(),
                        "采样率 Hz" to inputAudioFormat.sampleRate.toString(),
                        "声道数" to inputAudioFormat.channelCount.toString(),
                        "支持的编码" to "PCM_16BIT、PCM_FLOAT",
                    ),
                )
            }
            return AudioProcessor.AudioFormat.NOT_SET
        }
        bus.configure(inputAudioFormat.sampleRate)
        runCatching {
            onDiagnostic?.invoke(
                false,
                "配音混流处理器已配置",
                mapOf(
                    "PCM 编码值" to inputAudioFormat.encoding.toString(),
                    "采样率 Hz" to inputAudioFormat.sampleRate.toString(),
                    "声道数" to inputAudioFormat.channelCount.toString(),
                    "处理器输出格式" to "沿用输入 PCM 格式",
                ),
            )
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        bus.setStreamStartUs(-1L)
        clearInputBufferTimestamp()
        activeBufferSerial = Long.MIN_VALUE
        activeBufferTimeUs = INVALID_TIME_US
        framesConsumedFromBuffer = 0L
        consumedFramesTotal = 0L
        appliedOriginalGain = DubbingGainPolicy.sanitizeGain(originalGain)
        firstInputLogged = false
        timestampFallbackLogged = false
    }

    override fun onReset() {
        bus.reset()
        clearInputBufferTimestamp()
        activeBufferSerial = Long.MIN_VALUE
        activeBufferTimeUs = INVALID_TIME_US
        framesConsumedFromBuffer = 0L
        consumedFramesTotal = 0L
        appliedOriginalGain = DubbingGainPolicy.sanitizeGain(originalGain)
        voiceScratch = FloatArray(0)
        firstInputLogged = false
        timestampFallbackLogged = false
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
        val timestampWindowStartUs = if (activeBufferTimeUs >= 0L) {
            activeBufferTimeUs + framesConsumedFromBuffer * 1_000_000L / sampleRate
        } else {
            INVALID_TIME_US
        }
        val fallbackWindowStartUs = if (fallbackHeadUs >= 0L) {
            fallbackHeadUs + consumedFramesTotal * 1_000_000L / sampleRate
        } else {
            INVALID_TIME_US
        }

        // Media3 normally supplies timestamps in the same timeline as the subtitle cues. On a
        // few stream/decoder combinations, however, the sink PTS can be offset from the player
        // timeline. Only fall back when the difference is clearly abnormal AND the fallback
        // window actually intersects queued speech; ordinary gaps between subtitles remain silent.
        val timestampSkewUs = if (timestampWindowStartUs >= 0L && fallbackWindowStartUs >= 0L) {
            kotlin.math.abs(timestampWindowStartUs - fallbackWindowStartUs)
        } else {
            0L
        }
        val useCalibratedFallback =
            timestampSkewUs > MAX_TIMESTAMP_SKEW_US &&
                !bus.hasAudioInWindow(timestampWindowStartUs, frames) &&
                bus.hasAudioInWindow(fallbackWindowStartUs, frames)
        val windowStartUs = when {
            useCalibratedFallback -> fallbackWindowStartUs
            timestampWindowStartUs >= 0L -> timestampWindowStartUs
            fallbackWindowStartUs >= 0L -> fallbackWindowStartUs
            else -> INVALID_TIME_US
        }
        if (useCalibratedFallback && !timestampFallbackLogged) {
            timestampFallbackLogged = true
            runCatching {
                onDiagnostic?.invoke(
                    true,
                    "配音混流时间戳与校准媒体时钟不一致，已使用校准时钟",
                    mapOf(
                        "音频缓冲区 PTS us" to timestampWindowStartUs.toString(),
                        "校准媒体时间 us" to fallbackWindowStartUs.toString(),
                        "时间戳偏差 ms" to (timestampSkewUs / 1_000L).toString(),
                        "PCM 采样率 Hz" to sampleRate.toString(),
                        "本窗口是否有待混入配音" to "有",
                    ),
                )
            }
        }
        if (!firstInputLogged) {
            firstInputLogged = true
            runCatching {
                onDiagnostic?.invoke(
                    false,
                    "配音混流处理器已收到播放器 PCM 音频",
                    mapOf(
                        "PCM 编码值" to encoding.toString(),
                        "采样率 Hz" to sampleRate.toString(),
                        "声道数" to channels.toString(),
                        "缓冲区字节数" to remaining.toString(),
                        "音频缓冲区媒体时间 us" to timestampWindowStartUs.toString(),
                        "播放器流偏移 us" to sinkStreamOffsetUs.toString(),
                        "校准媒体时间 us" to fallbackWindowStartUs.toString(),
                        "当前缓冲区是否有待混入配音" to
                            (windowStartUs >= 0L && bus.hasAudioInWindow(windowStartUs, frames)).toString(),
                    ),
                )
            }
        }
        if (voiceScratch.size < frames) voiceScratch = FloatArray(frames)
        if (windowStartUs >= 0L) {
            bus.mixInto(windowStartUs, frames, voiceScratch)
        } else {
            java.util.Arrays.fill(voiceScratch, 0, frames, 0f)
        }

        val sourceAttack = smoothingFactor(sampleRate, SOURCE_ATTACK_MS)
        val sourceRelease = smoothingFactor(sampleRate, SOURCE_RELEASE_MS)
        val output = replaceOutputBuffer(remaining)
        val requestedOriginalGain = DubbingGainPolicy.sanitizeGain(originalGain)
        val requestedDubbingGain = DubbingGainPolicy.sanitizeGain(dubbingGain)
        val isFloat = encoding == C.ENCODING_PCM_FLOAT

        // Do not run source separation or auto-ducking: the source track should retain its
        // original sound and be affected only by its own gain slider.
        var frame = 0
        while (frame < frames) {
            val voiceSample = DubbingGainPolicy.sanitizePcmSample(voiceScratch[frame]) * requestedDubbingGain
            appliedOriginalGain += (requestedOriginalGain - appliedOriginalGain) *
                if (requestedOriginalGain < appliedOriginalGain) sourceAttack else sourceRelease
            val sourceGain = appliedOriginalGain

            var channel = 0
            while (channel < channels) {
                val sourceSample = readSample(inputBuffer, isFloat)
                writeSample(output, isFloat, sourceSample * sourceGain + voiceSample, voiceSample)
                channel++
            }
            frame++
        }

        framesConsumedFromBuffer += frame.toLong()
        consumedFramesTotal += frame.toLong()
        output.flip()
    }

    private fun readSample(inputBuffer: ByteBuffer, isFloat: Boolean): Float =
        if (isFloat) {
            inputBuffer.float.let { if (it.isFinite()) it else 0f }
        } else {
            inputBuffer.short.toFloat() / PCM16_DENOMINATOR
        }

    private fun writeSample(output: ByteBuffer, isFloat: Boolean, mixed: Float, voiceSample: Float) {
        val safeMixed = DubbingGainPolicy.sanitizePcmSample(mixed)
        val safeVoice = DubbingGainPolicy.sanitizePcmSample(voiceSample)
        val limited = if (abs(safeVoice) > 1e-6f || abs(safeMixed) > 1f) {
            softLimit(safeMixed)
        } else {
            safeMixed
        }
        if (isFloat) {
            output.putFloat(limited)
        } else {
            output.putShort((limited * PCM16_SCALE).toInt().coerceIn(-32768, 32767).toShort())
        }
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
        const val SOURCE_ATTACK_MS = 8.0
        const val SOURCE_RELEASE_MS = 25.0
        const val PCM16_DENOMINATOR = 32768f
        const val PCM16_SCALE = 32767f
        const val LIMIT_THRESHOLD = 0.92f
        const val LIMIT_RANGE = 0.08f
        const val MAX_TIMESTAMP_SKEW_US = 750_000L
        const val INVALID_TIME_US = -1L
    }
}
