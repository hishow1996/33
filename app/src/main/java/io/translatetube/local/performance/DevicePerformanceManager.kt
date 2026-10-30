package io.translatetube.local.performance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

internal data class DevicePerformanceSnapshot(
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val memoryThresholdBytes: Long,
    val memoryClassMb: Int,
    val isLowRamDevice: Boolean,
    val isLowMemory: Boolean,
    val processorCount: Int,
    val powerSaveMode: Boolean,
    /** Android thermal status: 0 none, 1 light, 2 moderate, 3+ severe; -1 when unavailable. */
    val thermalStatus: Int = -1,
)
internal enum class DevicePerformanceTier { LOW, BALANCED, HIGH }

internal object DevicePerformancePolicy {
    private const val MIB = 1024L * 1024L
    private const val GIB = 1024L * MIB

    fun tier(s: DevicePerformanceSnapshot): DevicePerformanceTier {
        val cores=s.processorCount.coerceAtLeast(1)
        val smallHeap=s.memoryClassMb in 1..192
        if (s.isLowRamDevice || smallHeap || (s.totalMemoryBytes>0L && s.totalMemoryBytes<3L*GIB) || cores<=2)
            return DevicePerformanceTier.LOW
        return if (s.totalMemoryBytes>=8L*GIB && s.memoryClassMb>=384 && cores>=8)
            DevicePerformanceTier.HIGH else DevicePerformanceTier.BALANCED
    }

    fun hasSevereMemoryPressure(s: DevicePerformanceSnapshot): Boolean {
        if (s.isLowMemory) return true
        val available=s.availableMemoryBytes
        if (available<=0L) return false
        return available < maxOf(384L*MIB,s.memoryThresholdBytes.coerceAtLeast(0L))
    }

    fun hasModerateMemoryPressure(s: DevicePerformanceSnapshot): Boolean {
        val available=s.availableMemoryBytes
        if (available<=0L) return false
        val proportional=if(s.totalMemoryBytes>0L) s.totalMemoryBytes/12L else 0L
        return available < maxOf(768L*MIB,proportional)
    }

    private fun reductions(s: DevicePerformanceSnapshot): Int {
        var n=0
        if(s.powerSaveMode)n++
        if(s.thermalStatus>=2)n++
        if(hasSevereMemoryPressure(s)) n++ else if(hasModerateMemoryPressure(s)) n++
        return n
    }

    fun recommendedLocalInferenceThreads(s: DevicePerformanceSnapshot): Int {
        val cores=s.processorCount.coerceAtLeast(1)
        val base=when(tier(s)){DevicePerformanceTier.LOW->1;DevicePerformanceTier.BALANCED->2;DevicePerformanceTier.HIGH->3}
        return (base-reductions(s)).coerceIn(1,cores)
    }

    fun recommendedAsrThreads(s: DevicePerformanceSnapshot, modelId: String, isPlaying: Boolean, dubbingEnabled: Boolean): Int {
        val cores=s.processorCount.coerceAtLeast(1)
        val base=when(tier(s)){
            DevicePerformanceTier.LOW->1
            DevicePerformanceTier.BALANCED->2
            DevicePerformanceTier.HIGH->if(modelId=="sensevoice") 4 else 3
        }.coerceAtMost(cores)
        return (base-(if(isPlaying&&dubbingEnabled)1 else 0)-reductions(s)).coerceAtLeast(1)
    }

    fun recommendedTtsThreads(s: DevicePerformanceSnapshot): Int = recommendedLocalInferenceThreads(s)

    fun translationBatchMaxExamples(s: DevicePerformanceSnapshot): Int {
        val base=when(tier(s)){DevicePerformanceTier.LOW->2;DevicePerformanceTier.BALANCED->4;DevicePerformanceTier.HIGH->8}
        return if(reductions(s)>0) minOf(base,2) else base
    }
    fun translationBatchMaxCharacters(s: DevicePerformanceSnapshot): Int = translationBatchMaxExamples(s)*512

    fun dubbingPcmCacheBytes(s: DevicePerformanceSnapshot): Long {
        val base=when(tier(s)){DevicePerformanceTier.LOW->4L*MIB;DevicePerformanceTier.BALANCED->16L*MIB;DevicePerformanceTier.HIGH->32L*MIB}
        val heapCap=when{
            s.memoryClassMb in 1..256->8L*MIB
            s.memoryClassMb in 257..384->16L*MIB
            s.memoryClassMb>384->32L*MIB
            else->8L*MIB
        }
        val capped=minOf(base,heapCap)
        return when{
            hasSevereMemoryPressure(s)->minOf(capped,4L*MIB)
            hasModerateMemoryPressure(s)->maxOf(4L*MIB,capped/2L)
            else->capped
        }
    }
}

internal object DevicePerformanceManager {
    private const val SAMPLE_CACHE_MS=1_500L
    @Volatile private var cachedSnapshot: DevicePerformanceSnapshot?=null
    @Volatile private var cachedAtElapsedMs=0L

    fun snapshot(context: Context): DevicePerformanceSnapshot {
        val now=SystemClock.elapsedRealtime()
        cachedSnapshot?.let{if(now-cachedAtElapsedMs in 0L until SAMPLE_CACHE_MS)return it}
        synchronized(this){
            cachedSnapshot?.let{if(now-cachedAtElapsedMs in 0L until SAMPLE_CACHE_MS)return it}
            val app=context.applicationContext
            val am=app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memory=ActivityManager.MemoryInfo()
            if(am!=null)runCatching{am.getMemoryInfo(memory)}
            val pm=app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val thermal=if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q)
                runCatching{pm?.currentThermalStatus ?: -1}.getOrDefault(-1) else -1
            val result=DevicePerformanceSnapshot(
                totalMemoryBytes=memory.totalMem.coerceAtLeast(0L),
                availableMemoryBytes=memory.availMem.coerceAtLeast(0L),
                memoryThresholdBytes=memory.threshold.coerceAtLeast(0L),
                memoryClassMb=am?.memoryClass ?: 0,
                isLowRamDevice=am?.isLowRamDevice==true,
                isLowMemory=memory.lowMemory,
                processorCount=Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
                powerSaveMode=runCatching{pm?.isPowerSaveMode==true}.getOrDefault(false),
                thermalStatus=thermal,
            )
            cachedAtElapsedMs=now
            cachedSnapshot=result
            return result
        }
    }
}
