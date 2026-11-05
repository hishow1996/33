package io.translatetube.local.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationQualityPolicyTest {
    @Test
    fun detectsSourceEchoesEvenWhenOnlyTrailingPunctuationDiffers() {
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Hello world", " hello   WORLD. ", "en"))
        assertTrue(SubtitleTranslationQualityPolicy.isSourceEcho("Bonjour à tous!", "bonjour à tous", "fr"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("Hello world", "你好世界。", "en"))
    }

    @Test
    fun rejectsResponsesThatRemainInTheSourceLanguageInsteadOfChinese() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I think we should leave now.",
                "I believe we need to go now.",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "こんにちは、元気ですか。",
                "こんにちは、元気ですか。",
                "ja",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "안녕하세요, 만나서 반가워요.",
                "안녕하세요, 만나서 반가워요.",
                "ko",
                "zh-CN",
            ),
        )
    }

    @Test
    fun reportsSpecificReasonsForChineseTargetLanguageMismatch() {
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "I think we should leave now.",
                "I think we should leave now.",
                "en",
                "zh-CN",
            ).orEmpty().contains("未检测到汉字"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "今日はいい天気ですね。",
                "こんにちは、元気ですか。",
                "ja",
                "zh-CN",
            ).orEmpty().contains("日文假名占主导"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "오늘은 날씨가 좋네요.",
                "안녕하세요, 만나서 반가워요.",
                "ko",
                "zh-CN",
            ).orEmpty().contains("韩文音节占主导"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "I think we should leave now.",
                "当然，I think we should leave now.",
                "en",
                "zh-CN",
            ).orEmpty().contains("外语文本"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.targetLanguageMismatchReason(
                "Hello",
                "……",
                "en",
                "zh-CN",
            ).orEmpty().contains("标点/符号"),
        )
    }

    @Test
    fun acceptsChineseOutputAndLegitimateUntranslatedNamesAndNumbers() {
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Hello there.",
                "你好。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "J. K. Rowling",
                "J. K. Rowling",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "2026",
                "2026",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "你好",
                "你好",
                "zh-Hans",
                "zh-CN",
            ),
        )
    }

    @Test
    fun rejectsChinesePrefacesThatLeaveMostOfTheSentenceInEnglish() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I think we should leave now.",
                "当然，I think we should leave now.",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "We should probably wait a little longer.",
                "好的，我们 should probably wait a little longer.",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun acceptsChineseOutputThatRetainsSourceProperNamesAndModelNames() {
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Google Gemini is useful, and Spider-Man is popular.",
                "Google Gemini 很实用，Spider-Man 也很受欢迎。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The new iPhone model is available.",
                "新款 iPhone 已经上市。",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun onlyAnUnchangedProperNameIsExemptFromChineseTargetLanguageChecks() {
        assertFalse(SubtitleTranslationQualityPolicy.isTargetLanguageMismatch("Elon Musk", "Elon Musk", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isTargetLanguageMismatch("Elon Musk", "David Beckham", "en", "zh-CN"))
    }

    @Test
    fun doesNotMarkChineseSourceCaptionsAsMissingTranslations() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("正在翻译", "正在翻译", "zh-Hans"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("你好", "你好", "cmn"))
    }

    @Test
    fun blanksAreHandledAsMissingByTheCallerRatherThanEchoes() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("", "hello", "en"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("hello", " ", "en"))
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho(null, "hello", "en"))
    }
    @Test
    fun acceptsNumbersUrlsAndEmailAddressesWhenTheyRemainUnchanged() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("2026", "2026", "en"))
        assertFalse(
            SubtitleTranslationQualityPolicy.isSourceEcho(
                "https://example.com/watch?v=42",
                "https://example.com/watch?v=42",
                "en",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isSourceEcho(
                "info@example.com",
                "info@example.com",
                "en",
            ),
        )
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("— …", "— …", "en"))
    }

    @Test
    fun ignoresInternalPunctuationDifferencesWhenDetectingAnUntranslatedEcho() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isSourceEcho(
                "Hello, world!",
                "hello world",
                "en",
            ),
        )
    }



    @Test
    fun shortTermsAcronymsAndNamesMayStayUnchanged() {
        listOf("H-1B", "NASA", "COVID-19", "iPhone 15", "Elon Musk", "Silicon Valley", "OK", "J. K. Rowling", "YouTube").forEach {
            assertFalse("'$it'", SubtitleTranslationQualityPolicy.isSourceEcho(it, it, "en"))
        }
    }

    @Test
    fun diagnosticEvidenceExplainsHanScriptEchoAndSpeakabilityChecks() {
        val evidence = SubtitleTranslationQualityPolicy.diagnosticEvidence(
            sourceText = "Hello world",
            translatedText = "こんにちは",
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )
        assertTrue(evidence.contains("sourceLanguage=en"))
        assertTrue(evidence.contains("targetLanguage=zh-CN"))
        assertTrue(evidence.contains("han=0"))
        assertTrue(evidence.contains("kana=5"))
        assertTrue(evidence.contains("sourceEcho=false"))
        assertTrue(evidence.contains("targetLanguageMismatch=true"))
        assertTrue(evidence.contains("speakable=true"))
    }

    @Test
    fun acceptsConfirmedShortNamesOnlyWhenWordCountAndSpellingStillMatch() {
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John", "John", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Sarah", "Sara", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Sarah Smith", "Sara Smith", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("J. K. Rowling", "J. K. Rowling", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("(John):", "“John”", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("[Sarah]", "(Sara)", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Good Morning", "Good Morning", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("THANK YOU", "THANK YOU", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John", "David", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Sarah", "David", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John Smith", "John David", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("John Smith", "John", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Goodbye", "Goodbye", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Wait", "Wait", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue("Hello", "Hello", "en", "zh-CN"))
    }

    @Test
    fun ordinaryWordsAndPhrasesReturnedUnchangedAreStillFailedTranslations() {
        listOf("Hello", "Thank you very much", "I think so.", "Good morning everyone", "Good Morning", "THANK YOU").forEach {
            assertTrue("'$it'", SubtitleTranslationQualityPolicy.isSourceEcho(it, it, "en"))
            assertFalse(
                "'$it' must not be accepted as an untranslated name",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    sourceText = it,
                    translatedText = it,
                    sourceLanguage = "en",
                    targetLanguage = "zh-CN",
                    acceptShortUnchanged = true,
                ),
            )
        }
    } 
    @Test
    fun rejectsCommonShortWordsAndCapitalizedPhrasesAsUntranslatedNames() {
        listOf(
            "NO WAY",
            "No Way",
            "Hello World",
            "No Problem",
            "CALL 911 NOW",
            "Taxi",
            "TAXI",
            "Goodbye",
            "Good Morning",
        ).forEach { source ->
            assertTrue("'$source' should be treated as an untranslated source echo",
                SubtitleTranslationQualityPolicy.isSourceEcho(source, source, "en"))
            assertTrue("'$source' should fail Chinese target-language validation",
                SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(source, source, "en", "zh-CN"))
            assertFalse("'$source' must not use the short-name exception",
                SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(source, source, "en", "zh-CN"))
            assertFalse("'$source' must not be accepted for dubbing after retry",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    source, source, "en", "zh-CN", acceptShortUnchanged = true,
                ))
        }
    }

    @Test
    fun rejectsShortEnglishFragmentsInsideOtherwiseChineseTranslations() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Hello there, how are you?",
                "你好 hello",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I do not agree.",
                "我 no",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Thank you very much.",
                "这是 thanks",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The new iPhone model is available.",
                "新款 iPhone 已经上市。",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun exactAcronymsAndProperNamesRemainEligibleButAreNotExpandedAsPhrases() {
        listOf("NASA", "TV", "H-1B", "COVID-19", "iPhone 15", "Elon Musk", "Silicon Valley", "J. K. Rowling", "YouTube", "OK").forEach {
            assertFalse("'$it' should remain eligible as a retained term",
                SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(it, it, "en", "zh-CN"))
        }
    } 
    @Test
    fun rejectsUnknownConsonantWordsInsteadOfInventingChineseInterjections() {
        assertFalse(SubtitleInterjectionPolicy.chineseFor("Bff", "en", "zh") != null)
        assertFalse(SubtitleInterjectionPolicy.chineseFor("Smh", "en", "zh") != null)
        assertFalse(SubtitleInterjectionPolicy.chineseFor("Tmr", "en", "zh") != null)
        assertTrue(SubtitleInterjectionPolicy.chineseFor("Shhhh", "en", "zh") == "嘘")
        assertTrue(SubtitleInterjectionPolicy.chineseFor("Brrr", "en", "zh") == "嘶")
        assertTrue(SubtitleInterjectionPolicy.chineseFor("Hmmmm", "en", "zh") == "嗯")
    }

    @Test
    fun rejectsAssistantMetaRepliesButPreservesGenuineDialogueWithTheSameMeaning() {
        val source = "Hello, how are you?"
        val metaReplies = listOf(
            "请提供需要翻译的原文。",
            "抱歉，我无法帮助你翻译这段内容。",
            "作为一个 AI 语言模型，我无法完成这个请求。",
            "这句话的意思是你好。",
        )
        metaReplies.forEach { output ->
            assertTrue(
                "'$output' should be rejected as model meta text",
                SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(source, output, "en", "zh-CN"),
            )
            assertFalse(
                "'$output' must not enter subtitle caches or dubbing",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    source, output, "en", "zh-CN", acceptShortUnchanged = true,
                ),
            )
        }

        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Please provide the original text to be translated.",
                "请提供需要翻译的原文。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The speaker says they are unable to translate this.",
                "我无法翻译这段文本。",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "This dialogue discusses an AI language model.",
                "作为一个 AI 语言模型，我可以回答。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "As an AI language model, I cannot answer this.",
                "作为一个 AI 语言模型，我无法回答这个问题。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "This sentence means something important.",
                "这句话的意思是很重要的事情。",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun rejectsServiceStatusRepliesButPreservesDialogueAboutTheSameFailure() {
        val source = "I think we should leave now."
        val statusReplies = listOf(
            "请求过于频繁，请稍后重试。",
            "服务器暂时不可用，请稍后重试。",
            "API 密钥无效，请检查配置。",
            "网络连接失败，请检查网络后重试。",
        )
        statusReplies.forEach { output ->
            assertTrue(
                "'$output' should be rejected as service status text",
                SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(source, output, "en", "zh-CN"),
            )
            assertFalse(
                "'$output' must not be accepted as a subtitle translation",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    source, output, "en", "zh-CN", acceptShortUnchanged = true,
                ),
            )
        }

        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The request limit is too high, so try again later.",
                "请求过于频繁，请稍后重试。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The server is unavailable; try again later.",
                "服务器暂时不可用，请稍后重试。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The API key is invalid. Check the configuration.",
                "API 密钥无效，请检查配置。",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun doesNotTreatInventedUppercaseWordsAsRetainedNamesOrAcronyms() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I need some help.",
                "你好 BOGUS",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "We should leave now.",
                "当然，RANDOM WORDS",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The answer is clear.",
                "你好 FooBar",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "NASA is a space agency.",
                "NASA 是一家航天机构。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "The new iPhone model is available.",
                "新款 iPhone 已经上市。",
                "en",
                "zh-CN",
            ),
        )
    }
}
