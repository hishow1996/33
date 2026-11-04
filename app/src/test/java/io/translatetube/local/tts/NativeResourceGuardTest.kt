package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NativeResourceGuardTest {
    @Test
    fun repeatedCloseReleasesOnlyOnce() {
        val releases = AtomicInteger()
        val guard = NativeResourceGuard("repeat-close") { releases.incrementAndGet() }

        guard.close()
        guard.close()
        guard.close()

        assertEquals(1, releases.get())
    }

    @Test
    fun useAfterCloseThrowsIllegalStateException() {
        val guard = NativeResourceGuard("closed-use") {}

        guard.close()

        try {
            guard.use { "must not run" }
            throw AssertionError("use after close should fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("closed"))
        }
    }

    @Test
    fun closeDuringUseDefersReleaseUntilUseFinishesAndResetsGateCount() {
        val baseline = NativeTtsRuntimeGate.pendingDeferredReleases()
        val enteredUse = CountDownLatch(1)
        val finishUse = CountDownLatch(1)
        val useBodyFinished = AtomicBoolean(false)
        val releases = AtomicInteger()
        val workerFailure = AtomicReference<Throwable?>(null)
        val guard = NativeResourceGuard("deferred-release") {
            assertTrue("release ran before use body finished", useBodyFinished.get())
            releases.incrementAndGet()
        }

        val worker = Thread({
            try {
                guard.use {
                    enteredUse.countDown()
                    check(finishUse.await(5, TimeUnit.SECONDS)) { "test timed out waiting to finish use" }
                    useBodyFinished.set(true)
                }
            } catch (failure: Throwable) {
                workerFailure.set(failure)
            }
        }, "native-guard-test")
        worker.start()

        assertTrue("use did not start", enteredUse.await(5, TimeUnit.SECONDS))
        guard.close()

        assertEquals(baseline + 1, NativeTtsRuntimeGate.pendingDeferredReleases())
        assertEquals(0, releases.get())

        finishUse.countDown()
        worker.join(5_000L)

        assertFalse("use thread did not finish", worker.isAlive)
        assertEquals(null, workerFailure.get())
        assertEquals(1, releases.get())
        assertEquals(baseline, NativeTtsRuntimeGate.pendingDeferredReleases())
        guard.close()
        assertEquals(1, releases.get())
    }

    @Test
    fun linkageErrorFromUseIsWrappedInIllegalStateException() {
        val guard = NativeResourceGuard("linkage-error") {}
        var observed: IllegalStateException? = null

        try {
            guard.use<Unit> { throw LinkageError("native symbol missing") }
        } catch (failure: IllegalStateException) {
            observed = failure
        }

        assertNotNull(observed)
        assertTrue(observed!!.cause is LinkageError)
    }
}
