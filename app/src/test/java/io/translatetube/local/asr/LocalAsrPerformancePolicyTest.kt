package io.translatetube.local.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalAsrPerformancePolicyTest {
    @Test
    fun capsThreadsOnLowCoreDevices() {
        assertEquals(1, LocalAsrPerformancePolicy.recommendedThreadCount("sensevoice", 1, false, false))
        assertEquals(1, LocalAsrPerformancePolicy.recommendedThreadCount("whisper", 2, true, true))
    }

    @Test
    fun senseVoiceGetsThirdWorkerWhenEightCoreDeviceHasHeadroom() {
        assertEquals(3, LocalAsrPerformancePolicy.recommendedThreadCount("sensevoice", 8, false, false))
    }

    @Test
    fun keepsPlaybackAndDubbingResponsive() {
        assertEquals(2, LocalAsrPerformancePolicy.recommendedThreadCount("sensevoice", 8, true, true))
    }

    @Test
    fun whisperKeepsConservativeTwoThreadBudget() {
        assertEquals(2, LocalAsrPerformancePolicy.recommendedThreadCount("whisper", 8, false, false))
    }

    @Test
    fun midrangeThreadCountIsTwo() {
        assertEquals(2, LocalAsrPerformancePolicy.recommendedThreadCount("sensevoice", 4, false, false))
    }

    @Test
    fun neverRequestsZeroThreadsWhenRuntimeReportsNoCores() {
        assertEquals(1, LocalAsrPerformancePolicy.recommendedThreadCount("sensevoice", 0, false, false))
    }
}
