package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationRelaxationPolicyTest {
    @Test
    fun recognizesHonorificMarkedNamesAcrossAdditionalWritingSystems() {
        val examples = listOf(
            Triple("ดร. สมชาย มาถึงแล้ว", "สมชาย", "th"),
            Triple("ডঃ রহমান এসেছেন।", "রহমান", "bn"),
            Triple("លោក សុខ មកដល់ហើយ។", "សុខ", "km"),
            Triple("ດຣ ສົມຊາຍ ມາແລ້ວ", "ສົມຊາຍ", "lo"),
            Triple("ဒေါက်တာ အောင်မြင့် လာပါပြီ", "အောင်မြင့်", "my"),
        )

        examples.forEach { (source, name, language) ->
            val terms = SubtitleTermContinuityPolicy.extractStableTerms(
                listOf(source),
                sourceLanguage = language,
            )
            assertTrue("$language should retain the marked name", terms.contains(name))
            assertTrue(
                "$language should recognize the same name in a later cue",
                SubtitleTermContinuityPolicy.containsStableTerm(
                    "$name is speaking",
                    terms,
                    sourceLanguage = language,
                ),
            )
            assertTrue(
                "$language name should be allowed inside Chinese translation",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    source,
                    "$name 来了。",
                    language,
                    "zh-CN",
                ),
            )
        }
    }

    @Test
    fun acceptsKnownNewScriptPlaceNamesButStillRejectsOrdinaryEnglishEcho() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                "ঢাকা",
                "ঢাকা",
                "bn",
                "zh-CN",
            ),
        )
        assertTrue(
            SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                "กรุงเทพฯ",
                "กรุงเทพฯ",
                "th",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                "Hello there",
                "Hello there",
                "en",
                "zh-CN",
            ),
        )
    }

    @Test
    fun resolvesNamesSplitAcrossSubtitleBoundariesAndExplicitIntroductions() {
        val splitTitle = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Dr.", "john arrived."),
            sourceLanguage = "en",
        )
        assertTrue(splitTitle.contains("john"))

        val introduced = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("My name is", "john"),
            sourceLanguage = "en",
        )
        assertTrue(introduced.contains("john"))
    }

    @Test
    fun acceptsKnownPlaceNamesFromTheNewScriptsWithoutAllowingOrdinaryEchoes() {
        listOf(
            Triple("சென்னை", "ta", "சென்னை"),
            Triple("హైదరాబాద్", "te", "హైదరాబాద్"),
            Triple("ಬೆಂಗಳೂರು", "kn", "ಬೆಂಗಳೂರು"),
            Triple("കൊച്ചി", "ml", "കൊച്ചി"),
            Triple("කොළඹ", "si", "කොළඹ"),
            Triple("თბილისი", "ka", "თბილისი"),
            Triple("Երևան", "hy", "Երևան"),
            Triple("ድሬዳዋ", "am", "ድሬዳዋ"),
        ).forEach { (source, language, unchanged) ->
            assertTrue(
                "$language should retain a recognized place name",
                SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                    source, unchanged, language, "zh-CN",
                ),
            )
        }
    }

    @Test
    fun normalizesHyphenAndApostropheStyleForStableNameMatching() {
        val hyphenated = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Jean-Luc arrived."),
            sourceLanguage = "en",
        )
        assertTrue(
            SubtitleTermContinuityPolicy.containsStableTerm(
                "Jean‑Luc is back.",
                hyphenated,
                sourceLanguage = "en",
            ),
        )

        val apostrophe = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("O’Neil arrived."),
            sourceLanguage = "en",
        )
        assertTrue(
            SubtitleTermContinuityPolicy.containsStableTerm(
                "O'Neil is back.",
                apostrophe,
                sourceLanguage = "en",
            ),
        )
    }

    @Test
    fun matchesAccentVariantsOnlyWhenTheEntityKeyIsUnambiguous() {
        val terms = SubtitleTermContinuityPolicy.extractStableTerms(
            listOf("Élodie arrived."),
            sourceLanguage = "fr",
        )
        assertTrue(
            SubtitleTermContinuityPolicy.containsStableTerm(
                "Elodie is back.",
                terms,
                sourceLanguage = "fr",
            ),
        )

        val ambiguousTerms = setOf("José", "Jóse")
        assertFalse(
            SubtitleTermContinuityPolicy.containsStableTerm(
                "Jose arrived.",
                ambiguousTerms,
                sourceLanguage = "es",
            ),
        )
    }

    @Test
    fun shortNameValidatorAllowsOneMissingAccentButNotAccentMovedToAnotherLetter() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                "Élodie",
                "Elodie",
                "fr",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isAcceptableShortUntranslatedCue(
                "José",
                "Jóse",
                "es",
                "zh-CN",
            ),
        )
    }

    @Test
    fun retainsHonorificMarkedNamesAcrossTheNewWritingSystems() {
        val examples = listOf(
            Triple("டாக்டர் ரவி வந்தார்", "ரவி", "ta"),
            Triple("డాక్టర్ రవి వచ్చారు", "రవి", "te"),
            Triple("ಡಾ. ರವಿ ಬಂದರು", "ರವಿ", "kn"),
            Triple("ഡോ. രവി എത്തി", "രവി", "ml"),
            Triple("වෛද්‍ය නිමල් පැමිණියා", "නිමල්", "si"),
            Triple("ბატონი დავით მოვიდა", "დავით", "ka"),
            Triple("Պարոն Դավիթը եկավ", "Դավիթը", "hy"),
            Triple("ዶ/ር አበበ መጡ", "አበበ", "am"),
            Triple("آقای رضا آمد", "رضا", "fa"),
            Triple("جناب احمد تشریف لائے", "احمد", "ur"),
        )
        examples.forEach { (source, name, language) ->
            val terms = SubtitleTermContinuityPolicy.extractStableTerms(
                listOf(source),
                sourceLanguage = language,
            )
            assertTrue("$language should keep the honorific-marked name", terms.contains(name.lowercase()))
            assertTrue(
                "$language should recognize the name without its honorific",
                SubtitleTermContinuityPolicy.containsStableTerm(
                    "$name is speaking",
                    terms,
                    sourceLanguage = language,
                ),
            )
        }
    }

    @Test
    fun acceptsSourceGroundedSocialIdentifiersAndVersionLabelsButNotFullSentences() {
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation("@Elephant_33", "@Elephant_33", "en", "zh-CN"))
        assertTrue(
            SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                "Follow @Elephant_33",
                "关注@Elephant_33",
                "en",
                "zh-CN",
            ),
        )
        assertFalse(
            SubtitleTranslationQualityPolicy.isUsableChineseTranslation(
                "Follow @Elephant_33",
                "关注@Other_33",
                "en",
                "zh-CN",
            ),
        )
        assertEquals("ti", SourceLanguagePolicy.canonicalPrimaryCode("tir"))
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation("#SaveTheDate", "#SaveTheDate", "en", "zh-CN"))
        assertTrue(SubtitleTranslationQualityPolicy.isUsableChineseTranslation("v2.1.0", "v2.1.0", "en", "zh-CN"))
        assertFalse(SubtitleTranslationQualityPolicy.isUsableChineseTranslation("The update is ready", "The update is ready", "en", "zh-CN"))
    }

    @Test
    fun recognizesExpandedSoundEventCaptionsAndPreservesTheirBrackets() {
        val captions = listOf(
            "[engine starts]",
            "[glass breaking]",
            "[footsteps approaching]",
            "[water splashing]",
            "[speaking Vietnamese]",
            "(sighs)",
            "【car engine starts】",
            "{glass breaking}",
            "「dog barking」",
            "『crowd cheering』",
            "[someone whispers softly]",
            "[crowd cheering loudly]",
            "[distant traffic]",
            "(people talking in the background)",
        )
        captions.forEach { caption ->
            assertTrue(caption, SubtitleSoundDescriptionPolicy.isSoundDescription(caption))
        }

        assertEquals("[发动机启动声]", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("[engine starts]"))
        assertEquals("{玻璃破碎声}", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("{glass breaking}"))
        assertEquals("「狗叫声」", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("「dog barking」"))
        assertEquals("【汽车发动机启动声】", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("【car engine starts】"))
        assertEquals("[说越南语]", SubtitleSoundDescriptionPolicy.translateKnownSoundDescription("[speaking Vietnamese]"))
    }

    @Test
    fun ordinaryBracketedDialogueIsNotReclassifiedAsASoundEvent() {
        listOf(
            "[the engine starts now]",
            "[glass breaking is dangerous]",
            "{I know what happened}",
            "「Hello there」",
        ).forEach { caption ->
            assertFalse(caption, SubtitleSoundDescriptionPolicy.isSoundDescription(caption))
        }
    }

    @Test
    fun promptRulesAllowIncompleteFragmentsAndUnknownClearSoundCaptions() {
        assertTrue(
            SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES.contains(
                "grammatically incomplete",
            ),
        )
        assertTrue(
            SubtitleTranslationPromptPolicy.SOURCE_ALIGNMENT_GUIDELINES.contains(
                "unfamiliar but clear sound/event descriptions",
            ),
        )
        assertTrue(SubtitleTranslationPromptPolicy.ENGLISH_FIDELITY_GUIDELINES.contains("@handles"))
        assertTrue(
            SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES.contains(
                "跨行截断的不完整片段",
            ),
        )
    }
}
