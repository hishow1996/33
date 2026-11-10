package io.translatetube.local.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogSearchIndexTest {

    private fun record(
        level: String = "INFO",
        tag: String = "Playback",
        message: String = "播放器已准备",
        details: String = "",
        issueKey: String = "",
        recoveredAtMs: Long? = null,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) = AppLogRecord(
        timestampMs = 1_800_000_000_000L,
        level = level,
        tag = tag,
        message = message,
        details = details,
        thread = "main",
        issueKey = issueKey,
        recoveredAtMs = recoveredAtMs,
        eventId = "event-${tag.lowercase()}-${level.lowercase()}",
        sessionId = "session-1",
        operationId = "operation-1",
        diagnosticContext = diagnosticContext,
    )

    @Test
    fun searchesMessageModuleAndStructuredContext() = runBlocking {
        val input = listOf(
            record(
                tag = "AudioExtraction",
                message = "音频读取失败",
                diagnosticContext = mapOf("下载阶段" to "解析音频源"),
            ),
            record(message = "正常播放"),
        )
        val index = AppLogSearchIndex.build(input)

        assertEquals("音频读取失败", AppLogSearchIndex.search(index, "全部", "音频").single().record.message)
        assertEquals("音频读取失败", AppLogSearchIndex.search(index, "全部", "解析音频源").single().record.message)
        assertEquals(
            listOf("正常播放"),
            AppLogSearchIndex.search(index, "全部", "PLAYBACK").map { it.record.message },
        )
        assertEquals(2, AppLogSearchIndex.search(index, "全部", "").size)
    }

    @Test
    fun searchesDetailsWithoutCreatingAnotherCopyOfLargeStackText() = runBlocking {
        val longDetails = "prefix-" + "x".repeat(50_000) + "-unique-stack-marker"
        val index = AppLogSearchIndex.build(listOf(record(details = longDetails)))

        val result = AppLogSearchIndex.search(index, "全部", "unique-stack-marker")
        assertEquals(1, result.size)
        assertTrue(result.single().record.details === longDetails)
    }

    @Test
    fun warningAndRecoveryFiltersRemainIndependentFromTextSearch() = runBlocking {
        val warning = record(level = "WARN", issueKey = "warning-1")
        val recovered = record(
            level = "ERROR",
            tag = "Translation",
            issueKey = "translation-1",
            recoveredAtMs = 1_800_000_000_100L,
        )
        val index = AppLogSearchIndex.build(listOf(warning, recovered))

        assertEquals(listOf(warning), AppLogSearchIndex.search(index, "未恢复", "").map { it.record })
        assertEquals(listOf(recovered), AppLogSearchIndex.search(index, "已恢复", "").map { it.record })
        assertEquals(listOf(warning), AppLogSearchIndex.search(index, "警告", "").map { it.record })
    }

    @Test
    fun aNewQuerySearchesCurrentSnapshotAndReturnsNoStaleMatch() = runBlocking {
        val index = AppLogSearchIndex.build(
            listOf(
                record(message = "first request timeout"),
                record(tag = "Translation", message = "subtitle translated"),
            ),
        )

        assertEquals(1, AppLogSearchIndex.search(index, "全部", "timeout").size)
        assertTrue(AppLogSearchIndex.search(index, "全部", "no-such-entry").isEmpty())
    }
}
