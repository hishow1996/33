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
    fun unknownFailureIsExplicitlyMarkedAsInsufficientEvidence() {
        val diagnosis = AppLogDiagnostics.analyze(
            record("SomeModule", "操作没有成功", "只有普通提示，没有异常类型、状态码或调用栈。"),
        )
        assertEquals("根因信息不足", diagnosis.category)
        assertEquals("证据不足", diagnosis.confidence)
        assertTrue(diagnosis.nextSteps.isNotEmpty())
    }
}
