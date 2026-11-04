package io.translatetube.local.util

import java.util.ArrayDeque

internal data class AppLogRecentEventTrailSnapshot(
    val scopeLabel: String,
    val summary: String,
)

/**
 * Keeps preceding events within one operation. Events without an operation ID are isolated by
 * thread and limited to a short time window; they are never mixed into another operation's trail.
 */
internal class AppLogRecentEventTrail(
    private val maxTrackedScopes: Int = 12,
    private val maxEventsPerScope: Int = 24,
    private val unscopedThreadWindowMs: Long = 120_000L,
) {
    private data class Event(val timestampMs: Long, val summary: String)

    private val operationEvents = LinkedHashMap<String, ArrayDeque<Event>>()
    private val threadEvents = LinkedHashMap<Long, ArrayDeque<Event>>()

    init {
        require(maxTrackedScopes > 0)
        require(maxEventsPerScope > 0)
        require(unscopedThreadWindowMs > 0L)
    }

    @Synchronized
    fun add(operationId: String, threadId: Long, timestampMs: Long, summary: String) {
        val normalizedOperationId = operationId.trim()
        val bucket = if (normalizedOperationId.isNotBlank()) {
            operationEvents.remove(normalizedOperationId) ?: ArrayDeque<Event>()
        } else {
            threadEvents.remove(threadId) ?: ArrayDeque<Event>()
        }
        if (normalizedOperationId.isNotBlank()) {
            operationEvents[normalizedOperationId] = bucket
            trimScopes(operationEvents)
        } else {
            threadEvents[threadId] = bucket
            trimScopes(threadEvents)
        }
        bucket.addLast(Event(timestampMs, summary))
        while (bucket.size > maxEventsPerScope) bucket.removeFirst()
    }

    @Synchronized
    fun snapshot(operationId: String, threadId: Long, atTimestampMs: Long): AppLogRecentEventTrailSnapshot {
        val normalizedOperationId = operationId.trim()
        if (normalizedOperationId.isNotBlank()) {
            val events = operationEvents[normalizedOperationId]
                ?.filter { it.timestampMs <= atTimestampMs }
                .orEmpty()
            return AppLogRecentEventTrailSnapshot("同一 operationId", events.joinToString("\n") { it.summary })
        }
        val cutoff = atTimestampMs - unscopedThreadWindowMs
        val events = threadEvents[threadId]?.filter { it.timestampMs in cutoff..atTimestampMs }.orEmpty()
        return AppLogRecentEventTrailSnapshot(
            "同一线程最近 2 分钟（未获得 operationId）",
            events.joinToString("\n") { it.summary },
        )
    }

    @Synchronized
    fun clear() {
        operationEvents.clear()
        threadEvents.clear()
    }

    private fun <K> trimScopes(scopes: LinkedHashMap<K, ArrayDeque<Event>>) {
        while (scopes.size > maxTrackedScopes) {
            val iterator = scopes.entries.iterator()
            if (!iterator.hasNext()) return
            scopes.remove(iterator.next().key)
        }
    }
}
