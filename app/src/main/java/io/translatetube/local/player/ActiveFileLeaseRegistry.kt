package io.translatetube.local.player

/**
 * Reference-counted leases for cached files that must not be pruned while being read.
 * Lease acquisition/release and conditional deletion share one monitor, so a new reader cannot
 * acquire a path in the gap between a prune check and deleting the underlying file.
 */
internal class ActiveFileLeaseRegistry {
    private val leaseCounts = HashMap<String, Int>()

    @Synchronized
    fun acquire(path: String?) {
        if (path.isNullOrBlank()) return
        leaseCounts[path] = (leaseCounts[path] ?: 0) + 1
    }

    @Synchronized
    fun release(path: String?) {
        if (path.isNullOrBlank()) return
        val count = leaseCounts[path] ?: return
        if (count <= 1) leaseCounts.remove(path) else leaseCounts[path] = count - 1
    }

    @Synchronized
    fun contains(path: String): Boolean = path in leaseCounts

    @Synchronized
    internal fun leaseCount(path: String): Int = leaseCounts[path] ?: 0

    /**
     * Run deletion while no active lease exists. New readers cannot acquire this path until
     * the deletion has completed, eliminating the check-then-delete race.
     */
    @Synchronized
    fun deleteIfUnleased(path: String, delete: () -> Boolean): Boolean =
        deleteIfLeaseCountAtMostLocked(path, allowedLeaseCount = 0, delete = delete)

    /**
     * A caller that already owns a lease may retire its failed file only when no *other* reader
     * owns it. The caller's own lease remains active until its surrounding finally block runs.
     */
    @Synchronized
    fun deleteIfLeaseCountAtMost(
        path: String,
        allowedLeaseCount: Int,
        delete: () -> Boolean,
    ): Boolean = deleteIfLeaseCountAtMostLocked(path, allowedLeaseCount, delete)

    private fun deleteIfLeaseCountAtMostLocked(
        path: String,
        allowedLeaseCount: Int,
        delete: () -> Boolean,
    ): Boolean {
        if (path.isBlank() || allowedLeaseCount < 0) return false
        if ((leaseCounts[path] ?: 0) > allowedLeaseCount) return false
        return delete()
    }
}
