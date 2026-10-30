package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalTranslationRuntimePolicyTest {
    private val mb = 1024L * 1024L

    @Test
    fun usesOneThreadOnSmallCpuDevices() {
        assertEquals(1, LocalTranslationRuntimePolicy.inferenceThreads(2, 3_000L * mb, false))
        assertEquals(1, LocalTranslationRuntimePolicy.inferenceThreads(3, 3_000L * mb, false))
    }

    @Test
    fun reducesThreadsWhenMemoryIsLowOrAndroidSignalsPressure() {
        assertEquals(1, LocalTranslationRuntimePolicy.inferenceThreads(8, 900L * mb, false))
        assertEquals(1, LocalTranslationRuntimePolicy.inferenceThreads(8, 2_000L * mb, true))
    }

    @Test
    fun allowsTwoThreadsWhenCpuAndMemoryHeadroomAreAvailable() {
        assertEquals(2, LocalTranslationRuntimePolicy.inferenceThreads(4, 1_024L * mb, false))
        assertEquals(2, LocalTranslationRuntimePolicy.inferenceThreads(8, 3_000L * mb, false))
    }

    @Test
    fun invalidRuntimeInformationFallsBackToOneThread() {
        assertEquals(1, LocalTranslationRuntimePolicy.inferenceThreads(0, -1L, false))
    }
}
