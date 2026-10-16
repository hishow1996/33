package io.translatetube.local.tts

/** Status is active as soon as a task is claimed, before its IO coroutine starts. */
internal object PiperTaskActivityPolicy {
    fun isActive(stateActive: Boolean, jobPendingOrRunning: Boolean, downloadCount: Int): Boolean =
        stateActive || jobPendingOrRunning || downloadCount > 0
}
