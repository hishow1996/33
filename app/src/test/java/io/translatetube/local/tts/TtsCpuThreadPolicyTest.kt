package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsCpuThreadPolicyTest {
    @Test
    fun usesOneThreadOnSingleOrDualCoreDevices() {
        assertEquals(1, TtsCpuThreadPolicy.recommendedThreads(1))
        assertEquals(1, TtsCpuThreadPolicy.recommendedThreads(2))
    }

    @Test
    fun usesTwoThreadsOnMidRangeCoreCounts() {
        assertEquals(2, TtsCpuThreadPolicy.recommendedThreads(3))
        assertEquals(2, TtsCpuThreadPolicy.recommendedThreads(4))
        assertEquals(2, TtsCpuThreadPolicy.recommendedThreads(5))
    }

    @Test
    fun capsLocalNeuralInferenceAtThreeThreadsOnLargerPhones() {
        assertEquals(3, TtsCpuThreadPolicy.recommendedThreads(6))
        assertEquals(3, TtsCpuThreadPolicy.recommendedThreads(8))
        assertEquals(3, TtsCpuThreadPolicy.recommendedThreads(12))
    }
}
