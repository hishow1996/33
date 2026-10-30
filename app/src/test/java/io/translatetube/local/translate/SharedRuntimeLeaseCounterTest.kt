package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedRuntimeLeaseCounterTest {
    @Test
    fun closesOnlyAfterTheLastOwnerReleases() {
        val counter = SharedRuntimeLeaseCounter()
        var closeCount = 0

        counter.acquire()
        counter.acquire()

        assertFalse(counter.closeIfUnused { closeCount++ })
        assertFalse(counter.release())
        assertFalse(counter.closeIfUnused { closeCount++ })
        assertTrue(counter.release())
        assertTrue(counter.closeIfUnused { closeCount++ })

        assertEquals(1, closeCount)
        assertEquals(0, counter.activeOwners())
    }

    @Test
    fun releaseWithoutAnOwnerCannotUnderflowOrTriggerClose() {
        val counter = SharedRuntimeLeaseCounter()

        assertFalse(counter.release())
        assertFalse(counter.closeIfUnused { error("must not be called") })
        assertEquals(0, counter.activeOwners())
    }

    @Test
    fun aNewOwnerPreventsAQueuedCloseFromUnloadingTheRuntime() {
        val counter = SharedRuntimeLeaseCounter()
        var closeCount = 0

        counter.acquire()
        assertTrue(counter.release())

        // Simulate a new translation task acquiring before the deferred close coroutine runs.
        counter.acquire()
        assertFalse(counter.closeIfUnused { closeCount++ })
        assertEquals(0, closeCount)
        assertEquals(1, counter.activeOwners())
    }
}
