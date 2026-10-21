package io.translatetube.local.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 配音混流处理器：注入 ExoPlayer 音频渲染管线（PlayerManager 自定义 RenderersFactory
 * 的 buildAudioSink），把 [DubbingTrackBus] 里同时刻的配音 PCM 与原声逐样本相加。
 *
 * 数值范围：总线里的配音是归一化浮点（-1..1），原声是 16 位整数（±32768），
 * 相加前必须把配音乘以 [PCM16_SCALE]，否则配音幅度只有 1/32768，完全听不见。
 * 声道：配音总线是单声道，同一帧的配音样本加到该帧的每个声道上。
 * 原声增益：混流后配音与原声在同一路输出里，播放器 volume 会连配音一起缩放，
 * 因此「静音/切断原声」只能作用在这里的 [originalGain]，播放器音量保持 1。
 * 仅处理 ENCODING_PCM_16BIT，其他编码时通过 onConfigure 声明不活跃，安全降级为纯原声。
 */
class DubbingAudioProcessor(private val bus: DubbingTrackBus) : BaseAudioProcessor() {

    /** 原声增益 0..1：0 = 完全切断原声（只留配音），1 = 原声原样。可从任意线程设置。 */
    @Volatile
    var originalGain: Float = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        bus.configure(inputAudioFormat.sampleRate)
        // 输出格式=输入格式：处理器保持活跃（返回 NOT_SET 会让 ExoPlayer 跳过整个处理器）
        return inputAudioFormat
    }

    override fun onFlush() {
        bus.setStreamStartUs(-1L)
        // flush 后流头重新校准，已消费帧数必须同步清零
        consumedFramesTotal = 0L
    }

    override fun onReset() {
        bus.reset()
        consumedFramesTotal = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        val frameBytes = 2 * channels
        val frames = remaining / frameBytes
        val headUs = bus.streamStart()
        val calibrated = headUs >= 0
        val voice = if (calibrated) {
            // 单声道配音：每帧一个样本，与声道数无关
            val windowStartUs = headUs + consumedFramesTotal * 1_000_000L / rate
            bus.readMixWindow(windowStartUs, frames)
        } else {
            NO_VOICE
        }
        val output = replaceOutputBuffer(remaining)
        var mixedFrames = 0
        while (inputBuffer.remaining() >= frameBytes) {
            val voiceSample = if (mixedFrames < voice.size) voice[mixedFrames] else 0f
            val voiceActive = kotlin.math.abs(voiceSample) > 1e-4f
            duckGain = approach(duckGain, if (voiceActive) DUCK_LEVEL else 1f, DUCK_SMOOTH)
            appliedOriginalGain = approach(appliedOriginalGain, originalGain.coerceIn(0f, 1f), GAIN_SMOOTH)
            val originalScale = duckGain * appliedOriginalGain
            val voiceScaled = voiceSample * PCM16_SCALE
            for (ch in 0 until channels) {
                val sample = inputBuffer.short
                output.putShort(clamp16(sample * originalScale + voiceScaled))
            }
            mixedFrames++
        }
        if (calibrated) consumedFramesTotal += mixedFrames
        output.flip()
    }

    private fun approach(current: Float, target: Float, smooth: Float): Float =
        current + (target - current) * smooth

    private fun clamp16(value: Float): Short =
        value.coerceIn(-32768f, 32767f).toInt().toShort()

    private var consumedFramesTotal = 0L
    private var duckGain = 1f
    private var appliedOriginalGain = 1f

    private companion object {
        const val DUCK_LEVEL = 0.15f
        const val DUCK_SMOOTH = 0.06f
        const val GAIN_SMOOTH = 0.003f
        const val PCM16_SCALE = 32767f
        val NO_VOICE = FloatArray(0)
    }
}
