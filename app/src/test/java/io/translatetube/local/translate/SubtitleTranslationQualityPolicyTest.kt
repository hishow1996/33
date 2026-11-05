package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationQualityPolicyTest {
    @Test
    fun detectsPolarityChangesAndPreservesAffirmativeIdioms() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I don't know the answer.", "我知道答案。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I know the answer.", "我不知道答案。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "No problem.", "没问题。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "That's not uncommon.", "这并不少见。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "You're welcome.", "不客气。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "You should try it.", "不妨试一试。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Reviews from people who have actually followed this route are pouring in.",
            "这条路线实际体验者的评价正不断涌现。", "en", "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "Reviews from people who have actually followed this route are pouring in.",
            "这条路线实际体验者的评价正不断涌现。", "en", "zh-CN",
        ))
    }

    @Test
    fun detectsExplicitModalityReversalsButNotEpistemicMustBe() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "You must leave now.", "你可能现在离开。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_MODALITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "She might come tomorrow.", "她明天必须来。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_MODALITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "You should leave now.", "你禁止现在离开。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_MODALITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "She may join us tomorrow.", "她明天必须加入我们。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_MODALITY_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "It must be John.", "他可能是约翰。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "You must leave now.", "你必须现在离开。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "You may leave now.", "你可以现在离开。", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun detectsOnlyUnambiguousGenderedPronounReversals() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "She said she was ready.", "他说他准备好了。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_ENTITY_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "He said he was ready.", "他说他准备好了。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "They said they were ready.", "他们说他们准备好了。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "She said she was ready.", "对方说已经准备好了。", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun detectsExplicitTimeConditionAndCausalRelationReversals() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "The train arrives before noon.", "火车中午之后到达。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I stayed because it was raining.", "我留下来，尽管当时正在下雨。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Unless you hurry, you will miss it.", "如果你快点，就会错过。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "She has not yet arrived.", "她已经到了。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "She has not yet arrived.", "她尚未到达。", "en", "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I waited until noon.", "我在中午之前就离开了。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "He still works here.", "他已经不在这里工作了。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I stayed because it was raining.", "因为下雨，我留了下来。", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun semanticRulesAreLimitedToKnownEnglishSourceLanguage() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Je ne sais pas.", "我知道。", "fr", "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I do not agree.", "我同意。", "auto", "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I do not agree.", "我同意。", "eng", "zh-CN",
        )!!.startsWith("SEMANTIC_POLARITY_MISMATCH"))
    }

    @Test
    fun rejectsClearNegationButDoesNotGuessFromOmittedMarkersAlone() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason("I do not agree.", "我同意。", "en", "zh-CN")!!.startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason("I do not agree.", "我不同意。", "en", "zh-CN") != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason("You must leave now.", "你现在离开。", "en", "zh-CN") != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason("If it rains, we will stay.", "下雨了，我们会留下。", "en", "zh-CN") != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason("Maybe we should wait.", "也许我们应该等一等。", "en", "zh-CN") != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason("I like this movie.", "我喜欢这部电影。", "en", "zh-CN") != null)
    }


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
    fun retainsExpandedProperNamesAcrossLongerPhrasesAndCasedScripts() {
        val retainedNames = listOf(
            Triple("New York", "New York", "en"),
            Triple("New York Times", "New York Times", "en"),
            Triple("The Hobbit", "The Hobbit", "en"),
            Triple("Martin Luther King Jr.", "Martin Luther King Jr.", "en"),
            Triple("J. K. Rowling", "J. K. Rowling", "en"),
            Triple("Александр Пушкин", "Александр Пушкин", "ru"),
            Triple("Владимир Путин", "Владимир Путин", "ru"),
            Triple("Αλέξανδρος Ωνάσης", "Αλέξανδρος Ωνάσης", "el"),
        )
        retainedNames.forEach { (source, translated, language) ->
            assertFalse(
                "'$source' should be eligible as a retained proper name",
                SubtitleTranslationQualityPolicy.isSourceEcho(source, translated, language),
            )
            assertFalse(
                "'$source' should not fail Chinese target-language validation",
                SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                    source, translated, language, "zh-CN",
                ),
            )
        }

        listOf("Hello World", "Good Morning", "My Friend John").forEach { ordinaryPhrase ->
            assertTrue(
                "'$ordinaryPhrase' must still be translated",
                SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                    ordinaryPhrase, ordinaryPhrase, "en", "zh-CN",
                ),
            )
        }
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Elon Musk", "David Beckham", "en", "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Elon Musk arrived yesterday.",
                "埃隆·马斯克昨天到了。",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "I watched The Hobbit in New York.",
                "我在纽约看了 The Hobbit。",
                "en",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isTargetLanguageMismatch(
                "Elon Musk arrived yesterday and spoke at the conference.",
                "David Beckham 昨天到达，并在会议上发表讲话。",
                "en",
                "zh-CN",
            ),
            "A substituted English name must not be hidden by a longer Chinese sentence",
        )
    }

    @Test
    fun singleWordCyrillicAndGreekNamesRequireRetryAndCommonGreetingsStayRejected() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                "Иван", "Иван", "ru", "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                "Αλέξανδρος", "Αλέξανδρος", "el", "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                "Привет", "Привет", "ru", "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                "Γεια", "Γεια", "el", "zh-CN",
            ),
        )

        val firstAttempt = SubtitleTranslationQualityPolicy.evaluateTranslation(
            "Иван", "Иван", "ru", "zh-CN",
        )
        assertFalse(firstAttempt.accepted)
        assertEquals("SHORT_NAME_RETRY_REQUIRED", firstAttempt.reasonCode)

        val confirmedRetry = SubtitleTranslationQualityPolicy.evaluateTranslation(
            "Иван", "Иван", "ru", "zh-CN", acceptShortUnchanged = true,
        )
        assertTrue(confirmedRetry.accepted)
        assertTrue(confirmedRetry.shortUntranslatedAccepted)
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

    @Test
    fun rejectsChangedExplicitNumbersButAllowsLocaleFormattingAndNaturalChineseTime() {
        val changed = SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 3 cats and 2 dogs.",
            "我有4只猫和2只狗。",
            "en",
            "zh-CN",
        )
        assertTrue(changed.orEmpty().contains("显式数字"))

        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "I have 3 cats and 2 dogs.",
                "我有3只猫和2只狗。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "La valeur est 1,5.",
                "数值是1.5。",
                "fr",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "May 5, 2024",
                "2024年5月5日",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "Meet me at 10:30.",
                "十点半见面。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "There are 3 cats.",
                "有三只猫。",
                "en",
                "zh-CN",
            ) == null,
        )
    }

    @Test
    fun acceptsThousandsSeparatorsAcrossEnglishAndEuropeanNumberFormats() {
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "I ran 1,000 meters.",
                "我跑了1000米。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "Die Entfernung beträgt 1.000 Meter.",
                "距离是1000米。",
                "de",
                "zh-CN",
            ) == null,
        )
    }

    @Test
    fun preservesNegativeNumbersAndPercentagesWithoutFalsePositives() {
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "The temperature is -5 degrees.",
                "温度是-5度。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "The temperature is -5 degrees.",
                "温度是5度。",
                "en",
                "zh-CN",
            ).orEmpty().contains("显式数字"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "The rate is 50%.",
                "比例是50%。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "The rate is 50%.",
                "比例是60%。",
                "en",
                "zh-CN",
            ).orEmpty().contains("显式数字"),
        )
    }

    @Test
    fun rejectsExplicitUnitSubstitutionButAcceptsEquivalentUnitLabels() {
        assertTrue(
            SubtitleTranslationQualityPolicy.unitContentMismatchReason(
                "The road is 5 km long.",
                "这条路长5米。",
                "en",
                "zh-CN",
            ).orEmpty().contains("单位类别不一致"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.unitContentMismatchReason(
                "The road is 5 km long.",
                "这条路长5公里。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.unitContentMismatchReason(
                "The package weighs 2 kilograms.",
                "包裹重2千克。",
                "en",
                "zh-CN",
            ) == null,
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.unitContentMismatchReason(
                "The temperature is 20 °C.",
                "温度是20华氏度。",
                "en",
                "zh-CN",
            ).orEmpty().contains("单位类别不一致"),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.unitContentMismatchReason(
                "The road is 5 km long.",
                "这条路长五。",
                "en",
                "zh-CN",
            ) == null,
        )
    }
    @Test
    fun detectsHighConfidenceActionAndDirectionReversalsWithoutRejectingCorrectMeaning() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I will come back tomorrow.", "我明天会去那里。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "The train entered the station.", "列车离开了车站。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Prices increased again.", "价格再次下降。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I bought a car.", "我卖了一辆车。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))

        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I will come back tomorrow.", "我明天会回来。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Prices increased again.", "价格再次上涨。", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun detectsClearChineseScriptMismatchButLeavesShortOrAmbiguousTextAlone() {
        assertTrue(SubtitleTranslationQualityPolicy.targetChineseScriptMismatchReason(
            "這個軟體後續會持續更新。", "zh-CN",
        )!!.startsWith("TARGET_SCRIPT_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.targetChineseScriptMismatchReason(
            "这个软件后续会持续更新。", "zh-TW",
        )!!.startsWith("TARGET_SCRIPT_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.targetChineseScriptMismatchReason(
            "这个软件后续会持续更新。", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.targetChineseScriptMismatchReason(
            "這個軟體後續會持續更新。", "zh-TW",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.targetChineseScriptMismatchReason(
            "你好。", "zh-CN",
        ) != null)
    }

    @Test
    fun detectsExtraExplicitNumbers() {
        assertTrue(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 3 cats.", "我有3只猫和5只狗。", "en", "zh-CN",
        )!!.contains("多出"))
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 3 cats.", "我有3只猫。", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun sharedUsabilityGateKeepsReadableChineseEvenWhenScriptStyleDiffers() {
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "The software is useful.",
            "這個軟體後續會持續更新。",
            "en",
            "zh-CN",
            acceptShortUnchanged = true,
        ))
        assertTrue(
            SubtitleTranslationQualityPolicy.targetChineseScriptMismatchReason(
                "這個軟體後續會持續更新。", "zh-CN",
            ) != null,
        )
    }

    @Test
    fun doesNotApplyChineseTargetScriptRulesToAlreadyChineseSourceCaptions() {
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "這是原始字幕。",
            "這是原始字幕。",
            "zh-Hant",
            "zh-CN",
        ))
    }

    @Test
    fun detectsNumericUnitPairsWhenTheNumbersAndUnitSequenceAreOtherwiseUnchanged() {
        val source = "The first route is 3 km and the second route is 5 m."
        val swapped = "第一条路线是5公里，第二条路线是3米。"
        val correct = "第一条路线是3公里，第二条路线是5米。"

        assertTrue(
            SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
                source, swapped, "en", "zh-CN",
            )!!.contains("对应关系发生变化"),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
                source, correct, "en", "zh-CN",
            ) != null,
        )
        val decision = SubtitleTranslationQualityPolicy.evaluateTranslation(
            source, swapped, "en", "zh-CN",
        )
        assertTrue(decision.accepted)
        assertEquals(null, decision.reasonCode)
        assertTrue(decision.numericUnitAssociationMismatchReason != null)
        assertTrue(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "The ticket costs 5 EUR.",
            "这张票售价5美元。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "The trip lasts 5 days.",
            "这趟旅程持续5天。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "a 2-night, 3-day trip", "一个为期两晚三天的旅行", "en", "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "a 2-night, 3-day trip", "一个为期两天三晚的旅行", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun ordinalMarkersDoNotBecomeExplicitQuantityChanges() {
        assertFalse(
            SubtitleTranslationQualityPolicy.numericContentMismatchReason(
                "第3章有2只猫。",
                "第三章有两只猫。",
                "ja",
                "zh-CN",
            ) != null,
        )
    }

    @Test
    fun detectsPolarityReversalsForJapaneseKoreanFrenchGermanSpanishAndPortuguese() {
        val examples = listOf(
            Triple("行きません。", "我去。", "ja"),
            Triple("저는 안 갑니다.", "我去。", "ko"),
            Triple("Je ne sais pas.", "我知道。", "fr"),
            Triple("Ich kann nicht kommen.", "我能来。", "de"),
            Triple("No quiero ir.", "我想去。", "es"),
            Triple("Eu não concordo.", "我同意。", "pt"),
        )
        examples.forEach { (source, translated, language) ->
            val mismatch = SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
                source, translated, language, "zh-CN",
            )
            assertTrue("$language: $source -> $translated", mismatch.orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        }
    }

    @Test
    fun preservesCommonPositiveIdiomsWithNegativeSurfaceFormsAcrossLanguages() {
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "問題ありません。", "没问题。", "ja", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "문제없습니다.", "没问题。", "ko", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Pas de problème.", "没问题。", "fr", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Kein Problem.", "没问题。", "de", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "No hay problema.", "没问题。", "es", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Sem problema.", "没问题。", "pt", "zh-CN",
        ) != null)
    }

    @Test
    fun checksMultilingualTimeCauseAndConditionRelationsConservatively() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Le train arrive avant midi.", "火车中午之后到达。", "fr", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "A menos que vengas, me iré.", "如果你来，我就离开。", "es", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Ich blieb, weil es regnete.", "尽管下雨，我还是留下了。", "de", "zh-CN",
        )!!.startsWith("SEMANTIC_RELATION_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Le train arrive avant midi.", "火车中午之前到达。", "fr", "zh-CN",
        ) != null)
    }

    @Test
    fun detectsQuestionIntentLossButDoesNotMistakeAStatementForAQuestion() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Where are you?", "我在这里。", "en", "zh-CN",
        )!!.startsWith("SEMANTIC_INTENT_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Est-ce que tu viens?", "你会来。", "fr", "zh-CN",
        )!!.startsWith("SEMANTIC_INTENT_MISMATCH"))
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "What I want is coffee.", "我想要咖啡。", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Where are you?", "你在哪里。", "en", "zh-CN",
        ) != null)
    }

    @Test
    fun sourceSymbolsAreValidNoTranslationRowsAndSoundDescriptionsMustBeTranslated() {
        assertTrue(SubtitleTranslationQualityPolicy.hasNothingToSpeak("♪♪"))
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "♪♪", null, "en", "zh-CN",
        ))
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "…", "", "en", "zh-CN",
        ))
        assertFalse(SubtitleTranslationQualityPolicy.hasNothingToSpeak("[music ]"))
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "[music ]", "[音乐]", "en", "zh-CN",
        ))
        assertFalse(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "[music ]", "[music ]", "en", "zh-CN",
        ))
        assertFalse(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "[MUSIC]", "[MUSIC]", "en", "zh-CN",
        ))
    }

    @Test
    fun sharedDecisionReturnsTheSameReasonForAllQualityCallers() {
        val decision = SubtitleTranslationQualityPolicy.evaluateTranslation(
            "I have 3 cats and 2 dogs.",
            "我有4只猫和2只狗。",
            "en",
            "zh-CN",
        )
        assertTrue(decision.accepted)
        assertEquals(null, decision.reasonCode)
        assertTrue(decision.numericMismatchReason != null)
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            "I have 3 cats and 2 dogs.",
            "我有4只猫和2只狗。",
            "en",
            "zh-CN",
        ))
    }

    @Test
    fun pairsCurrencySymbolsWrittenBeforeOrAfterTheNumber() {
        assertTrue(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "The ticket costs €5.",
            "这张票售价5美元。",
            "en",
            "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "The first route is 3 km and the second route is 5 m.",
            "第一条路线是五公里，第二条路线是三米。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "The first route is 3 km and the second route is 5 m.",
            "第一条路线是三公里，第二条路线是五米。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "The ticket costs £5.",
            "这张票售价5英镑。",
            "en",
            "zh-CN",
        ) != null)
    }

    @Test
    fun parsesChineseNumeralsAndRejectsMissingOrChangedExplicitValues() {
        val missing = SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 3 cats and 2 dogs.",
            "我有三只猫。",
            "en",
            "zh-CN",
        )
        assertTrue(missing.orEmpty().contains("未保留"))

        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 3 cats and 2 dogs.",
            "我有三只猫和两只狗。",
            "en",
            "zh-CN",
        ) != null)

        assertTrue(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The year is 2026.",
            "年份是二〇二五年。",
            "en",
            "zh-CN",
        ) != null)

        // A natural classifier for an indefinite article is not treated as an added quantity.
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have a cat.",
            "我有一只猫。",
            "en",
            "zh-CN",
        ) != null)

        val tripSource = "a 2-night, 3-day trip departing on Friday and returning on Sunday; however,"
        val correctTrip = "一个为期两晚三天的旅行，从周五出发，周日返回；然而，"
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            tripSource, correctTrip, "en", "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
            tripSource, correctTrip, "en", "zh-CN",
        ))
        assertTrue(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            tripSource, "一个为期一晚三天的旅行，从周五出发，周日返回；然而，", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "a 1-night, 3-day trip", "一个一晚三天的旅行", "en", "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "a 2-night trip for 1 person", "一个为期两晚的旅行，适合一位游客", "en", "zh-CN",
        ) != null)

    @Test
    fun localizedHalfHourIsExcludedWithoutHidingOtherExplicitQuantities() {
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 2 cats and arrive at 3:30.",
            "我有两只猫，三点半到达。",
            "en",
            "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I have 2 cats and arrive at 3:30.",
            "我有三只猫，三点半到达。",
            "en",
            "zh-CN",
        ) != null)
    }

    @Test
    fun autoLanguageUsesOnlyHighConfidenceScriptDetectionForSemanticChecks() {
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "行きません。",
            "我去。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Je ne sais pas.",
            "我知道。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Ich kann nicht kommen.",
            "我能来。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "No quiero ir.",
            "我想去。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "저는 안 갑니다.",
            "我去。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Я не согласен.",
            "我同意。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "I do not agree.",
            "我同意。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
    }

    @Test
    fun preservesRussianPositiveIdiomsAndChecksClearBeforeAfterRelations() {
        assertFalse(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Нет проблем.",
            "没问题。",
            "auto",
            "zh-CN",
        ) != null)
        assertTrue(SubtitleTranslationQualityPolicy.semanticConsistencyMismatchReason(
            "Поезд прибудет до полудня.",
            "火车中午之后到达。",
            "auto",
            "zh-CN",
        ).orEmpty().startsWith("SEMANTIC_RELATION_MISMATCH"))
    }

    @Test
    fun parsesFractionsRangesAndColloquialChineseLargeNumbers() {
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "I need 1/2 meter.",
            "我需要半米。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericUnitAssociationMismatchReason(
            "I need 1/2 meter.",
            "我需要半米。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "Use 1/4 of the amount.",
            "使用总量的四分之一。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "Set a range from 3 to 5.",
            "范围设为三到五。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The total is 13,000.",
            "总数是一万三。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The price is 1,300.",
            "价格是一千三。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The price is 230.",
            "价格是二百三。",
            "en",
            "zh-CN",
        ) != null)
    }

    @Test
    fun preservesHyphenatedDatesWithoutTreatingDatePartsAsNegativeNumbers() {
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The release date is 2026-11-05.",
            "发布日期是2026年11月5日。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The meeting is on 11/05/2026.",
            "会议日期是2026年11月5日。",
            "en",
            "zh-CN",
        ) != null)
        assertFalse(SubtitleTranslationQualityPolicy.numericContentMismatchReason(
            "The temperature is -5 degrees.",
            "温度是零下五度。",
            "en",
            "zh-CN",
        ) != null)
    }

}
