package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogIncidentDocumentTest {
    private val env = AppLogEnvironmentSnapshot("1.0.11 (a1b2c3d)", "Huawei COR-AL10", "9 (API 28)")

    @Test
    fun translationErrorIncludesBatchResponseAndApplicationFrame() {
        val record = AppLogRecord(
            timestampMs = 1_799_999_000_000L, level = "ERROR", tag = "Translation",
            category = "字幕翻译", message = "翻译服务响应格式不完整，HTTP 200",
            details = "", thread = "worker", eventId = "ev-translation",
            sessionId = "session-a", operationId = "translation-1",
            errorClass = "java.lang.IllegalStateException",
            rootCauseMessage = "期望 5 条译文，实际解析 3 条",
            httpStatusCode = 200,
            diagnosticContext = mapOf(
                "当前处理阶段" to "TRANSLATING", "当前处理步骤" to "缩小批次再次校验",
                "翻译引擎名称" to "智谱 glm", "当前批次进度" to "1/242",
                "本次翻译批次大小" to "5", "本批输入字幕行数" to "5",
                "本批已获得有效译文行数" to "3", "本批未通过校验字幕行数" to "2",
                "HTTP 状态码" to "200", "响应摘要" to "缺少 2 条编号译文",
                "传输/响应/网络重试轨迹" to "5→5 失败", "本次请求耗时 ms" to "4200",
            ),
            exceptionSnapshot = AppLogExceptionSnapshot(
                "java.lang.IllegalStateException", "期望 5 条译文，实际解析 3 条",
                listOf(AppLogStackFrame("io.translatetube.local.translate.Engines",
                    "translateBatchWithAdaptiveRecoveryInternal", "Engines.kt", 1187)),
            ),
        )
        val document = AppLogIncidentDocument.build(record, listOf(record), env)
        val text = document.toPlainText()
        assertTrue(text.contains("日志级别：ERROR"))
        assertTrue(text.contains("模块：字幕翻译"))
        assertTrue(text.contains("处理阶段：TRANSLATING"))
        assertTrue(text.contains("处理引擎/模型：智谱 glm"))
        assertTrue(text.contains("翻译批次：1/242"))
        assertTrue(text.contains("输入字幕行数：5"))
        assertTrue(text.contains("有效译文数/拒绝行数"))
        assertTrue(text.contains("HTTP 状态码：200"))
        assertTrue(text.contains("5→5 失败"))
        assertTrue(text.contains("Engines.kt:1187"))
        assertTrue(text.contains("期望 5 条译文，实际解析 3 条"))
        assertEquals(8, document.sceneFields.size)
        assertTrue(text.length <= 5_000)
    }

    @Test
    fun translationQualityDetailExplainsTheSelectedEventWithoutMixingAnotherFailure() {
        val quality = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "Translation",
            category = "字幕翻译",
            message = "第 7 批翻译失败：第 211 行未通过译文质量校验",
            details = "专有名词 Google 被保留；汉字数=10，外文字母数=6；重试耗尽。",
            thread = "worker",
            eventId = "quality-event",
            sessionId = "translation-session",
            operationId = "translation-op",
            errorClass = "java.lang.IllegalStateException",
            rootCauseMessage = "汉字数 10、外文字母数 6，未满足专有名词外文占比规则。",
            httpStatusCode = 200,
            firstAppStackFrame = "io.translatetube.local.player.PlaybackService.translateLinesInternal(PlaybackService.kt:12193)",
            diagnosticContext = mapOf(
                "翻译引擎名称" to "智谱 GLM-4-Flash-250414",
                "当前批次进度" to "7/7",
                "失败字幕行号" to "211",
                "HTTP 状态码" to "200",
                "本批未通过校验字幕行数" to "1",
                "重试轨迹" to "第 211 行重试多次后仍未通过",
            ),
        )
        val other = quality.copy(
            timestampMs = quality.timestampMs - 1_000L,
            eventId = "format-event",
            message = "第 3 批字幕编号混用，解析器拒绝响应",
            rootCauseMessage = "字幕编号混用了0起始和1起始边界",
        )
        val document = AppLogIncidentDocument.build(quality, listOf(other, quality), env)
        val text = document.toPlainText()

        assertTrue(document.problemTitle.contains("质量校验"))
        assertTrue(document.symptom.contains("第 211 行"))
        assertTrue(document.failureLocation.contains("PlaybackService.kt:12193"))
        assertTrue(document.directCause.contains("汉字数为 10"))
        assertTrue(document.faultNature.contains("HTTP 200"))
        assertFalse(document.directCause.contains("编号混用"))
        assertTrue(text.indexOf("关键原因") < text.indexOf("关联上下文"))
        assertTrue(text.contains("批次=7/7"))
        assertTrue(text.length <= 5_000)
    }

    @Test
    fun responseFormatDetailExplainsUnsafeSubtitleAlignment() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "Translation",
            category = "字幕翻译",
            message = "第 3 批翻译失败：字幕编号混用了0起始和1起始边界",
            details = "本批输入字幕行数：30；索引 272–301；HTTP 状态码：200。",
            thread = "worker",
            eventId = "format-event",
            sessionId = "translation-session",
            operationId = "translation-op",
            errorClass = "java.lang.IllegalStateException",
            rootCauseMessage = "字幕编号混用了0起始和1起始边界",
            httpStatusCode = 200,
            firstAppStackFrame = "io.translatetube.local.translate.Engines.parseModelTranslations(Engines.kt:799)",
            diagnosticContext = mapOf(
                "翻译引擎名称" to "智谱 GLM-4-Flash-250414",
                "当前批次进度" to "3/7",
                "本次翻译批次大小" to "30",
                "本批输入字幕行数" to "30",
                "HTTP 状态码" to "200",
            ),
        )
        val document = AppLogIncidentDocument.build(record, listOf(record), env)
        assertTrue(document.problemTitle.contains("编号混用"))
        assertTrue(document.directCause.contains("无法安全地将译文逐行对应"))
        assertTrue(document.failureLocation.contains("Engines.kt:799"))
        assertTrue(document.faultNature.contains("不支持"))
    }


    @Test
    fun detailUsesTheExistingLogTitleAndDoesNotInventACompletedRetry() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "Translation",
            category = "字幕翻译",
            message = "第 7 批翻译失败：第 211 行未通过译文质量校验",
            details = "专有名词 Google 被保留；汉字数=10，外文字母数=6。",
            thread = "worker",
            eventId = "quality-without-retry-proof",
            httpStatusCode = 200,
            rootCauseMessage = "汉字数 10、外文字母数 6，未满足质量规则。",
        )
        val document = AppLogIncidentDocument.build(record, listOf(record), env)

        assertEquals(AppLogTitleResolver.title(record.tag, record.message), document.problemTitle)
        assertFalse(document.problemTitle.contains("重试后任务停止"))
        assertTrue(document.problemTitle.contains("质量校验"))
    }

    @Test
    fun persistedApplicationFrameIsShownWithoutSnapshotAndCallSiteIsNotMislabelled() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "PlaybackPipeline",
            message = "视频处理流程失败",
            details = "",
            thread = "worker",
            firstAppStackFrame = "io.translatetube.local.player.PlaybackService.translateLinesInternal(PlaybackService.kt:12193)",
            diagnosticContext = mapOf(
                "日志调用位置" to "io.translatetube.local.util.LogWriter.record(LogWriter.kt:87)",
            ),
        )
        val document = AppLogIncidentDocument.build(record, listOf(record), env)
        val codeLines = document.codeLocation?.renderLines().orEmpty()

        assertTrue(document.failureLocation.contains("PlaybackService.kt:12193"))
        assertFalse(document.failureLocation.contains("日志调用位置"))
        assertTrue(codeLines.any { it.contains("日志调用位置（不是异常抛出位置）") && it.contains("LogWriter.kt:87") })
        assertTrue(document.toPlainText().contains("PlaybackService.kt:12193"))
    }

    @Test
    fun relatedEventsAreLabelledByIdentifiersAndUnrelatedErrorsAreExcluded() {
        val selected = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "Translation",
            message = "当前选中的翻译故障",
            details = "",
            thread = "translation-worker",
            eventId = "selected-event",
            sessionId = "session-a",
            operationId = "operation-a",
            issueKey = "translation-signature",
        )
        val sameOperation = selected.copy(
            timestampMs = selected.timestampMs - 1_000L,
            level = "INFO",
            tag = "Translation",
            message = "同一任务的处理记录",
            eventId = "same-operation",
            issueKey = "",
        )
        val sameSignature = selected.copy(
            timestampMs = selected.timestampMs - 2_000L,
            message = "相同故障签名的另一条记录",
            eventId = "same-signature",
            sessionId = "session-b",
            operationId = "operation-b",
        )
        val sameSession = selected.copy(
            timestampMs = selected.timestampMs - 3_000L,
            message = "同会话中的其他处理记录",
            eventId = "same-session",
            operationId = "operation-other",
            issueKey = "",
        )
        val unrelated = selected.copy(
            timestampMs = selected.timestampMs - 4_000L,
            message = "UNRELATED_ERROR_MUST_NOT_APPEAR",
            eventId = "unrelated-error",
            sessionId = "session-unrelated",
            operationId = "operation-unrelated",
            issueKey = "unrelated-signature",
            thread = "different-worker",
        )

        val document = AppLogIncidentDocument.build(
            selected,
            listOf(unrelated, sameSession, sameSignature, sameOperation, selected),
            env,
        )
        val text = document.toPlainText()

        assertTrue(text.contains("[同一任务标识]"))
        assertTrue(text.contains("[同一故障签名]"))
        assertTrue(text.contains("[同一会话，仅上下文]"))
        assertFalse(text.contains("UNRELATED_ERROR_MUST_NOT_APPEAR"))
        assertTrue(text.contains("不自动证明根因"))
    }

    @Test
    fun nearbySameThreadEventsAreMarkedAsWeakContextOnly() {
        val selected = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "PlaybackPipeline",
            message = "选中的播放故障",
            details = "",
            thread = "worker-a",
            eventId = "selected-no-ids",
        )
        val nearby = selected.copy(
            timestampMs = selected.timestampMs - 1_000L,
            level = "INFO",
            message = "同线程附近状态",
            eventId = "nearby-same-thread",
        )
        val otherThread = nearby.copy(
            thread = "worker-b",
            message = "OTHER_THREAD_CONTEXT_MUST_NOT_APPEAR",
            eventId = "other-thread",
        )
        val text = AppLogIncidentDocument.build(
            selected,
            listOf(nearby, otherThread, selected),
            env,
        ).toPlainText()

        assertTrue(text.contains("[同线程且时间接近（弱关联）]"))
        assertTrue(text.contains("不代表同一任务或根因"))
        assertFalse(text.contains("OTHER_THREAD_CONTEXT_MUST_NOT_APPEAR"))
    }

    @Test
    fun displayedAndCopiedIncidentSectionsShareTheSameOrderAndTitles() {
        val record = AppLogRecord(
            timestampMs = 1_800_000_000_000L,
            level = "ERROR",
            tag = "Translation",
            category = "字幕翻译",
            message = "字幕翻译失败",
            details = "",
            thread = "worker",
            eventId = "section-parity",
        )
        val document = AppLogIncidentDocument.build(record, listOf(record), env)
        val sections = document.sections()
        val copied = document.toPlainText()

        assertEquals(document.blockTitles(), sections.map { it.title })
        sections.forEach { section ->
            assertTrue("复制内容缺少分区：" + section.title, copied.contains(section.title))
        }
        assertTrue(copied.contains("发生时间："))
        assertTrue(copied.contains("事件 ID："))
        assertTrue(copied.contains("日志级别：ERROR"))
    }

    @Test
    fun fatalWithExceptionSnapshotShowsCauseSuppressedAndAppFrames() {
        val record = AppLogRecord(
            timestampMs = 1_799_999_000_000L, level = "FATAL", tag = "UncaughtException",
            category = "应用异常", message = "应用发生未捕获异常", details = "", thread = "main",
            eventId = "ev-fatal", sessionId = "session-crash", operationId = "crash-1",
            errorClass = "java.lang.IllegalStateException", rootCauseMessage = "player initialization failed",
            exceptionSnapshot = AppLogExceptionSnapshot(
                "java.lang.IllegalStateException", "player initialization failed",
                listOf(AppLogStackFrame("io.translatetube.local.player.PlayerManager",
                    "initialize", "PlayerManager.kt", 88)),
                cause = AppLogExceptionSnapshot(
                    "java.io.IOException", "media source unavailable",
                    listOf(AppLogStackFrame("io.translatetube.local.player.PlaybackService",
                        "prepareSource", "PlaybackService.kt", 240)),
                ),
                suppressed = listOf(AppLogExceptionSnapshot(
                    "java.lang.IllegalArgumentException", "cleanup failed", emptyList(),
                )),
            ),
        )
        val text = AppLogIncidentDocument.build(record, listOf(record), env).toPlainText()
        assertTrue(text.contains("代码定位"))
        assertTrue(text.contains("PlayerManager.kt:88"))
        assertTrue(text.contains("PlaybackService.kt:240"))
        assertTrue(text.contains("异常链：IllegalStateException → IOException"))
        assertTrue(text.contains("suppressed：IllegalArgumentException"))
    }

    @Test
    fun errorWithoutThrowableUsesOnlyRecordedCallSite() {
        val record = AppLogRecord(
            timestampMs = 1_799_999_000_000L, level = "ERROR", tag = "PlaybackPipeline",
            message = "视频处理流程失败", details = "", thread = "worker",
            diagnosticContext = mapOf("日志调用位置" to "Pipeline.kt:128"),
        )
        val text = AppLogIncidentDocument.build(record, listOf(record), env).toPlainText()
        assertTrue(text.contains("日志调用位置（不是异常抛出位置）：Pipeline.kt:128"))
        assertTrue(text.contains("异常类型：未采集：未采集"))
    }

    @Test
    fun legacyRecordKeepsMissingValuesExplicit() {
        val record = AppLogRecord(1_799_999_000_000L, "WARN", "LegacyWorker", "旧版任务警告", "", "")
        val text = AppLogIncidentDocument.build(record, listOf(record), env).toPlainText()
        assertTrue(text.contains("旧版日志未记录"))
        assertTrue(text.contains("当前处理阶段：未采集"))
        assertTrue(text.contains("当前处理步骤：未采集"))
        assertTrue(text.contains("线程：未采集"))
        assertTrue(text.contains("最后一个成功步骤：未采集"))
        assertTrue(text.length <= 5_000)
    }

    @Test
    fun infoOmitsExceptionAndCodeLocation() {
        val record = AppLogRecord(
            timestampMs = 1_799_999_000_000L, level = "INFO", tag = "PlaybackPipeline",
            message = "开始处理视频", details = "", thread = "main",
            diagnosticContext = mapOf("当前处理阶段" to "TRANSCRIBING"),
        )
        val document = AppLogIncidentDocument.build(record, listOf(record), env)
        val text = document.toPlainText()
        assertNull(document.codeLocation)
        assertFalse(text.contains("代码定位"))
        assertTrue(text.contains("日志级别：INFO"))
        assertTrue(text.contains("现象"))
        assertFalse(text.contains("异常类型："))
    }

    @Test
    fun apiKeyAndBearerCredentialsAreRedacted() {
        val record = AppLogRecord(
            timestampMs = 1_799_999_000_000L, level = "ERROR", tag = "Translation",
            message = "请求失败 API Key: sk-test-secret-123", details = "", thread = "worker",
            diagnosticContext = mapOf(
                "翻译引擎名称" to "测试引擎",
                "响应摘要" to "authorization=Bearer secret-token",
                "当前处理阶段" to "TRANSLATING",
            ),
        )
        val text = AppLogIncidentDocument.build(record, listOf(record), env).toPlainText()
        assertFalse(text.contains("sk-test-secret-123"))
        assertFalse(text.contains("secret-token"))
        assertTrue(text.contains("[REDACTED]"))
    }

    @Test
    fun overlongContentIsCompressedBelowLimitAndRetainsRequiredBlocks() {
        val target = AppLogRecord(
            timestampMs = 1_799_999_000_000L, level = "ERROR", tag = "Translation",
            category = "字幕翻译", message = "长响应导致翻译失败", details = "", thread = "worker",
            eventId = "long-target", sessionId = "session-long", operationId = "op-long",
            rootCauseMessage = "root cause ".repeat(600),
            firstAppStackFrame = "io.translatetube.local.translate.Engines.run(Engines.kt:1887)",
            diagnosticContext = mapOf(
                "翻译引擎名称" to "测试引擎", "当前批次进度" to "1/100",
                "输入字幕行数" to "5", "有效译文行数" to "3", "HTTP 状态码" to "200",
                "响应摘要" to "response ".repeat(1_200),
                "传输/响应/网络重试轨迹" to "retry ".repeat(300),
                "请求耗时" to "2.5s", "当前处理阶段" to "TRANSLATING", "当前处理步骤" to "批次校验",
            ),
        )
        val earlier = (1..12).map { index ->
            target.copy(
                timestampMs = target.timestampMs - (13 - index) * 1_000L,
                level = "INFO", tag = "Translate",
                message = "较早事件 " + index + " " + "context ".repeat(80),
                eventId = "prior-" + index, sequence = index.toLong(),
            )
        }
        val document = AppLogIncidentDocument.build(target, earlier + target, env)
        val text = document.toPlainText()
        assertTrue(text.length <= 5_000)
        assertTrue(text.contains("基本信息"))
        assertTrue(text.contains("故障"))
        assertTrue(text.contains("代码定位"))
        assertTrue(text.contains("现场"))
        assertTrue(text.contains("关联上下文"))
        assertTrue(text.contains("对照该 Git SHA 的代码定位真实根因"))
        assertTrue(text.contains("不要用吞异常/空结果/伪造成功掩盖故障"))
        assertTrue(text.contains("[已截断，原长"))
        assertTrue(document.codeLocation?.additionalFrames?.isEmpty() != false)
    }
}
