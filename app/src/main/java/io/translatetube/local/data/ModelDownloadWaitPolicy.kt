package io.translatetube.local.data

/** Pure decision used by the blocking pause gate to avoid waiting after resume or cancellation. */
internal object ModelDownloadWaitPolicy {
    fun shouldWait(jobIsActive: Boolean, resumeSignalCompleted: Boolean): Boolean =
        jobIsActive && !resumeSignalCompleted
}
