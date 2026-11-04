package io.translatetube.local.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPcmSanitizerTest {
    @Test
    fun sanitizesInvalidAndOutOfRangeSamplesWithoutAllocatingAnotherArray() {
        val samples = floatArrayOf(
            -2f, -1f, -0.25f, 0f, 0.75f, 1f, 2f,
            Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        )

        val sanitized = TtsPcmSanitizer.sanitizeInPlace(samples)

        assertSame(samples, sanitized)
        assertArrayEquals(
            floatArrayOf(-1f, -1f, -0.25f, 0f, 0.75f, 1f, 1f, 0f, 0f, 0f),
            sanitized,
            0f,
        )
    }

    @Test
    fun emptyArraysAreReturnedUnchanged() {
        val samples = FloatArray(0)
        assertSame(samples, TtsPcmSanitizer.sanitizeInPlace(samples))
        assertArrayEquals(FloatArray(0), samples, 0f)
    }

    @Test
    fun cancellationInterruptsLongSanitizationPass() {
        val samples = FloatArray(20_000) { Float.NaN }
        var checks = 0
        var cancelled = false
        try {
            TtsPcmSanitizer.sanitizeInPlace(samples) {
                checks++
                if (checks >= 3) throw IllegalStateException("cancel test")
            }
        } catch (_: IllegalStateException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertTrue("cancellation is checked between sample blocks", checks >= 3)
    }
}
