package io.translatetube.local.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Reference-counted leases for cached files that must not be pruned while being read.
 * A set is insufficient: two concurrent readers of the same path need two independent leases.
 */
internal class ActiveFileLeaseRegistry {
    private val leaseCounts = ConcurrentHashMap<String, Int>()

    fun acquire(path: String?) {
        if (path.isNullOrBlank()) return
        leaseCounts.compute(path) { _, count -> (count ?: 0) + 1 }
    }

    fun release(path: String?) {
        if (path.isNullOrBlank()) return
        leaseCounts.computeIfPresent(path) { _, count ->
            if (count <= 1) null else count - 1
        }
    }

    fun contains(path: String): Boolean = path in leaseCounts

    internal fun leaseCount(path: String): Int = leaseCounts[path] ?: 0
}
