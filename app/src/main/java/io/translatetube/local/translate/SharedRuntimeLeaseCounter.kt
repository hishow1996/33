package io.translatetube.local.translate

/**
 * Reference counter for process-wide model runtimes shared by multiple playback tasks.
 *
 * Closing a TranslationEngine must not unload a model while another engine instance still owns
 * a task using that same process-wide runtime. The close callback is executed under the same
 * monitor as acquire(), so a new owner cannot race between the zero-owner check and native close.
 */
internal class SharedRuntimeLeaseCounter {
    private var owners: Int = 0
    private var closePending: Boolean = false

    @Synchronized
    fun acquire() {
        owners++
        // A new task arriving before the deferred close runs keeps the shared model loaded.
        closePending = false
    }

    /** Returns true only for the transition from one owner to zero owners. */
    @Synchronized
    fun release(): Boolean {
        if (owners == 0) return false
        owners--
        closePending = owners == 0
        return closePending
    }

    /**
     * Runs a queued last-owner close atomically only if no new task acquired the runtime.
     * An idle counter with no prior owner release must not trigger an unrelated native close.
     * The action must be short and must not call back into this counter.
     */
    @Synchronized
    fun closeIfUnused(close: () -> Unit): Boolean {
        if (owners != 0 || !closePending) return false
        close()
        closePending = false
        return true
    }

    @Synchronized
    fun activeOwners(): Int = owners
}
