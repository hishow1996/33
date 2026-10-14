package io.translatetube.local.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedMutexTest {
    @Test
    fun removesEntriesAfterSuccessfulAndFailedActions() = runBlocking {
        val locks = KeyedMutex<String>()

        assertEquals("ok", locks.withLock("video-a") { "ok" })
        assertEquals(0, locks.entryCount)

        runCatching {
            locks.withLock("video-a") { throw IllegalStateException("expected") }
        }
        assertEquals(0, locks.entryCount)
    }

    @Test
    fun keepsSharedEntryUntilQueuedWaitersFinish() = runBlocking {
        val locks = KeyedMutex<String>()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            locks.withLock("same-video") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            locks.withLock("same-video") { }
        }

        assertEquals(1, locks.entryCount)
        assertTrue(first.isActive)
        assertTrue(second.isActive)
        releaseFirst.complete(Unit)
        joinAll(first, second)
        assertEquals(0, locks.entryCount)
    }

    @Test
    fun cancellationWhileWaitingReleasesItsRegistryReference() = runBlocking {
        val locks = KeyedMutex<String>()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val owner = launch { locks.withLock("same-video") { entered.complete(Unit); release.await() } }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { locks.withLock("same-video") { } }
        assertEquals(1, locks.entryCount)

        waiter.cancelAndJoin()
        assertEquals(1, locks.entryCount)
        release.complete(Unit)
        owner.join()
        assertEquals(0, locks.entryCount)
    }
}
