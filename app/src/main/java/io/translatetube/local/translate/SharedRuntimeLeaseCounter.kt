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

    @Synchronized
    fun acquire() {
        owners++
    }

    /** Returns true only for the transition from one owner to zero owners. */
    @Synchronized
    fun release(): Boolean {
        if (owners == 0) return false
        owners--
        return owners == 0
    }

    /**
     * Runs the close action atomically only when no task owns this runtime.
     * The action must be short and must not call back into this counter.
     */
    @Synchronized
    fun closeIfUnused(close: () -> Unit): Boolean {
        if (owners != 0) return false
        close()
        return true
    }

    @Synchronized
    fun activeOwners(): Int = owners
}
