package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InProgressResumeHintCodecTest {
    @Test
    fun synthesizingAndReadyBothRestoreAsSynthesizing() {
        listOf(Phase.SYNTHESIZING, Phase.READY).forEach { phase ->
            val raw = InProgressResumeHintCodec.encode(phase, ProcessingFlow.YOUTUBE_SUBTITLES, 120, 120)
            val hint = InProgressResumeHintCodec.decode(raw)
            assertEquals(Phase.SYNTHESIZING, hint?.phase)
            assertEquals(ProcessingFlow.YOUTUBE_SUBTITLES, hint?.processingFlow)
            assertEquals(120, hint?.totalLines)
            assertEquals(120, hint?.translatedLines)
        }
    }

    @Test
    fun earlierPhasesRestoreAsTranslating() {
        listOf(Phase.PARSING, Phase.TRANSCRIBING, Phase.TRANSLATING).forEach { phase ->
            val raw = InProgressResumeHintCodec.encode(phase, ProcessingFlow.WEB_SPEECH_RECOGNITION, 40, 12)
            val hint = InProgressResumeHintCodec.decode(raw)
            assertEquals(Phase.TRANSLATING, hint?.phase)
            assertEquals(ProcessingFlow.WEB_SPEECH_RECOGNITION, hint?.processingFlow)
            assertEquals(40, hint?.totalLines)
            assertEquals(12, hint?.translatedLines)
        }
    }

    @Test
    fun translatedCountIsClampedToTotal() {
        val hint = InProgressResumeHintCodec.decode("S|AUTO|10|99")
        assertEquals(10, hint?.translatedLines)
        assertEquals(0, InProgressResumeHintCodec.decode("S|AUTO|10|-5")?.translatedLines)
    }

    @Test
    fun malformedHintsAreIgnored() {
        assertNull(InProgressResumeHintCodec.decode(null))
        assertNull(InProgressResumeHintCodec.decode(""))
        assertNull(InProgressResumeHintCodec.decode("S|AUTO|10"))
        assertNull(InProgressResumeHintCodec.decode("S|AUTO|10|5|extra"))
        assertNull(InProgressResumeHintCodec.decode("S|NO_SUCH_FLOW|10|5"))
        assertNull(InProgressResumeHintCodec.decode("S|AUTO|zero|5"))
        assertNull(InProgressResumeHintCodec.decode("S|AUTO|0|0"))
        assertNull(InProgressResumeHintCodec.decode("S|AUTO|10|many"))
    }
}
