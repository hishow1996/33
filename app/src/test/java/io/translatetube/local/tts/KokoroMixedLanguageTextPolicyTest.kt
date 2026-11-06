package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroMixedLanguageTextPolicyTest {
    @Test
    fun insertsLanguageBoundariesWithoutChangingChineseOrEnglishWords() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("使用YouTube播放ChatGPT生成的内容")

        assertEquals("使用 YouTube 播放 Chat G P T 生成的内容", prepared.text)
        assertEquals(3, prepared.englishWordCount)
        assertEquals(0, prepared.expandedInitialismCount)
        assertEquals(1, prepared.expandedProperNameCount)
    }

    @Test
    fun expandsKnownStandaloneInitialismsAsEnglishLetterNames() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("AI调用API，并检查GPU和CPU")

        assertEquals("A I 调用 A P I，并检查 G P U 和 C P U", prepared.text)
        assertEquals(4, prepared.expandedInitialismCount)
    }

    @Test
    fun expandsInitialismsCaseInsensitivelyButNeverInsideOtherWords() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "api和gpu可以与OpenAI协作，again不是缩写，ChatGPT也不是普通GPT单词",
        )

        assertEquals(
            "A P I 和 G P U 可以与 Open A I 协作，again 不是缩写，Chat G P T 也不是普通 G P T 单词",
            prepared.text,
        )
        assertEquals(3, prepared.expandedInitialismCount)
        assertEquals(2, prepared.expandedProperNameCount)
    }

    @Test
    fun leavesUnlistedAndAmbiguousEnglishTermsUntouched() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "使用GitHub、Python、Kokoro、Sherpa-ONNX以及SQL",
        )

        assertEquals("使用 GitHub、 Python、 Kokoro、 Sherpa-ONNX 以及 SQL", prepared.text)
        assertEquals(0, prepared.expandedInitialismCount)
        assertEquals(0, prepared.expandedProperNameCount)
    }

    @Test
    fun doesNotExpandInitialismsWhenTheSpeechPayloadWouldExceedTheCueLimit() {
        val original = "AI".repeat(400)
        val prepared = KokoroMixedLanguageTextPolicy.prepare(original)

        assertEquals(original, prepared.text)
        assertTrue(prepared.text.codePointCount(0, prepared.text.length) <= TtsTextPolicy.MAX_CODE_POINTS)
        assertEquals(0, prepared.expandedInitialismCount)
    }

    @Test
    fun keepsPunctuationAndNumbersAndOnlyCollapsesWhitespace() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("第 3 章：AI  模型，支持 ChatGPT！")

        assertEquals("第 3 章：A I 模型，支持 Chat G P T！", prepared.text)
    }
}
