package io.translatetube.local.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRecentEventTrailTest {
    @Test
    fun operationTrailsNeverMixConcurrentOperations() {
        val trails = AppLogRecentEventTrail()
        trails.add("operation-a", 10L, 1_000L, "A1")
        trails.add("operation-b", 10L, 1_010L, "B1")
        trails.add("operation-a", 11L, 1_020L, "A2")
        val a = trails.snapshot("operation-a", 11L, 1_030L)
        val b = trails.snapshot("operation-b", 10L, 1_030L)
        assertTrue(a.scopeLabel.contains("operationId"))
        assertTrue(a.summary.contains("A1"))
        assertTrue(a.summary.contains("A2"))
        assertFalse(a.summary.contains("B1"))
        assertTrue(b.summary.contains("B1"))
        assertFalse(b.summary.contains("A1"))
    }

    @Test
    fun eventsWithoutOperationIdAreIsolatedByThreadAndTimeWindow() {
        val trails = AppLogRecentEventTrail(unscopedThreadWindowMs = 2_000L)
        trails.add("", 10L, 900L, "thread-10-old")
        trails.add("", 10L, 2_900L, "thread-10-new")
        trails.add("", 11L, 2_900L, "thread-11")
        val recent = trails.snapshot("", 10L, 3_000L)
        val otherThread = trails.snapshot("", 11L, 3_000L)
        assertTrue(recent.scopeLabel.contains("同一线程"))
        assertFalse(recent.summary.contains("thread-10-old"))
        assertTrue(recent.summary.contains("thread-10-new"))
        assertFalse(recent.summary.contains("thread-11"))
        assertTrue(otherThread.summary.contains("thread-11"))
    }

    @Test
    fun futureEventsAreNotIncludedInTheSelectedFailureTrail() {
        val trails = AppLogRecentEventTrail()
        trails.add("operation-a", 10L, 2_000L, "future-event")
        assertFalse(trails.snapshot("operation-a", 10L, 1_000L).summary.contains("future-event"))
    }

    @Test
    fun clearRemovesEveryTrail() {
        val trails = AppLogRecentEventTrail()
        trails.add("operation-a", 10L, 1_000L, "before clear")
        trails.clear()
        assertTrue(trails.snapshot("operation-a", 10L, 1_100L).summary.isBlank())
    }
}
