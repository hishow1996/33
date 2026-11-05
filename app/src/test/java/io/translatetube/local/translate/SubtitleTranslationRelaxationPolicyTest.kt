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
        assertTrue(
            SubtitleTranslationPromptPolicy.CHINESE_FIDELITY_GUIDELINES.contains(
                "跨行截断的不完整片段",
            ),
        )
    }
}
