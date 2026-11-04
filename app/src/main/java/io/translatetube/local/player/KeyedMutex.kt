package io.translatetube.local.player

import kotlinx.coroutines.sync.Mutex

/**
 * Per-key coroutine mutexes that are removed when the final owner/waiter leaves.
 *
 * Reserve a reference before trying to acquire a key lock: otherwise a newly arriving caller
 * could create a second mutex while an older waiter still refers to the first one.
 */
internal class KeyedMutex<K : Any> {
    private class Entry {
        val mutex = Mutex()
        var users: Int = 0
    }

    private val registryLock = Any()
    private val entries = HashMap<K, Entry>()

    suspend fun <T> withLock(key: K, action: suspend () -> T): T {
        val entry = synchronized(registryLock) {
            (entries[key] ?: Entry().also { entries[key] = it }).also { it.users++ }
        }
        var acquired = false
        try {
            entry.mutex.lock()
            acquired = true
            return action()
        } finally {
            try {
                if (acquired) entry.mutex.unlock()
            } finally {
                synchronized(registryLock) {
                    entry.users--
                    if (entry.users == 0 && entries[key] === entry) {
                        entries.remove(key)
                    }
                }
            }
        }
    }

    internal val entryCount: Int
        get() = synchronized(registryLock) { entries.size }
}
