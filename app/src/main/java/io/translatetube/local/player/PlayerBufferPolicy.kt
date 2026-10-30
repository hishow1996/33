package io.translatetube.local.player

/** ExoPlayer buffer limits selected for constrained Android devices. */
internal data class PlayerBufferConfig(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val lowMemoryProfile: Boolean,
)

internal object PlayerBufferPolicy {
    private const val BYTES_PER_MIB = 1024L * 1024L
    private const val LOW_AVAILABLE_MEMORY_BYTES = 384L * BYTES_PER_MIB
    private const val LOW_MEMORY_CLASS_MB = 192

    private val normal = PlayerBufferConfig(
        minBufferMs = 30_000,
        maxBufferMs = 150_000,
        bufferForPlaybackMs = 1_500,
        bufferForPlaybackAfterRebufferMs = 3_000,
        lowMemoryProfile = false,
    )
    private val constrained = PlayerBufferConfig(
        minBufferMs = 15_000,
        maxBufferMs = 60_000,
        bufferForPlaybackMs = 1_500,
        bufferForPlaybackAfterRebufferMs = 3_000,
        lowMemoryProfile = true,
    )

    fun select(
        isLowRamDevice: Boolean,
        memoryClassMb: Int,
        availableMemoryBytes: Long,
    ): PlayerBufferConfig {
        val hasReportedMemoryPressure =
            availableMemoryBytes > 0L && availableMemoryBytes < LOW_AVAILABLE_MEMORY_BYTES
        val constrainedDevice = isLowRamDevice ||
            memoryClassMb in 1..LOW_MEMORY_CLASS_MB ||
            hasReportedMemoryPressure
        return if (constrainedDevice) constrained else normal
    }
}
