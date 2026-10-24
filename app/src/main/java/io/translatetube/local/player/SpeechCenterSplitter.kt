package io.translatetube.local.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin

/**
 * 把立体声原声拆成两部分，用于"配音时保留视频自带的背景音乐"：
 *
 * - [center]：人声频段（约 80Hz～9kHz）里居中的那部分。视频里的解说/对白几乎都录在正中，
 *   配音时要把它压低或静音；
 * - [bedLeft]/[bedRight]：其余全部——音乐、环境声、两侧的立体声内容。配音时继续播放。
 *
 * 两部分严格互补：bed + center == 原信号（逐样本），所以原声和配音都开着、没有人声时听起来
 * 和原来完全一样，不会因为拆分引入音色变化。
 *
 * 只有真正的立体声才能拆：双声道一模一样的"假立体声"（很多网页/本地视频是这样）里人声和音乐
 * 混在同一个声道，无法分开，[usable] 会降到 0，调用方回退到原来的整体压低/静音。
 *
 * 不依赖 Android，可以在 JVM 里单测。
 */
internal class SpeechCenterSplitter(private val sampleRate: Int) {

    /** 去掉居中人声之后的左声道（音乐/环境声底）。 */
    var bedLeft = 0f
        private set

    /** 去掉居中人声之后的右声道。 */
    var bedRight = 0f
        private set

    /** 居中的人声频段分量（左右声道相同）：原信号 = bed + center。 */
    var center = 0f
        private set

    /**
     * 当前片段能不能拆（0..1）：侧向（左右差异）能量足够，说明里面有立体声音乐/环境声可以保留。
     * 上升快、下降很慢——解说占满的时段侧向能量会暂时偏低，不能让音乐底跟着忽有忽无。
     */
    var usable = 0f
        private set

    private val highPassLeft = Biquad.highPass(sampleRate, HIGH_PASS_HZ)
    private val highPassRight = Biquad.highPass(sampleRate, HIGH_PASS_HZ)
    private val lowPassLeft = Biquad.lowPass(sampleRate, lowPassHz(sampleRate))
    private val lowPassRight = Biquad.lowPass(sampleRate, lowPassHz(sampleRate))

    private val energyAlpha = (1.0 - exp(-1.0 / (ENERGY_TIME_S * sampleRate))).toFloat()
    private var midEnergy = 0f
    private var sideEnergy = 0f

    fun process(left: Float, right: Float) {
        val bandLeft = lowPassLeft.process(highPassLeft.process(left))
        val bandRight = lowPassRight.process(highPassRight.process(right))
        val mid = 0.5f * (bandLeft + bandRight)
        val side = 0.5f * (bandLeft - bandRight)
        midEnergy += (mid * mid - midEnergy) * energyAlpha
        sideEnergy += (side * side - sideEnergy) * energyAlpha

        val removed = CENTER_REMOVAL * mid
        center = removed
        bedLeft = left - removed
        bedRight = right - removed
    }

    /** 每处理完一个缓冲区调用一次：根据这段时间的侧向/居中能量比更新 [usable]。 */
    fun updateUsability(frames: Int) {
        if (frames <= 0 || sampleRate <= 0) return
        // 几乎全静音时没有可靠的判断依据，保持上一次的结果。
        if (midEnergy < SILENCE_ENERGY && sideEnergy < SILENCE_ENERGY) return
        val ratioDb = 10.0 * log10((sideEnergy + TINY) / (midEnergy + TINY))
        val target = ((ratioDb - USABLE_FLOOR_DB) / USABLE_RANGE_DB).coerceIn(0.0, 1.0).toFloat()
        val seconds = frames.toDouble() / sampleRate
        val time = if (target > usable) RISE_TIME_S else FALL_TIME_S
        usable += (target - usable) * (1.0 - exp(-seconds / time)).toFloat()
    }

    fun reset() {
        highPassLeft.reset()
        highPassRight.reset()
        lowPassLeft.reset()
        lowPassRight.reset()
        midEnergy = 0f
        sideEnergy = 0f
        usable = 0f
        bedLeft = 0f
        bedRight = 0f
        center = 0f
    }

    /** RBJ 二阶 Butterworth 滤波器，状态用 Double 保证低截止频率下的数值稳定。 */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: Float): Float {
            val x = input.toDouble()
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            val out = y.toFloat()
            // 异常输入（NaN/Inf）不能把滤波器状态永久污染。
            if (!out.isFinite()) {
                reset()
                return 0f
            }
            return out
        }

        fun reset() {
            z1 = 0.0
            z2 = 0.0
        }

        companion object {
            private const val Q = 0.70710678

            fun lowPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val w = 2.0 * PI * cutoffHz / sampleRate
                val cosW = cos(w)
                val alpha = sin(w) / (2.0 * Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 - cosW) / 2.0 / a0,
                    b1 = (1.0 - cosW) / a0,
                    b2 = (1.0 - cosW) / 2.0 / a0,
                    a1 = -2.0 * cosW / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }

            fun highPass(sampleRate: Int, cutoffHz: Double): Biquad {
                val w = 2.0 * PI * cutoffHz / sampleRate
                val cosW = cos(w)
                val alpha = sin(w) / (2.0 * Q)
                val a0 = 1.0 + alpha
                return Biquad(
                    b0 = (1.0 + cosW) / 2.0 / a0,
                    b1 = -(1.0 + cosW) / a0,
                    b2 = (1.0 + cosW) / 2.0 / a0,
                    a1 = -2.0 * cosW / a0,
                    a2 = (1.0 - alpha) / a0,
                )
            }
        }
    }

    private companion object {
        /** 人声频段下限：低于它的低频（鼓点、贝斯）原样保留。 */
        const val HIGH_PASS_HZ = 80.0

        /** 人声频段上限：覆盖齿音，避免解说的"嘶嘶"声漏进音乐底。 */
        const val LOW_PASS_MAX_HZ = 9_000.0

        /** 居中分量扣除比例：留一点余量，换取音乐底更自然、少相位怪音。 */
        const val CENTER_REMOVAL = 0.9f

        /** 居中/侧向能量的平滑时间。 */
        const val ENERGY_TIME_S = 0.3

        const val SILENCE_ENERGY = 1e-9f
        const val TINY = 1e-12f

        /** 侧向能量比居中低多少 dB 以内算"有立体声内容"：低于 FLOOR 不可用，高于 FLOOR+RANGE 完全可用。 */
        const val USABLE_FLOOR_DB = -32.0
        const val USABLE_RANGE_DB = 12.0

        const val RISE_TIME_S = 0.2
        const val FALL_TIME_S = 6.0

        fun lowPassHz(sampleRate: Int): Double =
            minOf(LOW_PASS_MAX_HZ, sampleRate * 0.45)
    }
}
