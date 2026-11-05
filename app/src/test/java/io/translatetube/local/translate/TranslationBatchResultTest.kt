package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationBatchResultTest {
    @Test
    fun rejectsRowsThatLoseExplicitSemanticPolarity() {
        val result = TranslationBatchResult.from(listOf(0), listOf("我同意。"), mapOf(0 to "I do not agree."), "en", "zh-CN")
        assertTrue(result.translations.isEmpty())
        assertTrue(result.missingIndices.contains(0))
        assertTrue(result.rejectedRows.any { it.reasonCode == "SEMANTIC_POLARITY_MISMATCH" })
    }


    @Test
    fun preservesRowIndicesAndOrderWhenEveryTranslationIsValid() {
        val result = TranslationBatchResult.from(
            listOf(4, 8, 11),
            listOf("第一句译文", "Second translation", "第三句译文"),
        )

        assertEquals(
            mapOf(4 to "第一句译文", 8 to "Second translation", 11 to "第三句译文"),
            result.translations,
        )
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun marksProgressPlaceholdersAndBlankRowsAsMissing() {
        val result = TranslationBatchResult.from(
            listOf(2, 3, 9),
            listOf("有效译文", "正在翻译", "   "),
        )

        assertEquals(mapOf(2 to "有效译文"), result.translations)
        assertEquals(listOf(3, 9), result.missingIndices)
    }

    @Test
    fun rejectsShortResponsesWithoutGuessingWhichSubtitleRowsTheyBelongTo() {
        val result = TranslationBatchResult.from(
            listOf(10, 12, 20),
            listOf("无法确认对应哪一行的译文"),
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(10, 12, 20), result.missingIndices)
    }

    @Test
    fun rejectsLongResponsesWithoutGuessingWhichSubtitleRowsTheyBelongTo() {
        val result = TranslationBatchResult.from(
            listOf(10, 12),
            listOf("第一行", "第二行", "多余的一行"),
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(10, 12), result.missingIndices)
    }

    @Test
    fun rejectsEnglishParaphrasesForChineseSubtitleOutput() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2),
            rawTranslations = listOf("I think so.", "你好", "J. K. Rowling"),
            sourceTexts = mapOf(
                0 to "I guess so.",
                1 to "Hello",
                2 to "J. K. Rowling",
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertEquals(mapOf(1 to "你好", 2 to "J. K. Rowling"), result.translations)
        assertEquals(listOf(0), result.missingIndices)
    }

    @Test
    fun treatsEmptyOrStatusOnlyResponsesAsMissing() {
        val result = TranslationBatchResult.from(listOf(0, 1), listOf("", "翻译中 1/2 行"))

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(0, 1), result.missingIndices)
    }

    @Test
    fun negativeSourceIndicesAreIgnoredInsteadOfBecomingCacheKeys() {
        val result = TranslationBatchResult.from(
            listOf(-1, 2, -8, 3),
            listOf("must not be stored", "valid", "must not be retried", "正在翻译"),
        )
        assertEquals(mapOf(2 to "valid"), result.translations)
        assertEquals(listOf(3), result.missingIndices)
    }

    @Test
    fun duplicateSourceIndicesProduceAtMostOneTranslationOrRetryEntry() {
        val result = TranslationBatchResult.from(
            listOf(2, 2, 3, 3, 4),
            listOf("", "第二行", "第三行", "", "第四行"),
        )

        assertEquals(mapOf(2 to "第二行", 3 to "第三行", 4 to "第四行"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun rejectsSourceTextEchoesAndRetriesOnlyThoseSubtitleRows() {
        val result = TranslationBatchResult.from(
            indices = listOf(5, 8, 9),
            rawTranslations = listOf("Bonjour à tous", "Hello world", "你好"),
            sourceTexts = mapOf(
                5 to "Bonjour à tous",
                8 to "Hello world",
                9 to "Hello there",
            ),
            sourceLanguage = "en",
        )

        assertEquals(mapOf(9 to "你好"), result.translations)
        assertEquals(listOf(5, 8), result.missingIndices)
    }

    @Test
    fun sourceEchoesAreAlwaysRejectedForRetryInsteadOfAcceptedAsBlankRows() {
        val result = TranslationBatchResult.from(
            indices = listOf(1, 4, 7),
            rawTranslations = listOf("你好世界", "J. K. Rowling", "正在翻译"),
            sourceTexts = mapOf(
                1 to "Hello world",
                4 to "J. K. Rowling",
                7 to "This row is still missing",
            ),
            sourceLanguage = "en",
        )

        assertEquals(mapOf(1 to "你好世界"), result.translations)
        assertEquals(listOf(4, 7), result.missingIndices)
    }

    @Test
    fun allowsIdenticalChineseSourceTextWhenTheSourceIsAlreadyChinese() {
        val result = TranslationBatchResult.from(
            indices = listOf(1, 2),
            rawTranslations = listOf("你好世界", "保留原声"),
            sourceTexts = mapOf(1 to "你好世界", 2 to "保留原声"),
            sourceLanguage = "zh-Hans",
        )

        assertEquals(mapOf(1 to "你好世界", 2 to "保留原声"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun retriesEchoRowsWhenTheProviderOnlyChangesFinalPunctuation() {
        val result = TranslationBatchResult.from(
            indices = listOf(1, 2),
            rawTranslations = listOf("Hello world.", "你好世界。"),
            sourceTexts = mapOf(1 to "Hello world", 2 to "Hello there"),
            sourceLanguage = "en",
        )
        assertEquals(mapOf(2 to "你好世界。"), result.translations)
        assertEquals(listOf(1), result.missingIndices)
    }

    @Test
    fun exposesSpecificReasonsForRejectedSubtitleRows() {
        val result = TranslationBatchResult.from(
            indices = listOf(252, 253, 254),
            rawTranslations = listOf("I think so.", "Hello there.", ""),
            sourceTexts = mapOf(
                252 to "I guess so.",
                253 to "Hello there.",
                254 to "Where are you?",
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertEquals(listOf(252, 253, 254), result.missingIndices)
        assertEquals(
            listOf(
                "TARGET_LANGUAGE_MISMATCH",
                "SOURCE_ECHO_AND_TARGET_LANGUAGE_MISMATCH",
                "EMPTY_OR_STATUS_PLACEHOLDER",
            ),
            result.rejectedRows.map { it.reasonCode },
        )
        assertEquals(listOf(11, 12, 14), result.rejectedRows.map { it.sourceCharCount })
        assertEquals(listOf(11, 12, 0), result.rejectedRows.map { it.rawOutputCharCount })
        assertEquals(listOf("I think so.", "Hello there.", "（空）"), result.rejectedRows.map { it.rawOutputPreview })
        assertTrue(result.rejectedRows[0].diagnosticReason.orEmpty().contains("未检测到汉字"))
    }

    @Test
    fun identifiesEveryAffectedRowWhenResponseLengthDoesNotMatchRequest() {
        val result = TranslationBatchResult.from(
            indices = listOf(10, 12),
            rawTranslations = listOf("only one output"),
            sourceTexts = mapOf(10 to "First source", 12 to "Second source"),
        )

        assertEquals(listOf(10, 12), result.missingIndices)
        assertEquals(listOf("ROW_COUNT_MISMATCH", "ROW_COUNT_MISMATCH"), result.rejectedRows.map { it.reasonCode })
        assertEquals(listOf(12, 13), result.rejectedRows.map { it.sourceCharCount })
    }

    @Test
    fun echoedInterjectionGetsChineseSubstituteInsteadOfBeingRetried() {
        val result = TranslationBatchResult.from(
            listOf(252, 253),
            listOf("嗯哼。", "Mm-hmm."),
            sourceTexts = mapOf(252 to "Right.", 253 to "Mm-hmm."),
            sourceLanguage = "en",
        )

        assertEquals(mapOf(252 to "嗯哼。", 253 to "嗯哼"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun punctuationOnlyOrBlankInterjectionTranslationUsesSubstitute() {
        val result = TranslationBatchResult.from(
            listOf(1, 2, 3),
            listOf("。", "", "Oh"),
            sourceTexts = mapOf(1 to "Hmm...", 2 to "Uh-huh?", 3 to "Oh"),
            sourceLanguage = "auto",
        )

        assertEquals(mapOf(1 to "嗯", 2 to "嗯哼？", 3 to "哦"), result.translations)
        assertTrue(result.missingIndices.isEmpty())
    }

    @Test
    fun validChineseTranslationOfInterjectionIsKept() {
        val result = TranslationBatchResult.from(
            listOf(1),
            listOf("哦，原来如此"),
            sourceTexts = mapOf(1 to "Oh"),
            sourceLanguage = "en",
        )

        assertEquals(mapOf(1 to "哦，原来如此"), result.translations)
    }

    @Test
    fun confirmsShortNameOnlyAfterRetryAndUsesChineseInterjectionSubstitutionForHello() {
        val firstAttempt = TranslationBatchResult.from(
            indices = listOf(0, 1),
            rawTranslations = listOf("John", "Hello"),
            sourceTexts = mapOf(0 to "John", 1 to "Hello"),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )
        assertEquals(mapOf(1 to "你好"), firstAttempt.translations)
        assertEquals(listOf(0), firstAttempt.missingIndices)

        val retry = TranslationBatchResult.from(
            indices = listOf(0),
            rawTranslations = listOf("John"),
            sourceTexts = mapOf(0 to "John"),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            acceptShortUnchanged = true,
        )
        assertEquals(mapOf(0 to "John"), retry.translations)
        assertTrue(retry.missingIndices.isEmpty())
    }

    @Test
    fun retryOnlyConfirmsShortNamesWhenTheReturnedSpellingStillMatches() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2, 3),
            rawTranslations = listOf("John", "Sara", "David", "David Beckham"),
            sourceTexts = mapOf(0 to "John", 1 to "Sarah", 2 to "John", 3 to "Elon Musk"),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            acceptShortUnchanged = true,
        )

        assertEquals(mapOf(0 to "John", 1 to "Sara"), result.translations)
        assertEquals(listOf(2, 3), result.missingIndices)
    }

    @Test
    fun ordinarySentenceEchoIsStillRejectedAndNonEnglishSourceIsNotSubstituted() {
        val sentence = TranslationBatchResult.from(
            listOf(1),
            listOf("Right here."),
            sourceTexts = mapOf(1 to "Right here."),
            sourceLanguage = "en",
        )
        assertTrue(sentence.translations.isEmpty())
        assertEquals(listOf(1), sentence.missingIndices)

        val french = TranslationBatchResult.from(
            listOf(1),
            listOf("Oh"),
            sourceTexts = mapOf(1 to "Oh"),
            sourceLanguage = "fr",
        )
        assertEquals(listOf(1), french.missingIndices)
    }

    @Test
    fun symbolOnlyCueIsNotGivenAnInterjection() {
        assertEquals(null, SubtitleInterjectionPolicy.chineseFor("♪", "en", "zh"))
        assertEquals("哈哈", SubtitleInterjectionPolicy.chineseFor("Hahaha", "en", "zh-CN"))
        assertEquals("嗯", SubtitleInterjectionPolicy.chineseFor("Hmmmm", "en", "zh"))
        assertEquals(null, SubtitleInterjectionPolicy.chineseFor("Hmm", "en", "ja"))
    }

    @Test
    fun rejectsSymbolOnlyTranslationsThatCannotBeSpoken() {
        val result = TranslationBatchResult.from(
            indices = listOf(2, 5),
            rawTranslations = listOf("……", "♪♪"),
            sourceTexts = mapOf(2 to "Please wait.", 5 to "♪♪"),
            sourceLanguage = "zh-Hans",
            targetLanguage = "zh-CN",
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(2, 5), result.missingIndices)
        assertTrue(result.rejectedRows.any { it.reasonCode == "UNSPEAKABLE_TRANSLATION" })
    }
 
    @Test
    fun rejectsCommonShortPhraseEchoesAndMixedLanguagePartialOutput() {
        val source = listOf(
            "No Way",
            "Hello World",
            "CALL 911 NOW",
            "Taxi",
            "Hello there.",
        )
        val result = TranslationBatchResult.from(
            indices = source.indices.toList(),
            rawTranslations = listOf(
                "No Way",
                "Hello World",
                "CALL 911 NOW",
                "Taxi",
                "你好 hello",
            ),
            sourceTexts = source.indices.associateWith { source[it] },
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            acceptShortUnchanged = true,
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(source.indices.toList(), result.missingIndices)
        assertEquals(5, result.rejectedRows.size)
    } 
    @Test
    fun rejectsConsonantOnlyAcronymEchoInsteadOfReplacingItWithAnInterjection() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2),
            rawTranslations = listOf("BFF", "SMH", "LOL"),
            sourceTexts = mapOf(0 to "BFF", 1 to "SMH", 2 to "LOL"),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
            acceptShortUnchanged = true,
        )

        assertEquals(mapOf(2 to "哈哈"), result.translations)
        assertEquals(listOf(0, 1), result.missingIndices)
    }

    @Test
    fun rejectsModelMetaRepliesAndReportsTheirSpecificFailureCategory() {
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2),
            rawTranslations = listOf(
                "请提供需要翻译的原文。",
                "抱歉，我无法帮助你翻译这段内容。",
                "作为一个 AI 语言模型，我无法完成这个请求。",
            ),
            sourceTexts = mapOf(
                0 to "Hello, how are you?",
                1 to "What are you doing?",
                2 to "Nice to meet you.",
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(0, 1, 2), result.missingIndices)
        assertEquals(
            listOf("MODEL_META_RESPONSE", "MODEL_META_RESPONSE", "MODEL_META_RESPONSE"),
            result.rejectedRows.map { it.reasonCode },
        )
        assertTrue(result.rejectedRows.all {
            it.diagnosticReason.orEmpty().contains("疑似模型") ||
                it.diagnosticReason.orEmpty().contains("要求补充")
        })
    }

    @Test
    fun rejectsServiceErrorTextAsTranslationRows() {
        val outputs = listOf(
            "请求过于频繁，请稍后重试。",
            "服务器暂时不可用，请稍后重试。",
            "API 密钥无效，请检查配置。",
        )
        val result = TranslationBatchResult.from(
            indices = listOf(0, 1, 2),
            rawTranslations = outputs,
            sourceTexts = mapOf(
                0 to "Hello there.",
                1 to "What happened?",
                2 to "I think so.",
            ),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )
        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(0, 1, 2), result.missingIndices)
        assertEquals(
            listOf("MODEL_META_RESPONSE", "MODEL_META_RESPONSE", "MODEL_META_RESPONSE"),
            result.rejectedRows.map { it.reasonCode },
        )
    }

    @Test
    fun rejectsChangedExplicitNumbersWithDedicatedDiagnosticCategory() {
        val result = TranslationBatchResult.from(
            indices = listOf(8),
            rawTranslations = listOf("我有4只猫和2只狗。"),
            sourceTexts = mapOf(8 to "I have 3 cats and 2 dogs."),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )

        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(8), result.missingIndices)
        assertEquals(listOf("NUMERIC_CONTENT_MISMATCH"), result.rejectedRows.map { it.reasonCode })
        assertTrue(result.rejectedRows.single().diagnosticReason.orEmpty().contains("显式数字"))
        assertTrue(result.rejectedRows.single().qualityEvidence.orEmpty().contains("explicitNumericContentMismatch=true"))
    }

    @Test
    fun rejectsExplicitUnitChangesWithDedicatedDiagnosticCategory() {
        val result = TranslationBatchResult.from(
            indices = listOf(3),
            rawTranslations = listOf("这条路长5米。"),
            sourceTexts = mapOf(3 to "The road is 5 km long."),
            sourceLanguage = "en",
            targetLanguage = "zh-CN",
        )
        assertTrue(result.translations.isEmpty())
        assertEquals(listOf(3), result.missingIndices)
        assertEquals(listOf("UNIT_CONTENT_MISMATCH"), result.rejectedRows.map { it.reasonCode })
        assertTrue(result.rejectedRows.single().diagnosticReason.orEmpty().contains("单位类别不一致"))
    }
}
