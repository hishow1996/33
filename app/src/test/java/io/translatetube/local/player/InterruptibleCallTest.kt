package io.translatetube.local.player

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InterruptibleCallTest {
    @Test
    fun finishedCallReturnsItsValue() = runBlocking {
        val result = runUntilInterrupted(CompletableDeferred<Unit>()) { "译文" }
        assertEquals(InterruptibleResult.Done("译文"), result)
    }

    @Test
    fun alreadyInterruptedNeverRunsTheCall() = runBlocking {
        val ran = AtomicBoolean(false)
        val interrupt = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val result = runUntilInterrupted(interrupt) { ran.set(true); 1 }
        assertEquals(InterruptibleResult.Interrupted, result)
        assertTrue(!ran.get())
    }

    @Test
    fun interruptReturnsPromptlyAndCancelsTheSlowCall() = runBlocking {
        val interrupt = CompletableDeferred<Unit>()
        val cancelled = AtomicBoolean(false)
        launch { delay(100); interrupt.complete(Unit) }
        val startedAt = System.nanoTime()
        val result = runUntilInterrupted(interrupt) {
            try {
                delay(30_000)
                "太晚了"
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.set(true)
                throw e
            }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(InterruptibleResult.Interrupted, result)
        assertTrue("应在 2 秒内返回，实际 ${elapsedMs}ms", elapsedMs < 2_000)
        delay(200)
        assertTrue(cancelled.get())
    }

    @Test
    fun aBlockingCallThatCannotBeCancelledDoesNotHoldTheCallerUp() = runBlocking {
        val interrupt = CompletableDeferred<Unit>()
        launch { delay(100); interrupt.complete(Unit) }
        val startedAt = System.nanoTime()
        val result = runUntilInterrupted(interrupt) {
            withContext(Dispatchers.IO) { Thread.sleep(3_000); "本地模型推理" }
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(InterruptibleResult.Interrupted, result)
        assertTrue("不能被原生推理拖住，实际 ${elapsedMs}ms", elapsedMs < 2_000)
    }

    @Test
    fun failureOfTheCallPropagatesToTheCaller() = runBlocking {
        var caught: Throwable? = null
        try {
            runUntilInterrupted(CompletableDeferred<Unit>()) { throw IllegalStateException("接口失败") }
        } catch (e: IllegalStateException) {
            caught = e
        }
        assertEquals("接口失败", caught?.message)
    }

    @Test
    fun cancellingTheCallerCancelsTheCall() = runBlocking {
        val cancelled = AtomicBoolean(false)
        val started = CompletableDeferred<Unit>()
        val job = launch {
            runUntilInterrupted(CompletableDeferred<Unit>()) {
                try {
                    started.complete(Unit)
                    delay(30_000)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled.set(true)
                    throw e
                }
            }
        }
        started.await()
        job.cancel()
        job.join()
        delay(200)
        assertTrue(cancelled.get())
    }
}
