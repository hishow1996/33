package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroMixedLanguageTextPolicyTest {
    @Test
    fun insertsLanguageBoundariesWithoutChangingChineseOrEnglishWords() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("使用YouTube播放ChatGPT生成的内容")

        assertEquals("使用 You Tube 播放 Chat gee pee tee 生成的内容", prepared.text)
        assertEquals(2, prepared.englishWordCount)
        assertEquals(0, prepared.expandedInitialismCount)
        assertEquals(2, prepared.expandedProperNameCount)
    }

    @Test
    fun expandsKnownStandaloneInitialismsAsEnglishLetterNames() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("AI调用API，并检查GPU和CPU")

        assertEquals("ay eye 调用 ay pee eye，并检查 gee pee you 和 see pee you", prepared.text)
        assertEquals(4, prepared.expandedInitialismCount)
    }

    @Test
    fun expandsInitialismsCaseInsensitivelyButNeverInsideOtherWords() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "api和gpu可以与OpenAI协作，again不是缩写，ChatGPT也不是普通GPT单词",
        )

        assertEquals(
            "ay pee eye 和 gee pee you 可以与 Open ay eye 协作， again 不是缩写， Chat gee pee tee 也不是普通 gee pee tee 单词",
            prepared.text,
        )
        assertEquals(3, prepared.expandedInitialismCount)
        assertEquals(2, prepared.expandedProperNameCount)
    }

    @Test
    fun leavesUnlistedAndAmbiguousEnglishTermsUntouched() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "使用Android、Python、Kokoro、Sherpa-ONNX以及SQL",
        )

        assertEquals("使用 Android、 Python、 Kokoro、 Sherpa-ONNX 以及 SQL", prepared.text)
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
    fun keepsPunctuationAndNumbersWhileAddingSpeechBoundaries() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("第 3 章：AI  模型，支持 ChatGPT！")

        assertEquals("第 3 章： ay eye 模型，支持 Chat gee pee tee！", prepared.text)
    }
}
