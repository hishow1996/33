package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogDiagnosticsTest {
    private fun record(
        tag: String,
        message: String,
        details: String,
        level: String = "ERROR",
    ) = AppLogRecord(
        timestampMs = 1_800_000_000_000L,
        level = level,
        tag = tag,
        message = message,
        details = details,
        thread = "test",
    )

    @Test
    fun notificationInflationCrashIsRecognizedWithoutPretendingItsRootCauseIsKnown() {
        val diagnosis = AppLogDiagnostics.analyze(
            record(
                "UncaughtException",
                "android.app.RemoteServiceException: Bad notification(tag=null, id=1001) posted from package io.translatetube.local: Couldn't inflate contentViewsjava.util.ConcurrentModificationException",
                "异常根因链：\\n#0 android.app.RemoteServiceException: Bad notification(tag=null, id=1001): Couldn't inflate contentViewsjava.util.ConcurrentModificationException\\n" +
                    "完整调用堆栈：\\nandroid.app.RemoteServiceException\\n\\tat android.app.ActivityThread\$H.handleMessage(ActivityThread.java:2153)",
                level = "FATAL",
            ),
        )
        assertEquals("Android 通知内容视图膨胀失败", diagnosis.category)
        assertTrue(diagnosis.finding.contains("通知 ID=1001"))
        assertTrue(diagnosis.confidence.contains("待验证"))
        assertTrue(diagnosis.finding.contains("不能仅凭这一行断定是通知 ID 冲突"))
        assertTrue(diagnosis.nextSteps.any { it.contains("NotificationDiagnostics") })
    }

    @Test
    fun nuxtBeforeEachErrorDoesNotBlameTheAppWithoutProof() {
        val diagnosis = AppLogDiagnostics.analyze(
            record(
                "WebPlayerConsole",
                "网页播放器 JavaScript 报错",
                "错误内容：[nuxt] error caught during app initialization TypeError: Cannot read property 'beforeEach' of undefined",
            ),
        )
        assertEquals("网页端 Nuxt/路由初始化异常", diagnosis.category)
        assertTrue(diagnosis.confidence.contains("待验证"))
        assertTrue(diagnosis.nextSteps.any { it.contains("系统浏览器") })
    }

    @Test
    fun http429IsRecognizedFromTheRecordedStatusCode() {
        val diagnosis = AppLogDiagnostics.analyze(
            record("CloudTranscription", "云端转写失败", "请求阶段：提交；HTTP 状态码：429"),
        )
        assertEquals("HTTP 429：请求频率受限", diagnosis.category)
        assertTrue(diagnosis.finding.contains("限制请求频率"))
    }

    @Test
    fun audioTrackFailureProvidesAudioSpecificChecks() {
        val diagnosis = AppLogDiagnostics.analyze(
            record("DubbingEngine", "音频播放失败", "异常根因链：\n#0 java.lang.IllegalStateException: AudioTrack STATE_UNINITIALIZED"),
        )
        assertEquals("Android 音频输出初始化/写入异常", diagnosis.category)
        assertTrue(diagnosis.nextSteps.any { it.contains("采样率") })
    }

    @Test
    fun indexOutOfBoundsIsNotMisclassifiedAsGenericTtsFailure() {
        val diagnosis = AppLogDiagnostics.analyze(
            record("DubbingEngine", "第 1 句配音合成失败", "异常根因链：\n#0 java.lang.ArrayIndexOutOfBoundsException: length=100; index=100"),
        )
        assertEquals("数组/缓冲区索引越界", diagnosis.category)
    }

    @Test
    fun businessOutcomeWithoutThrowableIsNotMisclassifiedAsAnExceptionFailure() {
        val diagnosis = AppLogDiagnostics.analyze(
            record(
                "WebSourceDetection",
                "网页媒体源检测等待结束，仍没有确认可直接播放的候选源",
                "候选源总数：3；已验证可访问的候选源数：0。",
                level = "WARN",
            ).copy(
                diagnosticContext = mapOf(
                    "日志调用位置" to "io.translatetube.local.ui.screens.WebVideoScreen.detectSources(WebVideoScreen.kt:1350)",
                    "异常对象采集状态" to "普通业务状态日志未提供 Throwable",
                    "候选源总数" to "3",
                    "已验证可访问的候选源数" to "0",
                ),
            ),
        )

        assertEquals("业务状态未达成（未抛出异常）", diagnosis.category)
        assertTrue(diagnosis.confidence.contains("业务结果与判定位置已记录"))
        assertTrue(diagnosis.finding.contains("没有 Throwable 不等于程序抛出异常"))
        assertTrue(diagnosis.nextSteps.any { it.contains("DRM") })
    }

    @Test
    fun failureWithoutThrowableIsExplicitlyMarkedAsMissingEvidence() {
        val diagnosis = AppLogDiagnostics.analyze(
            record("SomeModule", "操作没有成功", "只有普通提示，没有异常类型、状态码或调用栈。"),
        )
        assertEquals("失败事件缺少异常对象", diagnosis.category)
        assertEquals("调用位置和异常根因均缺失", diagnosis.confidence)
        assertTrue(diagnosis.finding.contains("不能代表异常真正抛出位置"))
        assertTrue(diagnosis.nextSteps.any { it.contains("Throwable") })
    }

    @Test
    fun failureWithoutThrowableUsesCallSiteOnlyAsTheLoggingBranchLocation() {
        val diagnosis = AppLogDiagnostics.analyze(
            record("SomeModule", "操作没有成功", "失败结果：false").copy(
                diagnosticContext = mapOf(
                    "日志调用位置" to "io.translatetube.local.feature.TaskRunner.run(TaskRunner.kt:128)",
                    "异常对象采集状态" to "调用方未提供 Throwable",
                ),
            ),
        )

        assertEquals("失败事件缺少异常对象", diagnosis.category)
        assertEquals("日志调用位置已记录，根因待补充", diagnosis.confidence)
        assertTrue(diagnosis.evidence.contains("TaskRunner.kt:128"))
        assertTrue(diagnosis.finding.contains("不能代表异常真正抛出位置"))
        assertTrue(diagnosis.nextSteps.any { it.contains("源码分支") })
    }
    @Test
    fun structuredHttpContextIsUsedForClassificationAndEvidence() {
        val diagnosis = AppLogDiagnostics.analyze(
            record(
                "CloudTranscription",
                "云端转写失败",
                "请求未成功，但没有把状态码拼入文本。",
            ).copy(
                diagnosticContext = mapOf(
                    "HTTP 状态码" to "429",
                    "请求阶段" to "提交转写任务",
                    "重试次数" to "2",
                ),
                httpStatusCode = 429,
            ),
        )

        assertEquals("HTTP 429：请求频率受限", diagnosis.category)
        assertTrue(diagnosis.evidence.contains("HTTP 状态码：429"))
        assertTrue(diagnosis.nextSteps.any { it.contains("Retry-After") })
    }

}
