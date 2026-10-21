package io.translatetube.local.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer

/**
 * 配音混流处理器：注入 ExoPlayer 音频渲染管线（PlayerManager 自定义 RenderersFactory
 * 的 buildAudioSink），把 [DubbingTrackBus] 里同时刻的配音 PCM 与原声逐样本相加。
 *
 * 配上逐样本 ducking：配音活跃时原声压到 [DUCK_LEVEL]（句间自然回升），
 * 5ms 时间常数的平滑过渡替代整行硬切。
 *
 * 时钟：queueInput 的流内样本计数即媒体时间外推（flush 后由 bus.setStreamStartUs
 * 校准基点），与视频共享同一个播放时钟——seek/暂停/倍速天然同步。
 *
 * 仅处理 ENCODING_PCM_16BIT（Android 9 默认 sink 输出；其他编码时该处理器
 * 通过 onConfigure 声明透传不参与混流，安全降级为纯原声）。
 */
class DubbingAudioProcessor(private val bus: DubbingTrackBus) : BaseAudioProcessor() {

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        bus.configure(inputAudioFormat.sampleRate)
        // 输出与输入同格式：NOT_SET 语义为 BASE（透传），由父类处理缓冲交换
        return AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush() {
        bus.setStreamStartUs(-1L)
    }

    override fun onReset() {
        bus.reset()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount
        // 本窗媒体时间：流头 + 已消费样本数（含声道）
        val headUs = bus.streamStart()
        if (headUs < 0) {
            // 未校准（极早期窗口）：纯透传，等下一 tick 校准后混流
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
            return
        }
        val frames = remaining / (2 * channels)
        val windowStartUs = headUs + consumedFramesTotal * 1_000_000L / rate
        val voice = bus.readMixWindow(windowStartUs, frames * channels)
        val output = replaceOutputBuffer(remaining)
        var mixedFrames = 0
        while (inputBuffer.remaining() >= 2 * channels) {
            val voiceActive = hasVoice(voice, mixedFrames, channels)
            duckGain = approach(duckGain, if (voiceActive) DUCK_LEVEL else 1f)
            for (ch in 0 until channels) {
                val sample = inputBuffer.short
                var value = sample * duckGain
                val vIdx = mixedFrames * channels + ch
                if (vIdx < voice.size) value += voice[vIdx]
                output.putShort(clamp16(value))
            }
            mixedFrames++
        }
        consumedFramesTotal += mixedFrames
        output.flip()
    }

    private fun hasVoice(voice: FloatArray, frame: Int, channels: Int): Boolean {
        for (ch in 0 until channels) {
            val idx = frame * channels + ch
            if (idx < voice.size && kotlin.math.abs(voice[idx]) > 1e-4f) return true
        }
        return false
    }

    private fun approach(current: Float, target: Float): Float =
        current + (target - current) * DUCK_SMOOTH

    private fun clamp16(value: Float): Short =
        value.coerceIn(-32768f, 32767f).toInt().toShort()

    private var consumedFramesTotal = 0L
    private var duckGain = 1f

    private companion object {
        const val DUCK_LEVEL = 0.15f
        // 每帧趋近系数：sink 帧率的倒数 × 5ms 时间常数（帧级平滑足够）
        const val DUCK_SMOOTH = 0.06f
    }
}
