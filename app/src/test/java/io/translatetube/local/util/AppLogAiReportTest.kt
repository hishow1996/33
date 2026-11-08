package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogAiReportTest {
    @Test
    fun reportContainsSummaryAndCompleteChronologicalJsonlTimeline() {
        val records = listOf(
            AppLogRecord(
                timestampMs = 2_000L,
                level = "ERROR",
                tag = "Translation",
                message = "字幕翻译失败",
                details = "异常根因链：\\n#0 java.net.SocketTimeoutException: timeout",
                thread = "DefaultDispatcher-worker-2",
                eventId = "event-2",
                sessionId = "session-a",
                operationId = "pipeline-test-1",
                sequence = 2L,
                category = "字幕翻译",
                errorClass = "java.net.SocketTimeoutException",
                rootCauseMessage = "connect timed out",
                firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.translate(TranslationEngine.kt:88)",
                diagnosticContext = mapOf(
                    "HTTP 状态码" to "429",
                    "请求阶段" to "提交",
                    "当前处理阶段" to "TRANSLATING",
                    "当前 UI 阶段" to "TRANSLATING",
                    "当前处理步骤" to "正在处理第 1/3 批",
                    "处理流程" to "AUTO",
                    "翻译引擎" to "测试翻译引擎",
                    "字幕总行数" to "50",
                    "已获得有效译文行数" to "16",
                    "日志调用位置采集状态" to "已定位到应用内调用帧",
                    "日志调用位置" to "io.translatetube.local.translate.TranslationEngine.translateBatch(TranslationEngine.kt:214)",
                    "日志调用链（最多8帧）" to "io.translatetube.local.translate.TranslationEngine.translateBatch(TranslationEngine.kt:214)",
                    "异常对象采集状态" to "已提供 Throwable",
                ),
                httpStatusCode = 429,
                exceptionSnapshot = AppLogExceptionSnapshot(
                    type = "java.lang.IllegalStateException",
                    message = "translation request failed",
                    stackFrames = listOf(
                        AppLogStackFrame(
                            "io.translatetube.local.translate.TranslationEngine",
                            "translate",
                            "TranslationEngine.kt",
                            88,
                        ),
                    ),
                    cause = AppLogExceptionSnapshot(
                        type = "java.net.SocketTimeoutException",
                        message = "read timed out",
                        stackFrames = listOf(
                            AppLogStackFrame("java.net.SocketInputStream", "read", "SocketInputStream.java", 0),
                        ),
                    ),
                    suppressed = listOf(
                        AppLogExceptionSnapshot(
                            type = "java.io.IOException",
                            message = "response close failed",
                            stackFrames = emptyList(),
                            truncated = true,
                        ),
                    ),
                ),
            ),
            AppLogRecord(
                timestampMs = 1_000L,
                level = "INFO",
                tag = "PlaybackPipeline",
                message = "开始处理视频",
                details = "阶段：语音转写",
                thread = "main",
                diagnosticContext = mapOf(
                    "当前处理阶段" to "TRANSCRIBING",
                    "当前 UI 阶段" to "TRANSCRIBING",
                    "当前处理步骤" to "正在整理语音时间轴",
                    "处理流程" to "AUTO",
                    "转写引擎" to "Whisper",
                    "日志调用位置采集状态" to "已定位到应用内调用帧",
                    "日志调用位置" to "io.translatetube.local.playback.PlaybackPipeline.start(PlaybackPipeline.kt:55)",
                    "日志调用链（最多8帧）" to "io.translatetube.local.playback.PlaybackPipeline.start(PlaybackPipeline.kt:55)",
                    "异常对象采集状态" to "普通事件未提供 Throwable",
                ),
                eventId = "event-1",
                sessionId = "session-a",
                operationId = "pipeline-test-1",
                sequence = 1L,
                category = "视频处理流程",
            ),
        )
        val report = AppLogAiReport.render(
            records = records,
            exportedAt = "2026-11-04 20:00:00 +0800",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 2_000L, true),
        )

        assertTrue(report.contains("report_schema: elephant-app-log/v9"))
        assertTrue(report.contains("最近最多 800 条"))
        assertTrue(report.contains("优先检查的故障事件"))
        assertTrue(report.contains("完整事件时间线（JSONL"))
        assertTrue(report.contains("\"eventId\":\"event-1\""))
        assertTrue(report.contains("\"eventId\":\"event-2\""))
        assertTrue(report.contains("\"errorClass\":\"java.net.SocketTimeoutException\""))
        assertTrue(report.contains("\"rootCauseMessage\":\"connect timed out\""))
        assertTrue(report.contains("\"firstAppStackFrame\":\"io.translatetube.local.translate.TranslationEngine.translate(TranslationEngine.kt:88)\""))
        assertTrue(report.contains("\"logCallSite\":\"io.translatetube.local.translate.TranslationEngine.translateBatch(TranslationEngine.kt:214)\""))
        assertTrue(report.contains("\"logCallSiteStatus\":\"已定位到应用内调用帧\""))
        assertTrue(report.contains("\"exceptionCaptureStatus\":\"已提供 Throwable\""))
        assertTrue(report.contains("日志调用位置="))
        assertTrue(report.contains("\"HTTP 状态码\":\"429\""))
        assertTrue(report.contains("\"请求阶段\":\"提交\""))
        assertTrue(report.contains("AI 分类=HTTP 429：请求频率受限"))
        assertTrue(report.contains("\"exceptionSnapshot\""))
        assertTrue(report.contains("\"message\":\"response close failed\""))
        assertTrue(report.contains("\"truncated\":true"))
        assertTrue(report.indexOf("\"eventId\":\"event-1\"") < report.indexOf("\"eventId\":\"event-2\""))
        assertTrue(report.contains("不要把不同进程会话混为一次任务"))
        assertTrue(report.contains("任务故障关系候选"))
        assertTrue(report.contains("五阶段诊断摘要"))
        assertTrue(report.contains("阶段=TRANSLATING"))
        assertTrue(report.contains("阶段=TRANSCRIBING"))
        assertTrue(report.contains("正在处理第 1/3 批"))
        assertTrue(report.contains("\"processingStage\":\"TRANSLATING\""))
        assertTrue(report.contains("最早异常候选事件=event-2"))
        assertTrue(report.contains("FIRST_RECORDED_ANOMALY_CANDIDATE"))
        assertTrue(report.contains("不能直接认定为真实根因"))
        assertTrue(report.contains("operationFailureRole"))
        assertTrue(report.contains("缺失证据="))
        assertTrue(report.contains("近期操作轨迹关联方式"))
    }

    @Test
    fun legacyRecordsAreExplicitlyMarked() {
        val report = AppLogAiReport.render(
            records = listOf(AppLogRecord(1_000L, "WARN", "Legacy", "旧版警告", "", "main")),
            exportedAt = "now",
            appVersion = "unknown",
            device = "unknown",
            androidVersion = "unknown",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 0L, true),
        )
        assertTrue(report.contains("legacy/unknown"))
        assertTrue(report.contains("legacy-1000-1"))
        assertTrue(report.contains("\"schemaVersion\":1"))
        assertTrue(report.contains("\"reportSchemaVersion\":9"))
        assertTrue(report.contains("\"logCallSiteStatus\":\"旧版日志未记录\""))
        assertFalse(report.contains("undefined"))
    }
    @Test
    fun compactClipboardReportStaysWithinChatLimitAndRetainsPriorityErrors() {
        val records = (1..80).map { index ->
            AppLogRecord(
                timestampMs = index * 1_000L,
                level = if (index % 5 == 0) "FATAL" else "ERROR",
                tag = "Translation",
                message = "翻译批次故障-$index",
                details = "长异常上下文 ".repeat(260),
                thread = "worker-$index",
                eventId = "compact-error-$index",
                sessionId = "compact-session",
                sequence = index.toLong(),
                operationId = "compact-operation",
                errorClass = "java.io.IOException",
                rootCauseMessage = "response-$index timed out",
                firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.run(TranslationEngine.kt:120)",
            )
        }

        val compact = AppLogAiReport.renderCompact(
            records = records,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 80_000L, true),
        )
        val full = AppLogAiReport.render(
            records = records,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 80_000L, true),
        )

        assertTrue(compact.length <= 6_000)
        assertTrue(compact.contains("elephant-app-log/chat-v1"))
        assertTrue(compact.contains("翻译批次故障-80"))
        assertTrue(compact.contains("首帧="))
        assertTrue(compact.contains("未复制"))
        assertTrue(full.contains("elephant-app-log/v9"))
        assertTrue(full.contains("compact-error-1"))
    }

    @Test
    fun incidentReportIncludesPrimaryFailureAndOnlyItsCorrelatedEvents() {
        val target = AppLogRecord(
            timestampMs = 20_000L,
            level = "ERROR",
            tag = "Translation",
            message = "字幕翻译失败",
            details = "异常根因链：\\n#0 java.net.SocketTimeoutException: read timed out",
            thread = "DefaultDispatcher-worker-2",
            eventId = "failure-2",
            sessionId = "session-a",
            sequence = 2L,
            category = "字幕翻译",
            operationId = "op-77",
            errorClass = "java.net.SocketTimeoutException",
            rootCauseMessage = "read timed out",
            firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.run(TranslationEngine.kt:120)",
            diagnosticContext = mapOf(
                "HTTP 状态码" to "504",
                "请求阶段" to "读取响应",
                "日志调用位置采集状态" to "已定位到应用内调用帧",
                "日志调用位置" to "io.translatetube.local.translate.TranslationEngine.run(TranslationEngine.kt:120)",
                "日志调用链（最多8帧）" to "io.translatetube.local.translate.TranslationEngine.run(TranslationEngine.kt:120)",
                "异常对象采集状态" to "已提供 Throwable",
            ),
            httpStatusCode = 504,
            exceptionSnapshot = AppLogExceptionSnapshot(
                type = "java.io.IOException",
                message = "translation request failed",
                stackFrames = emptyList(),
                cause = AppLogExceptionSnapshot(
                    type = "java.net.SocketTimeoutException",
                    message = "read timed out",
                    stackFrames = emptyList(),
                ),
            ),
        )
        val related = target.copy(
            timestampMs = 19_000L,
            level = "INFO",
            tag = "Translation",
            message = "开始请求翻译服务",
            details = "请求阶段：提交",
            eventId = "start-1",
            sequence = 1L,
        )
        val earlierAnomaly = target.copy(
            timestampMs = 18_500L,
            level = "WARN",
            message = "翻译前置检查出现警告",
            eventId = "earlier-anomaly",
            sequence = 0L,
            issueKey = "earlier-issue",
        )
        val report = AppLogAiReport.renderIncident(
            records = listOf(earlierAnomaly, related, target),
            target = target,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 20_000L, true),
        )

        assertTrue(report.contains("report_schema: elephant-app-log/incident-v9"))
        assertTrue(report.contains("异常链：IOException -> SocketTimeoutException"))
        assertTrue(report.contains("HTTP 504"))
        assertTrue(report.contains("根因：read timed out"))
        assertTrue(report.contains("首个应用栈帧："))
        assertTrue(report.contains("关联事件"))
        assertTrue(report.contains("关联范围"))
    }


    @Test
    fun incidentRepairReportStaysWithinChatLengthAndMarksOmittedTimelineEvents() {
        val target = AppLogRecord(
            timestampMs = 50_000L,
            level = "ERROR",
            tag = "Translation",
            message = "主故障",
            details = "主故障上下文 ".repeat(500),
            thread = "worker-main",
            eventId = "primary-large",
            sessionId = "session-large",
            operationId = "large-operation",
            errorClass = "java.io.IOException",
            rootCauseMessage = "response body was invalid",
            firstAppStackFrame = "io.translatetube.local.translate.TranslationEngine.run(TranslationEngine.kt:120)",
        )
        val related = (1..60).map { index ->
            target.copy(
                timestampMs = 50_000L + index,
                level = if (index % 3 == 0) "ERROR" else "INFO",
                message = "关联事件-$index",
                details = "较长的过程上下文 ".repeat(180),
                eventId = "large-event-$index",
                sequence = index.toLong(),
            )
        }

        val report = AppLogAiReport.renderIncident(
            records = related + target,
            target = target,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 50_000L, true),
        )

        assertTrue(report.length <= 4_000)
        assertTrue(report.contains("response body was invalid"))
        assertTrue(report.contains("关联事件"))
        assertTrue(report.contains("故障：ERROR java.io.IOException"))
    }

    @Test
    fun incidentRepairReportIdentifiesMergedTranslationDiagnosticEvidence() {
        val diagnosticId = "8caaf8ef-8d5b-437d-b292-5fc859a6e17d"
        val target = AppLogRecord(
            timestampMs = 20_000L,
            level = "ERROR",
            tag = "Translation",
            message = "字幕翻译失败",
            details = listOf("故障上下文：", "- 翻译诊断 ID：$diagnosticId").joinToString("\n"),
            thread = "DefaultDispatcher-worker-2",
            eventId = "translation-failure",
            sessionId = "session-a",
            sequence = 2L,
            operationId = "translation-operation",
        )
        val related = target.copy(
            timestampMs = 19_000L,
            level = "INFO",
            message = "翻译任务开始",
            details = listOf("故障上下文：", "- 翻译诊断 ID：$diagnosticId").joinToString("\n"),
            eventId = "translation-start",
            sequence = 1L,
        )

        val report = AppLogAiReport.renderIncident(
            records = listOf(related, target),
            target = target,
            exportedAt = "now",
            appVersion = "1.0",
            device = "Test Device",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 20_000L, true),
        )

        assertTrue(report.contains("同诊断 ID $diagnosticId"))
        assertTrue(report.contains("（2 条事件）"))
        assertTrue(report.contains("翻译专项要求"))
        assertTrue(report.contains("翻译任务开始"))
    }
    @Test
    fun verifyErrorIncidentKeepsDexInstructionAndBuildRevisionWithinClipboardLimit() {
        val params=(1..17).joinToString(""){"Landroidx/compose/runtime/Composer;"}
        val cause="Verifier rejected class Lio/translatetube/local/ui/screens/PlayerScreenKt;->PlayerScreen($params)V: failed to verify: [0x4440] copy1 v2<-v311 type=Reference: kotlinx.coroutines.CoroutineScope cat=1 (classes7.dex)"
        val target=AppLogRecord(
            timestampMs=1_799_000_000_000L,level="FATAL",tag="UncaughtException",
            message=cause,details="异常根因链：\n#0 java.lang.VerifyError: $cause\n处理方式：崩溃线程名称：main",
            thread="main",errorClass="java.lang.VerifyError",rootCauseMessage=cause,
            firstAppStackFrame="io.translatetube.local.ui.screens.PlayerScreenKt.PlayerScreen(PlayerScreen.kt:120)",
            exceptionSnapshot=AppLogExceptionSnapshot(
                type="java.lang.VerifyError",message=cause,stackFrames=listOf(
                    AppLogStackFrame("androidx.compose.runtime.ComposerImpl","startRestartGroup","Composer.kt",1),
                    AppLogStackFrame("io.translatetube.local.ui.screens.PlayerScreenKt","PlayerScreen","PlayerScreen.kt",120),
                ),
            ),
            diagnosticContext=mapOf("当前处理阶段" to "IDLE","视频来源" to "YouTube","日志调用位置" to "not useful"),
        )
        val result=AppLogAiReport.renderIncident(
            records=listOf(target),target=target,exportedAt="2026-11-08 15:06",
            appVersion="1.0.11 (efb32d1)",device="HUAWEI COR-AL10",androidVersion="Android 9",
            writerHealth=AppLogWriterHealth(0L,0L,"",0L,true),
        )
        assertTrue(result.length<=4_000)
        assertTrue(result.contains("copy1 v2<-v311"))
        assertTrue(result.contains("PlayerScreen"))
        assertEquals(1,Regex("Verifier rejected").findAll(result).count())
        assertTrue(result.contains("字节码校验失败"))
        assertTrue(result.contains("java.lang.VerifyError"))
        assertTrue(result.contains("efb32d1"))
        assertTrue(result.contains("寄存器编号超过 255"))
        assertFalse(result.contains("logCallSite"))
        assertFalse(result.contains("diagnosticContextBudget"))
    }

    @Test
    fun incidentRetainsWhitelistedHttpTranslationAndBatchEvidence() {
        val target=AppLogRecord(
            timestampMs=1_799_000_000_000L,level="ERROR",tag="Translation",
            message="翻译批次请求失败",details="",thread="translation-worker",
            errorClass="java.io.IOException",rootCauseMessage="HTTP 429",httpStatusCode=429,
            diagnosticContext=mapOf(
                "视频来源" to "YouTube","当前处理阶段" to "TRANSLATING",
                "翻译引擎" to "Gemini API","转写引擎" to "Whisper","配音引擎" to "Edge TTS",
                "HTTP 状态码" to "429","批次索引" to "2/5","字幕行索引" to "41",
                "日志调用位置" to "must be omitted",
            ),
        )
        val result=AppLogAiReport.renderIncident(
            listOf(target),target,"now","1.0.11 (1234567)","Test Device","Android 9",
            AppLogWriterHealth(0L,0L,"",0L,true),
        )
        assertTrue(result.contains("HTTP 状态码=429"))
        assertTrue(result.contains("翻译引擎=Gemini API"))
        assertTrue(result.contains("批次索引=2/5"))
        assertTrue(result.contains("字幕行索引=41"))
        assertFalse(result.contains("must be omitted"))
    }

    @Test
    fun clipForAiKeepsVerifierInstructionAndShortensLongParameterLists() {
        val args=(1..17).joinToString(", "){"androidx.compose.runtime.Composer arg$it"}
        val value="PlayerScreenKt.PlayerScreen($args) failed to verify: [0x4440] copy1 v2<-v311 type=Reference: CoroutineScope"
        val clipped=AppLogAiReport.clipForAi(value,180)
        assertTrue(clipped.contains("PlayerScreen"))
        assertTrue(clipped.contains("17 params"))
        assertTrue(clipped.contains("failed to verify:"))
        assertTrue(clipped.contains("copy1 v2<-v311"))
    }



    @Test
    fun fullSingleIncidentReportPreservesEntireStoredStackAndCorrelatedContext() {
        val primaryDetails = ("at io.translatetube.local.playback.PlayerManager.open(PlayerManager.kt:88)\\n")
            .repeat(420) + "PRIMARY_STACK_END_MARKER"
        val target = AppLogRecord(
            timestampMs = 90_000L,
            level = "FATAL",
            tag = "PlayerManager",
            message = "视频播放页崩溃",
            details = primaryDetails,
            thread = "main",
            eventId = "full-primary",
            sessionId = "full-session",
            sequence = 9L,
            operationId = "full-operation",
            errorClass = "java.lang.VerifyError",
            rootCauseMessage = "Verifier rejected player bytecode",
            firstAppStackFrame = "io.translatetube.local.playback.PlayerManager.open(PlayerManager.kt:88)",
            diagnosticContext = mapOf(
                "当前处理阶段" to "PLAYING",
                "设备" to "HUAWEI COR-AL10",
                "Android" to "9 (API 28)",
            ),
            exceptionSnapshot = AppLogExceptionSnapshot(
                type = "java.lang.VerifyError",
                message = "Verifier rejected player bytecode",
                stackFrames = listOf(
                    AppLogStackFrame(
                        "io.translatetube.local.playback.PlayerManager",
                        "open",
                        "PlayerManager.kt",
                        88,
                    ),
                ),
            ),
        )
        val related = target.copy(
            timestampMs = 89_000L,
            level = "INFO",
            tag = "PlaybackPipeline",
            message = "开始准备播放",
            details = "RELATED_CONTEXT_FULL_DETAIL",
            eventId = "full-context",
            sequence = 8L,
        )

        val report = AppLogAiReport.renderIncidentFull(
            records = listOf(related, target),
            target = target,
            exportedAt = "now",
            appVersion = "1.0.11 (efb32d1)",
            device = "HUAWEI COR-AL10",
            androidVersion = "9 (API 28)",
            writerHealth = AppLogWriterHealth(0L, 0L, "", 90_000L, true),
        )

        assertTrue(report.length > 4_000)
        assertTrue(report.contains("elephant-app-log/incident-full-v1"))
        assertTrue(report.contains("PRIMARY_STACK_END_MARKER"))
        assertTrue(report.contains("RELATED_CONTEXT_FULL_DETAIL"))
        assertTrue(report.contains("PlayerManager.kt"))
        assertTrue(report.contains("\"selectedIncident\":true"))
        assertTrue(report.contains("\"selectedIncident\":false"))
        assertFalse(report.contains("已压缩"))
    }

}
