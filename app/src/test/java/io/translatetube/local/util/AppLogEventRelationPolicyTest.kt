package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogEventRelationPolicyTest {
    @Test
    fun sameOperationSeparatesEarlierAndLaterFailuresWithoutClaimingCausality() {
        val earlier = record(1_000L, "earlier", "WARN", "op-1", "session-1")
        val selected = record(2_000L, "selected", "ERROR", "op-1", "session-1")
        val later = record(3_000L, "later", "ERROR", "op-1", "session-1")
        val roles = AppLogEventRelationPolicy.againstSelected(listOf(earlier, selected, later), selected)
        assertEquals("EARLIER_ANOMALY_SAME_OPERATION", roles["event:earlier"]?.role)
        assertEquals("SELECTED_EVENT", roles["event:selected"]?.role)
        assertEquals("LATER_ANOMALY_SAME_OPERATION", roles["event:later"]?.role)
        assertTrue(roles.getValue("event:later").evidence.contains("可能是后续连锁"))
    }

    @Test
    fun explicitRecoveryIsRecognizedAcrossProcessSessions() {
        val selected = record(1_000L, "failure", "ERROR", "", "old-session", "issue-1")
        val recovery = record(2_000L, "recovery", "INFO", "", "new-session")
            .copy(resolvesIssueKey = "issue-1")
        val relations = AppLogEventRelationPolicy.againstSelected(listOf(selected, recovery), selected)
        assertEquals("CONFIRMED_RECOVERY", relations["event:recovery"]?.role)
        assertTrue(relations.getValue("event:recovery").evidence.contains("完全匹配"))
    }

    @Test
    fun sameSessionWithoutOperationIdIsOnlyContextNotSameTask() {
        val selected = record(10_000L, "selected", "ERROR", "", "session-1")
        val neighbor = record(11_000L, "neighbor", "WARN", "", "session-1")
        val relations = AppLogEventRelationPolicy.againstSelected(listOf(selected, neighbor), selected)
        assertEquals("SAME_SESSION_CONTEXT", relations["event:neighbor"]?.role)
    }

    @Test
    fun operationRoleMarksFirstAnomalyAsCandidateAndLaterAnomalySeparately() {
        val info = record(500L, "start", "INFO", "op-1", "session-1")
        val first = record(1_000L, "first", "ERROR", "op-1", "session-1")
        val second = record(2_000L, "second", "ERROR", "op-1", "session-1")
        val roles = AppLogEventRelationPolicy.operationRoles(listOf(info, first, second))
        assertEquals("SAME_OPERATION_CONTEXT", roles["event:start"]?.role)
        assertEquals("FIRST_RECORDED_ANOMALY_CANDIDATE", roles["event:first"]?.role)
        assertEquals("LATER_ANOMALY_SAME_OPERATION", roles["event:second"]?.role)
        assertTrue(roles.getValue("event:first").evidence.contains("不等同于已证实根因"))
    }

    private fun record(
        time: Long,
        eventId: String,
        level: String,
        operationId: String = "",
        sessionId: String = "",
        issueKey: String = "",
    ) = AppLogRecord(
        timestampMs = time,
        level = level,
        tag = "Test",
        message = eventId,
        details = "",
        thread = "test",
        eventId = eventId,
        sessionId = sessionId,
        sequence = time,
        operationId = operationId,
        issueKey = issueKey,
    )
}
