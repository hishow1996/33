package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingTranslationPolicyTest {
    @Test
    fun neverTurnsProgressPlaceholdersIntoSpokenAudio() {
        assertNull(DubbingTranslationPolicy.usableText("This is a line", "正在翻译"))
        assertNull(DubbingTranslationPolicy.usableText("This is a line", "翻译中 2/5 行"))
    }

    @Test
    fun rejectsAnUntranslatedSourceEchoBeforeBuildingThePcmCache() {
        assertNull(DubbingTranslationPolicy.usableText("Hello world.", "Hello world"))
        assertEquals("你好世界。", DubbingTranslationPolicy.usableText("Hello world", "你好世界。"))
    }

    @Test
    fun preservesLiteralChineseSubtitlesThatResembleProgressLabels() {
        assertEquals("正在翻译", DubbingTranslationPolicy.usableText("正在翻译", "正在翻译", "zh"))
        assertNull(DubbingTranslationPolicy.usableText("正在翻译", "正在翻译", "auto"))
    }

    @Test
    fun blankTranslationProvidesSpecificDiagnosticReason() {
        assertNull(DubbingTranslationPolicy.usableText("Hello world", null))
        assertNull(DubbingTranslationPolicy.usableText("Hello world", "   "))
        assertEquals(
            "翻译结果为空或只有空白字符",
            DubbingTranslationPolicy.resolveText("Hello world", null).reason,
        )
    }

    @Test
    fun sourceEchoProvidesSpecificDiagnosticReason() {
        val result = DubbingTranslationPolicy.resolveText("Hello world.", "Hello world")
        assertNull(result.text)
        assertEquals("译文与原文相同，未生成中文译文", result.reason)
    }

    @Test
    fun placeholderTranslationProvidesSpecificDiagnosticReason() {
        val result = DubbingTranslationPolicy.resolveText("This is a line", "正在翻译")
        assertNull(result.text)
        assertEquals("原始译文非空，但清理占位内容/非字幕文本后为空", result.reason)
    }
    @Test
    fun knownJapaneseLanguageKeepsKanjiOnlyCueFromBeingMisclassifiedAsChinese() {
        assertEquals("东京", DubbingTranslationPolicy.usableText("東京", "东京", "ja"))
    }

    @Test
    fun recognizesMandarinAndCantoneseBcp47Tags() {
        assertEquals("你好 OK", DubbingTranslationPolicy.usableText("你好 OK", null, "cmn-Hans-CN"))
        assertEquals("你好世界", DubbingTranslationPolicy.usableText("你好世界", null, "yue-Hant-HK"))
        assertNull(DubbingTranslationPolicy.usableText("東京", "東京", "ja-JP"))
    }

    @Test
    fun knownChineseLanguagePreservesMixedHanAndLatinCaption() {
        assertEquals("你好 OK", DubbingTranslationPolicy.usableText("你好 OK", null, "zh-Hans"))
    }

    @Test
    fun unknownHanOnlySourceRequiresLanguageConfirmationOrARealTranslation() {
        val result = DubbingTranslationPolicy.resolveText("東京", "東京", "auto")
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().contains("无法确认是中文还是日语"))

        val missing = DubbingTranslationPolicy.resolveText("你好", null, "auto")
        assertNull(missing.text)
        assertTrue(missing.reason.orEmpty().contains("确认源语言"))

        assertEquals("你好", DubbingTranslationPolicy.usableText("你好", null, "zh"))
        assertEquals("您好", DubbingTranslationPolicy.usableText("你好", "您好", "auto"))
    }

    @Test
    fun knownForeignLanguageStillRejectsAnEchoEvenWhenCueLooksLikeKanjiOnly() {
        assertNull(DubbingTranslationPolicy.usableText("東京", "東京", "ja"))
    }

    @Test
    fun unchangedTermsAndNamesAreSpokenInsteadOfFallingBackToTheSourceAudio() {
        assertEquals("H-1B", DubbingTranslationPolicy.usableText("H-1B", "H-1B", "en"))
        assertEquals("NASA", DubbingTranslationPolicy.usableText("NASA", "NASA", "en"))
        assertEquals("Elon Musk", DubbingTranslationPolicy.usableText("Elon Musk", "Elon Musk", "en"))
    }

    @Test
    fun rejectsUnusableTranslationInsteadOfSilentlyUsingTheSourceAudio() {
        val sourceLines = listOf(
            io.translatetube.local.subs.SubLine(0, 0L, 900L, "Hello world", "你好世界"),
            io.translatetube.local.subs.SubLine(1, 900L, 1800L, "NASA", "NASA"),
        )

        val prepared = DubbingTranslationPolicy.prepareLinesForDubbing(sourceLines, "en")

        assertEquals(listOf("你好世界", "NASA"), prepared.map { it.zh })
        val error = runCatching {
            DubbingTranslationPolicy.prepareLinesForDubbing(
                listOf(io.translatetube.local.subs.SubLine(2, 1800L, 2700L, "Where are you?", null)),
                "en",
            )
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("无法生成中文配音"))
    }

    @Test
    fun importedChineseSourceWithoutSeparateTranslationRemainsDubbable() {
        val imported = io.translatetube.local.subs.ImportedSubtitlePolicy.preserveImportedTranslations(
            listOf(io.translatetube.local.subs.SubLine(0, 0L, 900L, "你好", null)),
        )
        val chineseSource = io.translatetube.local.subs.ImportedSubtitlePolicy
            .completeChineseSourceCaptions(imported)

        val prepared = DubbingTranslationPolicy.prepareLinesForDubbing(chineseSource, "zh")

        assertEquals("你好", prepared.single().zh)
    }

    @Test
    fun importedForeignSourceWithoutTranslationFailsPreflight() {
        val imported = io.translatetube.local.subs.ImportedSubtitlePolicy.preserveImportedTranslations(
            listOf(io.translatetube.local.subs.SubLine(0, 0L, 900L, "Hello there", null)),
        )

        assertNull(imported.single().zh)
        val error = runCatching {
            DubbingTranslationPolicy.prepareLinesForDubbing(imported, "en")
        }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("翻译结果为空"))
    }

    @Test
    fun blankSourceCuesDoNotBlockDubbingPreflight() {
        val source = io.translatetube.local.subs.SubLine(0, 0, 500, "", null)
        val prepared = DubbingTranslationPolicy.prepareLinesForDubbing(listOf(source), "en")

        assertEquals(1, prepared.size)
        assertNull(prepared.single().zh)
    }

    @Test
    fun rejectsTranslationsThatContainNoSpeakableCharacters() {
        val result = DubbingTranslationPolicy.resolveText("Hello world", "……", "en")
        assertNull(result.text)
        assertEquals("译文只有标点、符号或表情，没有可朗读内容", result.reason)

        assertNull(DubbingTranslationPolicy.usableText("♪♪", "♪♪", "zh-Hans"))
    }

    @Test
    fun shortNameOrLoanwordKeptUnchangedIsAValidTranslation() {
        assertEquals("Sarah.", DubbingTranslationPolicy.usableText("Sarah.", "Sarah.", "en"))
        assertEquals("Taxi", DubbingTranslationPolicy.usableText("Taxi", "Taxi", "en"))
        // A known interjection must use its Chinese equivalent, not the short-name exception.
        assertNull(DubbingTranslationPolicy.usableText("Hello", "Hello", "en"))
        assertNull(DubbingTranslationPolicy.usableText("Goodbye", "Goodbye", "en"))
        assertNull(DubbingTranslationPolicy.usableText("John", "David", "en"))
        assertNull(DubbingTranslationPolicy.usableText("Elon Musk", "David Beckham", "en"))
        assertEquals("Sara", DubbingTranslationPolicy.usableText("Sarah", "Sara", "en"))
        // Whole sentences returned unchanged remain a failed translation.
        assertNull(DubbingTranslationPolicy.usableText("Thank you.", "Thank you.", "en"))
        assertNull(DubbingTranslationPolicy.usableText("Where are you?", "Where are you?", "en"))
    }

    @Test
    fun symbolOnlySourceCuesDoNotFailDubbingPreflight() {
        val symbolOnly = io.translatetube.local.subs.SubLine(0, 0L, 900L, "♪♪", null)
        val prepared = DubbingTranslationPolicy.prepareLinesForDubbing(listOf(symbolOnly), "en")

        assertEquals(1, prepared.size)
        assertNull(prepared.single().zh)
    }


    @Test
    fun rejectsModelMetaRepliesBeforeTheyBecomeSpokenAudio() {
        val result = DubbingTranslationPolicy.resolveText(
            "Hello, how are you?",
            "请提供需要翻译的原文。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().contains("补充原文"))
    }

    @Test
    fun rejectsServiceErrorRepliesBeforeTheyBecomeSpokenAudio() {
        val result = DubbingTranslationPolicy.resolveText(
            "Hello there.",
            "服务器暂时不可用，请稍后重试。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().contains("服务/网络/鉴权错误"))
    }

    @Test
    fun rejectsSemanticContradictionsBeforeDubbingWithSpecificReason() {
        val result = DubbingTranslationPolicy.resolveText(
            "I don't agree with him.",
            "我同意他。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().contains("否定"))
    }

    @Test
    fun rejectsChangedExplicitNumbersBeforeDubbingWithSpecificReason() {
        val result = DubbingTranslationPolicy.resolveText(
            "I have 3 cats and 2 dogs.",
            "我有4只猫和2只狗。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().contains("显式数字"))
    }

    @Test
    fun rejectsExplicitUnitSubstitutionBeforeDubbing() {
        val result = DubbingTranslationPolicy.resolveText(
            "The road is 5 km long.",
            "这条路长5米。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().contains("单位类别不一致"))
    }
    @Test
    fun rejectsClearTraditionalChineseOutputWhenThePlaybackTargetIsSimplifiedChinese() {
        val result = DubbingTranslationPolicy.resolveText(
            "The software will be updated later.",
            "這個軟體後續會持續更新。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().startsWith("TARGET_SCRIPT_MISMATCH"))
    }

    @Test
    fun finalDubbingGuardRejectsSwappedNumberUnitPairsAndMultilingualNegation() {
        val units = DubbingTranslationPolicy.resolveText(
            "The first route is 3 km and the second route is 5 m.",
            "第一条路线是5公里，第二条路线是3米。",
            "en",
        )
        assertNull(units.text)
        assertTrue(units.reason.orEmpty().contains("数字与单位的对应关系"))

        val french = DubbingTranslationPolicy.resolveText(
            "Je ne sais pas.",
            "我知道。",
            "fr",
        )
        assertNull(french.text)
        assertTrue(french.reason.orEmpty().startsWith("SEMANTIC_POLARITY_MISMATCH"))
    }

    @Test
    fun finalDubbingGuardRejectsQuestionChangedIntoAStatement() {
        val result = DubbingTranslationPolicy.resolveText(
            "Where are you?",
            "我在这里。",
            "en",
        )
        assertNull(result.text)
        assertTrue(result.reason.orEmpty().startsWith("SEMANTIC_INTENT_MISMATCH"))
    }

}
