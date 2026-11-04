package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogCallSitePolicyTest {
    @Test
    fun selectsFirstApplicationFrameAndKeepsShortCallerChain() {
        val frames = arrayOf(
            StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1),
            StackTraceElement("io.translatetube.local.util.AppLogStore", "appendAt", "AppLogStore.kt", 700),
            StackTraceElement("io.translatetube.local.translate.TranslationEngine", "translateBatch", "TranslationEngine.kt", 214),
            StackTraceElement("io.translatetube.local.translate.TranslationEngine", "translate", "TranslationEngine.kt", 88),
            StackTraceElement("okhttp3.RealCall", "execute", "RealCall.kt", 30),
        )

        val fields = AppLogCallSitePolicy.diagnosticFields(frames)

        assertEquals(
            "io.translatetube.local.translate.TranslationEngine.translateBatch(TranslationEngine.kt:214)",
            fields["日志调用位置"],
        )
        assertEquals("io.translatetube.local.translate.TranslationEngine", fields["日志调用类"])
        assertEquals("translateBatch", fields["日志调用方法"])
        assertEquals("TranslationEngine.kt", fields["日志调用文件"])
        assertEquals("214", fields["日志调用行号"])
        assertTrue(fields["日志调用链（最多8帧）"].orEmpty().contains("translate("))
        assertFalse(fields["日志调用链（最多8帧）"].orEmpty().contains("okhttp3.RealCall"))
    }

    @Test
    fun returnsExplicitMissingStateWhenNoApplicationFrameExists() {
        val fields = AppLogCallSitePolicy.diagnosticFields(
            arrayOf(StackTraceElement("java.lang.Thread", "getStackTrace", "Thread.java", 1)),
        )

        assertEquals("未找到应用内调用帧", fields["日志调用位置采集状态"])
        assertEquals("未采集", fields["日志调用位置"])
        assertEquals("未采集", fields["日志调用链（最多8帧）"])
    }

    @Test
    fun limitsCallerChainToEightFrames() {
        val frames = Array(20) { index ->
            StackTraceElement(
                "io.translatetube.local.feature.Feature$index",
                "run",
                "Feature.kt",
                index + 1,
            )
        }
        val callSite = AppLogCallSitePolicy.from(frames)

        assertEquals("io.translatetube.local.feature.Feature0", callSite?.className)
        assertEquals(8, callSite?.chain?.size)
    }
}
