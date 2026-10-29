package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackBlockedHintPolicyTest {
    private fun hint(
        waitingTranscription: Boolean = false,
        dubbingOn: Boolean = true,
        waitingDubbing: Boolean = false,
        hasErrors: Boolean = false,
        phaseError: Boolean = false,
        engine: String = "系统TTS",
        detail: String? = null,
    ) = PlaybackBlockedHintPolicy.hintFor(
        waitingForTranscriptionEngine = waitingTranscription,
        dubbingOn = dubbingOn,
        waitingForDubbingEngine = waitingDubbing,
        dubbingHasErrors = hasErrors,
        phaseIsError = phaseError,
        ttsEngineName = engine,
        failureDetail = detail,
    )

    @Test
    fun nothingBlockedMeansNoHint() {
        assertNull(hint())
    }

    @Test
    fun missingTranscriptionEngineIsExplainedAndOffersNoOriginalOnly() {
        val h = hint(waitingTranscription = true)!!
        assertTrue(h.message.contains("转写引擎"))
        assertFalse(h.offersOriginalOnly)
    }

    @Test
    fun missingDubbingEngineNamesTheEngineAndOffersOriginalOnly() {
        val h = hint(waitingDubbing = true, engine = "Edge TTS")!!
        assertTrue(h.message.contains("Edge TTS"))
        assertTrue(h.offersOriginalOnly)
    }

    @Test
    fun dubbingFailureIncludesTheReasonAndOffersOriginalOnly() {
        val h = hint(hasErrors = true, detail = "系统 TTS 合成失败：没有中文语音数据")!!
        assertTrue(h.message.startsWith("配音语音失败：系统 TTS 合成失败"))
        assertTrue(h.offersOriginalOnly)
    }

    @Test
    fun dubbingFailureWithoutDetailStillReadsNaturally() {
        val h = hint(hasErrors = true, detail = "  ")!!
        assertTrue(h.message.startsWith("配音语音失败：所选配音引擎无法生成语音"))
    }

    @Test
    fun longDetailIsTruncatedAndWhitespaceCollapsed() {
        val h = hint(hasErrors = true, detail = "错误\n\n" + "x".repeat(300))!!
        assertTrue(h.message.contains("错误 xxx"))
        assertTrue(h.message.length < 260)
        assertTrue(h.message.contains("…"))
    }

    @Test
    fun withDubbingOffOnlyTheTranscriptionGateStillBlocks() {
        assertNull(hint(dubbingOn = false, waitingDubbing = true, hasErrors = true, phaseError = true))
        assertNotNull(hint(dubbingOn = false, waitingTranscription = true))
    }

    @Test
    fun processingErrorIsExplainedWithoutOriginalOnlyShortcut() {
        val h = hint(phaseError = true, detail = "网络超时")!!
        assertTrue(h.message.contains("网络超时"))
        assertFalse(h.offersOriginalOnly)
    }

    @Test
    fun transcriptionGateWinsOverDubbingProblems() {
        val h = hint(waitingTranscription = true, hasErrors = true)!!
        assertTrue(h.message.contains("转写引擎"))
    }

    @Test
    fun blankEngineNameFallsBackToAGenericLabel() {
        val h = hint(waitingDubbing = true, engine = "  ")!!
        assertEquals(true, h.message.contains("配音引擎 还没准备好"))
    }
}
