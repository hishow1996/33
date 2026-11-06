package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroMixedLanguageTextPolicyTest {
    @Test
    fun insertsLanguageBoundariesWithoutChangingOrdinaryEnglishWords() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare("使用YouTube播放ChatGPT生成的内容")

        assertEquals("使用 You Tube 播放 Chat gee pee tee 生成的内容", prepared.text)
        assertEquals(2, prepared.englishWordCount)
        assertEquals(2, prepared.expandedProperNameCount)
    }

    @Test
    fun expandsKnownStandaloneInitialismsButDoesNotGuessAmbiguousOnes() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "AI调用API，并检查GPU、CPU、ASR和TTS，SQL与JSON保留原样",
        )

        assertEquals(
            "ay eye 调用 ay pee eye，并检查 gee pee you、 see pee you、 ay ess ar 和 tee tee ess， SQL 与 JSON 保留原样",
            prepared.text,
        )
        assertEquals(6, prepared.expandedInitialismCount)
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
    fun expandsCommonTechnologyAndProductNames() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "使用GitHub、GitLab、TikTok、JavaScript和SenseVoice",
        )

        assertEquals(
            "使用 Git Hub、 Git Lab、 Tick Tock、 Java Script 和 Sense Voice",
            prepared.text,
        )
        assertEquals(5, prepared.expandedProperNameCount)
    }

    @Test
    fun readsCommonModelVersionsAndDeviceIdentifiersAsEnglish() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "GPT-4、GPT-4.1、GPT-4o、Wi-Fi 6、USB-C、H.264和IPv6",
        )

        assertEquals(
            "gee pee tee four、 gee pee tee four point one、 gee pee tee four oh、 why fie six、 you ess bee see、 aitch two six four 和 eye pee vee six",
            prepared.text,
        )
        assertEquals(7, prepared.expandedModelIdentifierCount)
    }

    @Test
    fun readsPythonAndroidAndGraphicsCardVersions() {
        val prepared = KokoroMixedLanguageTextPolicy.prepare(
            "Python 3.12、Android 14、RTX 4090 Ti",
        )

        assertEquals(
            "Python three point twelve、 Android fourteen、 ar tee ex forty ninety tie",
            prepared.text,
        )
        assertEquals(3, prepared.expandedModelIdentifierCount)
    }

    @Test
    fun doesNotExpandInitialismsWhenSpeechPayloadWouldExceedCueLimit() {
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
