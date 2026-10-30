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
        assertEquals(8L*mib,DevicePerformancePolicy.dubbingPcmCacheBytes(s()))
    }
    @Test fun highEndDeviceGetsMoreInferenceCapacity(){
        val d=s(ram=12L*gib,heap=512)
        assertEquals(DevicePerformanceTier.HIGH,DevicePerformancePolicy.tier(d))
        assertEquals(3,DevicePerformancePolicy.recommendedLocalInferenceThreads(d))
        assertEquals(4,DevicePerformancePolicy.recommendedAsrThreads(d,"sensevoice",false,false))
        assertEquals(3,DevicePerformancePolicy.recommendedTtsThreads(d))
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
