package io.translatetube.local.player

/** Rejects delayed Home-journal writes after a newer request, including same-video retries. */
internal object PendingProcessingResumeWritePolicy {
    fun shouldWrite(
        activeSessionKey: String?,
        activeGeneration: Long,
        expectedSessionKey: String,
        expectedGeneration: Long,
    ): Boolean =
        expectedSessionKey.isNotBlank() &&
            expectedGeneration > 0L &&
            activeSessionKey == expectedSessionKey &&
            activeGeneration == expectedGeneration
}
