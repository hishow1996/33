package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ParallelDecodePlanTest {
    @Test
    fun alignmentStepMatchesTheResamplingGrid() {
        assertEquals(441L, ParallelDecodePlan.alignmentStep(44_100))
        assertEquals(160L, ParallelDecodePlan.outputPerStep(44_100))
        assertEquals(3L, ParallelDecodePlan.alignmentStep(48_000))
        assertEquals(1L, ParallelDecodePlan.outputPerStep(48_000))
        assertEquals(2L, ParallelDecodePlan.alignmentStep(32_000))
    }

    @Test
    fun shortOrSingleCoreMediaStaysSequential() {
        assertEquals(1, ParallelDecodePlan.workerCount(60_000L, 8))
        assertEquals(1, ParallelDecodePlan.workerCount(600_000L, 1))
        assertEquals(2, ParallelDecodePlan.workerCount(100_000L, 8)) // 每段至少 40 秒
        assertEquals(ParallelDecodePlan.MAX_WORKERS, ParallelDecodePlan.workerCount(3_600_000L, 16))
        assertEquals(3, ParallelDecodePlan.workerCount(3_600_000L, 3))
    }

    @Test
    fun segmentsAreContiguousAlignedAndCoverTheWholeStream() {
        val rate = 44_100
        val segments = ParallelDecodePlan.plan(rate, 600_000L, 4)
        assertNotNull(segments)
        segments!!
        assertEquals(4, segments.size)
        assertEquals(0L, segments[0].sourceStart)
        assertEquals(0L, segments[0].outputStart)
        assertTrue(segments[0].isFirst)
        assertTrue(segments.last().isLast)
        assertEquals(Long.MAX_VALUE, segments.last().sourceEnd)
        for (i in 0 until segments.size - 1) {
            val current = segments[i]
            val next = segments[i + 1]
            assertEquals(current.sourceEnd, next.sourceStart)
            assertEquals(0L, next.sourceStart % 441L)
            assertEquals(current.outputStart + current.outputCount, next.outputStart)
            assertFalse(current.isLast)
            assertTrue(current.outputCount > 0L)
        }
    }

    @Test
    fun unsuitableInputsAreNotPlanned() {
        assertNull(ParallelDecodePlan.plan(44_100, 600_000L, 1))
        assertNull(ParallelDecodePlan.plan(22_050, 600_000L, 4)) // 可能是 HE-AAC
        assertNull(ParallelDecodePlan.plan(44_100, 0L, 4))
    }

    @Test
    fun bufferSliceKeepsOnlyTheFramesInsideTheRange() {
        // 缓冲区 [1000, 2024)，保留 [1500, 1800)
        assertEquals(ParallelDecodePlan.Slice(500, 300), ParallelDecodePlan.sliceOfBuffer(1000, 1024, 1500, 1800))
        // 整个缓冲区都在保留范围里
        assertEquals(ParallelDecodePlan.Slice(0, 1024), ParallelDecodePlan.sliceOfBuffer(1000, 1024, 0, 5000))
        // 预滚：整个缓冲区在保留范围之前
        assertNull(ParallelDecodePlan.sliceOfBuffer(0, 1024, 2000, 3000))
        // 已经越过保留范围结尾
        assertNull(ParallelDecodePlan.sliceOfBuffer(4000, 1024, 2000, 3000))
        // 恰好从保留范围起点开始
        assertEquals(ParallelDecodePlan.Slice(0, 24), ParallelDecodePlan.sliceOfBuffer(2000, 1024, 0, 2024))
    }

    @Test
    fun frameAndTimeMappingRoundTrips() {
        val rate = 44_100
        val origin = 23_000L
        val frame = 441_000L * 5
        val us = ParallelDecodePlan.timeUsForFrame(frame, rate, origin)
        assertEquals(frame, ParallelDecodePlan.frameIndexAtUs(us, origin, rate))
        assertTrue(ParallelDecodePlan.isContiguous(1000, 1003))
        assertFalse(ParallelDecodePlan.isContiguous(1000, 1100))
    }

    private fun noise(seed: Long, size: Int, amplitude: Int = 3000): ShortArray {
        val random = java.util.Random(seed)
        return ShortArray(size) { (random.nextInt(2 * amplitude + 1) - amplitude).toShort() }
    }

    private val seamSize = ParallelDecodePlan.seamSamples(44_100)

    @Test
    fun seamSamplesCoverTheOverlapWindow() {
        assertEquals(66_150, ParallelDecodePlan.seamSamples(44_100))
        assertEquals(72_000, ParallelDecodePlan.seamSamples(48_000))
    }

    @Test
    fun identicalOrNearlyIdenticalSeamIsAligned() {
        val tail = noise(1, seamSize)
        assertEquals(ParallelDecodePlan.SeamVerdict.ALIGNED, ParallelDecodePlan.compareSeam(tail, tail.copyOf()).verdict)
        // 两个独立解码器可能差 1 个 LSB 的舍入
        val jitter = ShortArray(tail.size) { (tail[it] + (it % 3 - 1)).toShort() }
        assertEquals(ParallelDecodePlan.SeamVerdict.ALIGNED, ParallelDecodePlan.compareSeam(tail, jitter).verdict)
    }

    @Test
    fun anyShiftOfEvenOneSampleIsDetected() {
        val base = noise(2, seamSize + 100)
        val tail = base.copyOfRange(0, seamSize)
        for (shift in listOf(1, 2, 3, 17, 40, 64)) {
            // head[j] = base[j + shift]：后一段比前一段晚了 shift 个采样点
            val head = base.copyOfRange(shift, shift + seamSize)
            val check = ParallelDecodePlan.compareSeam(tail, head)
            assertEquals("shift=$shift", ParallelDecodePlan.SeamVerdict.MISALIGNED, check.verdict)
            assertEquals("shift=$shift", -shift, check.lag)
        }
    }

    @Test
    fun unrelatedAudioIsAMismatch() {
        val check = ParallelDecodePlan.compareSeam(noise(3, seamSize), noise(4, seamSize))
        assertEquals(ParallelDecodePlan.SeamVerdict.MISMATCH, check.verdict)
    }

    @Test
    fun silenceQuietNoiseAndPureTonesCannotProveAlignment() {
        // 完全静音
        assertEquals(
            ParallelDecodePlan.SeamVerdict.INCONCLUSIVE,
            ParallelDecodePlan.compareSeam(ShortArray(seamSize), ShortArray(seamSize)).verdict,
        )
        // 几乎静音的底噪（±1 LSB）
        val quiet = noise(5, seamSize, amplitude = 1)
        assertEquals(
            ParallelDecodePlan.SeamVerdict.INCONCLUSIVE,
            ParallelDecodePlan.compareSeam(quiet, quiet.copyOf()).verdict,
        )
        // 周期只有 50 个采样点的纯音：错位 50 个采样点也"吻合"，分辨不出
        val tone = ShortArray(seamSize) { (8000.0 * Math.sin(2.0 * Math.PI * it / 50.0)).toInt().toShort() }
        assertEquals(
            ParallelDecodePlan.SeamVerdict.INCONCLUSIVE,
            ParallelDecodePlan.compareSeam(tone, tone.copyOf()).verdict,
        )
    }

    @Test
    fun wrongSizedSeamsAreInconclusive() {
        assertEquals(
            ParallelDecodePlan.SeamVerdict.INCONCLUSIVE,
            ParallelDecodePlan.compareSeam(ShortArray(10), ShortArray(10)).verdict,
        )
        assertEquals(
            ParallelDecodePlan.SeamVerdict.INCONCLUSIVE,
            ParallelDecodePlan.compareSeam(noise(6, seamSize), noise(6, seamSize - 1)).verdict,
        )
    }

    @Test
    fun totalLengthMustAgreeWithTheDeclaredDuration() {
        assertTrue(ParallelDecodePlan.totalLooksRight(1_600_000L, 100_000L))
        assertTrue(ParallelDecodePlan.totalLooksRight(1_600_000L + 16_000L, 100_000L))
        assertFalse(ParallelDecodePlan.totalLooksRight(1_000_000L, 100_000L))
        assertFalse(ParallelDecodePlan.totalLooksRight(0L, 100_000L))
    }

    /**
     * 核心保证：每段独立重采样再拼起来，和整段一次重采样结果一致（最多差 1 个 LSB，
     * 来自 44.1 kHz 网格上的 double 舍入），而且长度完全相同。
     */
    @Test
    fun segmentedResamplingMatchesSequentialResampling() {
        for ((rate, workers) in listOf(44_100 to 2, 48_000 to 3, 32_000 to 2)) {
            val durationMs = 100_000L
            val estimate = ParallelDecodePlan.estimatedSourceSamples(rate, durationMs)
            val total = (estimate + 137L).toInt() // 真实长度和标称时长不会完全相等
            val signal = ShortArray(total) { i ->
                (8000.0 * Math.sin(i * 0.05) + ((i * 7919L) % 1000L) - 500L).toInt().toShort()
            }

            val sequential = Pcm16StreamingResampler(rate, 16_000).let { r ->
                r.process(signal) + r.finish()
            }

            val segments = ParallelDecodePlan.plan(rate, durationMs, workers)!!
            val stitched = ArrayList<Short>()
            for (segment in segments) {
                val resampler = Pcm16StreamingResampler(rate, 16_000)
                val from = segment.sourceStart.toInt()
                if (segment.isLast) {
                    val part = resampler.process(signal.copyOfRange(from, total)) + resampler.finish()
                    part.forEach { stitched.add(it) }
                } else {
                    val to = minOf(total.toLong(), segment.sourceEnd + ParallelDecodePlan.seamSamples(rate)).toInt()
                    val part = resampler.process(signal.copyOfRange(from, to))
                    assertTrue(part.size >= segment.outputCount)
                    for (i in 0 until segment.outputCount.toInt()) stitched.add(part[i])
                }
            }

            assertEquals("rate=$rate", sequential.size, stitched.size)
            var maxDiff = 0
            for (i in sequential.indices) maxDiff = maxOf(maxDiff, abs(sequential[i] - stitched[i]))
            assertTrue("rate=$rate maxDiff=$maxDiff", maxDiff <= 1)
        }
    }

    @Test
    fun toleranceGrowsForMillisecondTimestampContainers() {
        assertEquals(48L, ParallelDecodePlan.ptsTolerance(48_000))
        assertEquals(4L, ParallelDecodePlan.ptsTolerance(2_000))
    }

    @Test
    fun leadingDiscardIsMeasuredFromTheFirstTwoBuffers() {
        // Opus：pre-skip 312 个样本，第一个缓冲区只剩 648 个样本，第二个缓冲区时间戳在 960
        assertEquals(312L, ParallelDecodePlan.leadingDiscardFrames(960, 648, 48_000))
        // AAC：没有丢样本，第二个缓冲区紧接在第一个之后
        assertEquals(0L, ParallelDecodePlan.leadingDiscardFrames(1024, 1024, 44_100))
        // 毫秒时间戳的舍入误差不算丢样本
        assertEquals(0L, ParallelDecodePlan.leadingDiscardFrames(962, 960, 48_000))
        // 差得离谱（超过 500ms）或为负：放弃并行
        assertNull(ParallelDecodePlan.leadingDiscardFrames(50_000, 100, 48_000))
        assertNull(ParallelDecodePlan.leadingDiscardFrames(100, 5_000, 48_000))
    }
}
