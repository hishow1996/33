package io.translatetube.local.util

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AppLogSearchIndexTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun record(
        timestampMs: Long = 1_800_000_000_000L,
        level: String = "INFO",
        tag: String = "Playback",
        message: String = "播放器已准备",
        details: String = "",
        issueKey: String = "",
        recoveredAtMs: Long? = null,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) = AppLogRecord(
        timestampMs = timestampMs,
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

    @Test
    fun everySpaceSeparatedWordMustMatchAndMinusWordsExclude() = runBlocking {
        val index = AppLogSearchIndex.build(
            listOf(
                record(tag = "Translation", message = "字幕翻译失败 安全过滤"),
                record(tag = "Translation", message = "字幕翻译失败 网络超时"),
                record(tag = "Playback", message = "字幕已显示"),
            ),
        )

        assertEquals(2, AppLogSearchIndex.search(index, "全部", "翻译 失败").size)
        assertEquals(
            listOf("字幕翻译失败 安全过滤"),
            AppLogSearchIndex.search(index, "全部", "翻译 安全").map { it.record.message },
        )
        assertEquals(
            listOf("字幕翻译失败 网络超时"),
            AppLogSearchIndex.search(index, "全部", "翻译失败 -安全").map { it.record.message },
        )
        assertEquals(
            listOf("字幕翻译失败 安全过滤"),
            AppLogSearchIndex.search(index, "全部", "\"失败 安全\"").map { it.record.message },
        )
        assertTrue(AppLogSearchIndex.search(index, "全部", "翻译 配音").isEmpty())
    }

    @Test
    fun excludeWordsOnlyLookAtFieldsNotStackDetails() = runBlocking {
        val index = AppLogSearchIndex.build(
            listOf(record(message = "播放失败", details = "at foo.Bar(retry)")),
        )

        assertEquals(1, AppLogSearchIndex.search(index, "全部", "播放 -retry").size)
    }

    @Test
    fun fieldMatchesComeBeforeDetailsOnlyMatchesAndAreFlagged() = runBlocking {
        val detailsOnly = record(message = "第一条", details = "java.lang.IllegalStateException: boom")
        val inTitle = record(message = "IllegalStateException 出现")
        val index = AppLogSearchIndex.build(listOf(detailsOnly, inTitle))

        val result = AppLogSearchIndex.searchDetailed(index, "全部", "illegalstateexception")

        assertEquals(listOf("IllegalStateException 出现", "第一条"), result.entries.map { it.record.message })
        assertEquals(1, result.detailsOnlyCount)
        assertTrue(result.isDetailsOnly(result.entries[1]))
        assertTrue(!result.isDetailsOnly(result.entries[0]))
    }

    @Test
    fun logTimeIsSearchable() = runBlocking {
        // 1_800_000_000_000 ms is 2027-01-15 08:00:00.000 UTC.
        val early = record(message = "early", timestampMs = 1_800_000_000_000L)
        val later = record(message = "later", timestampMs = 1_800_000_000_000L + 58L * 60_000L + 48_195L)
        val index = AppLogSearchIndex.build(listOf(early, later), utc)

        assertEquals(listOf("later"), AppLogSearchIndex.search(index, "全部", "08:58").map { it.record.message })
        assertEquals(2, AppLogSearchIndex.search(index, "全部", "2027-01-15").size)
        assertEquals(listOf("later"), AppLogSearchIndex.search(index, "全部", "01-15 08:58:48").map { it.record.message })
    }

    @Test
    fun moduleAndTimeRangeFiltersCombineWithTextAndLevel() = runBlocking {
        val old = record(tag = "Translation", message = "旧的翻译日志", timestampMs = 1_000_000L)
        val recent = record(tag = "Translation", message = "新的翻译日志", timestampMs = 9_000_000L)
        val other = record(tag = "Playback", message = "新的播放日志", timestampMs = 9_000_000L)
        val index = AppLogSearchIndex.build(listOf(old, recent, other), utc)
        val translationLabel = index.first { it.record.tag == "Translation" }.featureLabel

        val result = AppLogSearchIndex.searchDetailed(
            index,
            filter = "全部",
            query = "日志",
            module = translationLabel,
            sinceMs = 5_000_000L,
        )

        assertEquals(listOf("新的翻译日志"), result.entries.map { it.record.message })
        assertTrue(AppLogSearchIndex.moduleCounts(index).any { it.first == translationLabel && it.second == 2 })
    }
}
