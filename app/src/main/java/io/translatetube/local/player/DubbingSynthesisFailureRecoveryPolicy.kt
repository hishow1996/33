package io.translatetube.local.player

/** Limits background recovery after a transient per-cue synthesis exception. */
internal object DubbingSynthesisFailureRecoveryPolicy {
    const val MAX_BACKGROUND_RETRIES_PER_CUE = 1
    const val RETRY_DELAY_MS = 650L

    fun shouldScheduleRetry(
        retriesAlreadyScheduled: Int,
        deterministicFailure: Boolean,
        released: Boolean,
    ): Boolean =
        !released &&
            !deterministicFailure &&
            retriesAlreadyScheduled in 0 until MAX_BACKGROUND_RETRIES_PER_CUE
}
