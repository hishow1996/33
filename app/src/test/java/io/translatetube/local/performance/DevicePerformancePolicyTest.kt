package io.translatetube.local.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DevicePerformancePolicyTest {
    private val mib=1024L*1024L
    private val gib=1024L*mib
    private fun s(
        ram:Long=6L*gib,avail:Long=2L*gib,heap:Int=256,lowRam:Boolean=false,
        lowMemory:Boolean=false,cores:Int=8,power:Boolean=false,thermal:Int=-1,threshold:Long=0L,
    )=DevicePerformanceSnapshot(ram,avail,threshold,heap,lowRam,lowMemory,cores,power,thermal)

    @Test fun sixGbDeviceIsBalancedAndNotForcedIntoLowRamTier(){
        assertEquals(DevicePerformanceTier.BALANCED,DevicePerformancePolicy.tier(s()))
        assertEquals(2,DevicePerformancePolicy.recommendedLocalInferenceThreads(s()))
        assertEquals(1,DevicePerformancePolicy.recommendedLocalInferenceThreads(s(), playbackActive = true))
        assertEquals(4,DevicePerformancePolicy.translationBatchMaxExamples(s()))
        assertEquals(2,DevicePerformancePolicy.translationBatchMaxExamples(s(), playbackActive = true))
        assertEquals(8L*mib,DevicePerformancePolicy.dubbingPcmCacheBytes(s()))
    }
    @Test fun highEndDeviceGetsMoreInferenceCapacity(){
        val d=s(ram=12L*gib,heap=512)
        assertEquals(DevicePerformanceTier.HIGH,DevicePerformancePolicy.tier(d))
        assertEquals(3,DevicePerformancePolicy.recommendedLocalInferenceThreads(d))
        assertEquals(2,DevicePerformancePolicy.recommendedLocalInferenceThreads(d, playbackActive = true))
        assertEquals(4,DevicePerformancePolicy.recommendedAsrThreads(d,"sensevoice",false,false))
        assertEquals(3,DevicePerformancePolicy.recommendedTtsThreads(d))
        assertEquals(2,DevicePerformancePolicy.recommendedTtsThreads(d, playbackActive = true))
        assertEquals(2,DevicePerformancePolicy.translationBatchMaxExamples(d, playbackActive = true))
    }
    @Test fun lowRamAndLowCoreDevicesStayConservative(){
        val d=s(ram=2L*gib,heap=192,cores=4)
        assertEquals(DevicePerformanceTier.LOW,DevicePerformancePolicy.tier(d))
        assertEquals(1,DevicePerformancePolicy.recommendedLocalInferenceThreads(d))
        assertEquals(1,DevicePerformancePolicy.recommendedAsrThreads(s(cores=2),"whisper",false,false))
    }
    @Test fun playbackAndDubbingReduceAsrParallelism(){
        val d=s(ram=12L*gib,heap=512)
        assertEquals(4,DevicePerformancePolicy.recommendedAsrThreads(d,"sensevoice",false,false))
        assertEquals(3,DevicePerformancePolicy.recommendedAsrThreads(d,"sensevoice",true,true))
    }
    @Test fun eightCoreSixGbPhoneGetsThreeAsrThreads(){
        // Honor 10 class: 8 cores, ~5.6 GiB reported RAM.
        val ram=5L*gib+600L*mib
        val d=s(ram=ram,cores=8)
        assertEquals(DevicePerformanceTier.BALANCED,DevicePerformancePolicy.tier(d))
        assertEquals(3,DevicePerformancePolicy.recommendedAsrThreads(d,"whisper",false,true))
        assertEquals(3,DevicePerformancePolicy.recommendedAsrThreads(d,"sensevoice",false,false))
        // Keep playback + dubbing responsive.
        assertEquals(2,DevicePerformancePolicy.recommendedAsrThreads(d,"whisper",true,true))
        // Four-core 6 GB phones stay at two.
        assertEquals(2,DevicePerformancePolicy.recommendedAsrThreads(s(ram=ram,cores=4),"whisper",false,false))
    }
    @Test fun speakerDiarizationUsesMoreThanOneThreadOnCapablePhones(){
        // Honor 10 class: 8 cores, ~5.6 GiB reported RAM.
        val ram=5L*gib+600L*mib
        val honor=s(ram=ram,cores=8)
        assertEquals(3,DevicePerformancePolicy.recommendedSpeakerThreads(honor))
        // A playing video keeps one lane for rendering/dubbing.
        assertEquals(2,DevicePerformancePolicy.recommendedSpeakerThreads(honor,playbackActive=true))
        // Four-core 6 GB phones and mid-range 8-core phones with less RAM stay at two.
        assertEquals(2,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=ram,cores=4)))
        assertEquals(2,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=4L*gib,cores=8)))
        // High-end phones may use four; low-end phones stay single threaded.
        assertEquals(4,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=12L*gib,heap=512)))
        assertEquals(1,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=2L*gib,heap=192,cores=4)))
    }
    @Test fun speakerDiarizationBacksOffWhenHotSavingPowerOrOutOfMemory(){
        val ram=5L*gib+600L*mib
        assertEquals(2,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=ram,cores=8,power=true)))
        assertEquals(2,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=ram,cores=8,thermal=2)))
        assertEquals(2,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=ram,avail=300L*mib,cores=8)))
        // Moderate memory pressure is common on 6 GB phones and must not reduce the budget.
        assertEquals(3,DevicePerformancePolicy.recommendedSpeakerThreads(s(ram=ram,avail=600L*mib,cores=8)))
        // Never below one thread, even with every reduction at once.
        assertEquals(1,DevicePerformancePolicy.recommendedSpeakerThreads(
            s(ram=ram,avail=300L*mib,cores=8,power=true,thermal=3),playbackActive=true))
    }
    @Test fun moderateMemoryPressureNoLongerDropsAsrThreads(){
        val d=s(ram=5L*gib+600L*mib,avail=600L*mib,cores=8)
        assertTrue(DevicePerformancePolicy.hasModerateMemoryPressure(d))
        assertFalse(DevicePerformancePolicy.hasSevereMemoryPressure(d))
        assertEquals(3,DevicePerformancePolicy.recommendedAsrThreads(d,"whisper",false,false))
    }
    @Test fun severePressurePowerSaveAndHeatStillReduceAsrThreads(){
        val ram=5L*gib+600L*mib
        assertEquals(2,DevicePerformancePolicy.recommendedAsrThreads(s(ram=ram,avail=300L*mib,cores=8),"whisper",false,false))
        assertEquals(2,DevicePerformancePolicy.recommendedAsrThreads(s(ram=ram,cores=8,power=true),"whisper",false,false))
        assertEquals(2,DevicePerformancePolicy.recommendedAsrThreads(s(ram=ram,cores=8,thermal=2),"whisper",false,false))
    }
    @Test fun livePressurePowerSaveAndHeatReduceBudgets(){
        val d=s(ram=12L*gib,avail=256L*mib,heap=512,power=true,thermal=3,threshold=512L*mib)
        assertTrue(DevicePerformancePolicy.hasSevereMemoryPressure(d))
        assertEquals(1,DevicePerformancePolicy.recommendedLocalInferenceThreads(d))
        assertEquals(2,DevicePerformancePolicy.translationBatchMaxExamples(d))
        assertEquals(4L*mib,DevicePerformancePolicy.dubbingPcmCacheBytes(d))
    }
    @Test fun healthyHighEndDeviceCanUseLargerBatchesAndCache(){
        val d=s(ram=12L*gib,heap=512)
        assertFalse(DevicePerformancePolicy.hasModerateMemoryPressure(d))
        assertEquals(8,DevicePerformancePolicy.translationBatchMaxExamples(d))
        assertEquals(4096,DevicePerformancePolicy.translationBatchMaxCharacters(d))
        assertEquals(32L*mib,DevicePerformancePolicy.dubbingPcmCacheBytes(d))
    }
    @Test fun thermalAndPowerSaverReduceParallelism(){
        assertEquals(2,DevicePerformancePolicy.recommendedLocalInferenceThreads(s(ram=12L*gib,heap=512,thermal=2)))
        assertEquals(2,DevicePerformancePolicy.recommendedTtsThreads(s(ram=12L*gib,heap=512,power=true)))
    }
}
