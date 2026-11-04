package io.translatetube.local.tts

import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes model initialization against native TTS resources that are still being released.
 * A timed wait is deliberate: callers must never wait forever for a broken native call to retire.
 */
internal object NativeTtsRuntimeGate {
    private val lock = ReentrantLock()
    private val deferredReleasesFinished: Condition = lock.newCondition()
    private var pendingReleases = 0

    fun markReleaseDeferred() {
        lock.lock()
        try {
            pendingReleases++
        } finally {
            lock.unlock()
        }
    }

    fun markDeferredReleaseFinished() {
        lock.lock()
        try {
            if (pendingReleases > 0) pendingReleases--
            deferredReleasesFinished.signalAll()
        } finally {
            lock.unlock()
        }
    }

    fun pendingDeferredReleases(): Int {
        lock.lock()
        try {
            return pendingReleases
        } finally {
            lock.unlock()
        }
    }

    fun awaitDeferredReleases(timeoutMs: Long = 15_000L): Boolean {
        var remainingNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        try {
            lock.lockInterruptibly()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }

        try {
            while (pendingReleases > 0) {
                if (remainingNanos <= 0L) return false
                remainingNanos = try {
                    deferredReleasesFinished.awaitNanos(remainingNanos)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        } finally {
            lock.unlock()
        }
    }
}

/**
 * Guards an OfflineTts native resource. Active uses are serialized and close never waits for
 * them: when closed during a use, the last active caller performs release after the use returns.
 */
internal class NativeResourceGuard(
    private val label: String,
    private val releaseAction: () -> Unit,
) {
    private val stateLock = Any()
    private val useLock = ReentrantLock()
    private var activeUses = 0
    private var closeRequested = false
    private var releaseCommitted = false
    private var deferredReleaseRegistered = false

    fun <T> use(block: () -> T): T {
        synchronized(stateLock) {
            check(!closeRequested && !releaseCommitted) {
                "Native TTS resource '$label' is already closed"
            }
            activeUses++
        }

        var useLockAcquired = false
        try {
            useLock.lock()
            useLockAcquired = true
            return try {
                block()
            } catch (failure: Throwable) {
                when (failure) {
                    is VirtualMachineError -> throw failure
                    is ThreadDeath -> throw failure
                    is Exception -> throw failure
                    else -> throw IllegalStateException(
                        "Native TTS use failed for '$label'",
                        failure,
                    )
                }
            }
        } finally {
            if (useLockAcquired) useLock.unlock()
            finishUse()
        }
    }

    fun close() {
        val releaseImmediately = synchronized(stateLock) {
            if (closeRequested) {
                false
            } else {
                closeRequested = true
                if (activeUses > 0) {
                    // Register under the same lock as activeUses so finishing the last use can
                    // never decrement the gate before this deferred release has been counted.
                    NativeTtsRuntimeGate.markReleaseDeferred()
                    deferredReleaseRegistered = true
                    false
                } else {
                    releaseCommitted = true
                    true
                }
            }
        }

        if (releaseImmediately) releaseSafely()
    }

    private fun finishUse() {
        val shouldRelease = synchronized(stateLock) {
            activeUses--
            if (activeUses == 0 && closeRequested && !releaseCommitted) {
                releaseCommitted = true
                true
            } else {
                false
            }
        }

        if (shouldRelease) {
            try {
                releaseSafely()
            } finally {
                if (deferredReleaseRegistered) {
                    NativeTtsRuntimeGate.markDeferredReleaseFinished()
                }
            }
        }
    }

    private fun releaseSafely() {
        try {
            releaseAction()
        } catch (failure: Throwable) {
            // release() is invoked from close/finally paths; never let a native teardown
            // failure escape and destabilize another coroutine or thread.
            Log.w("NativeResourceGuard", "Failed to release native resource '$label'", failure)
        }
    }
}
