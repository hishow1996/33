package io.translatetube.local.translate

import io.translatetube.local.asr.CloudTranscription
import io.translatetube.local.asr.TranscriptTimelinePolicy
import io.translatetube.local.asr.WhisperSubtitleTimelinePolicy
import io.translatetube.local.data.ModelStoragePreflightPolicy
import io.translatetube.local.player.SubtitleCacheValidationPolicy
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundarySafetyPolicyTest {
    @Test fun canonicalizesUnderscoreAndMixedCaseLanguageTags() {
        assertEquals("zh-Hant-TW", SourceLanguagePolicy.normalizeTag("ZH_hant_tw"))
        assertEquals("en-US", SourceLanguagePolicy.normalizeTag(" EN_us "))
    }

    @Test fun sourceLanguageNormalizationIsLocaleIndependent() {
        assertEquals("tr-TR", SourceLanguagePolicy.normalizeTag("TR_tr"))
        assertEquals("de-DE", SourceLanguagePolicy.normalizeTag("DE_de"))
    }

    @Test fun normalizesScriptAndNumericRegionSubtags() {
        assertEquals("sr-Latn-RS", SourceLanguagePolicy.normalizeTag("SR_latn_rs"))
        assertEquals("es-419", SourceLanguagePolicy.normalizeTag("ES_419"))
    }

    @Test fun lowercasesSubtagsAfterBcp47Extensions() {
        assertEquals("en-US-u-ca-gregory", SourceLanguagePolicy.normalizeTag("EN_us_U_CA_GREGORY"))
    }

    @Test fun recognizesAutoSentinelCaseInsensitively() {
        assertTrue(SourceLanguagePolicy.isUndetermined("AUTO"))
        assertEquals("", SourceLanguagePolicy.sourceForTranslation("AuTo"))
    }

    @Test fun recognizesUndeterminedScriptTaggedHints() {
        assertTrue(SourceLanguagePolicy.isUndetermined("und-Latn"))
        assertTrue(SourceLanguagePolicy.isUndetermined("unknown-US"))
        assertTrue(SourceLanguagePolicy.isUndetermined("undefined-Latn"))
    }

    @Test fun stripsUnknownLanguageHintsBeforeTranslationRequests() {
        assertEquals("", SourceLanguagePolicy.sourceForTranslation("UND-Latn"))
        assertEquals("en-US", SourceLanguagePolicy.sourceForTranslation("EN_us"))
    }

    @Test fun recognizesSimplifiedAndTraditionalChineseTags() {
        assertTrue(SourceLanguagePolicy.isChinese("zh-CN"))
        assertTrue(SourceLanguagePolicy.isChinese("zh_Hant_TW"))
        assertTrue(SourceLanguagePolicy.isChinese("ZH-hans"))
    }

    @Test fun recognizesMandarinAndCantoneseTags() {
        assertTrue(SourceLanguagePolicy.isChinese("cmn-Hans-CN"))
        assertTrue(SourceLanguagePolicy.isChinese("yue-HK"))
    }

    @Test fun doesNotClassifyJapaneseOrKoreanAsChinese() {
        assertFalse(SourceLanguagePolicy.isChinese("ja-JP"))
        assertFalse(SourceLanguagePolicy.isChinese("ko-KR"))
    }

    @Test fun cartesiaReceivesCanonicalIsoLanguageOnly() {
        assertEquals("zh", CloudTranscription.cartesiaLanguageCode("ZH_hant_tw"))
        assertEquals("en", CloudTranscription.cartesiaLanguageCode("EN-us"))
        assertEquals(null, CloudTranscription.cartesiaLanguageCode("und-Latn"))
        assertEquals(null, CloudTranscription.cartesiaLanguageCode("auto"))
    }

    @Test fun kawaLanguageTagIsCanonicalAndOmitsSentinels() {
        assertEquals("en-US", CloudTranscription.kawaLanguageCode("EN_us"))
        assertEquals(null, CloudTranscription.kawaLanguageCode("unknown-US"))
    }

    @Test fun sourceEchoDetectionHandlesUnicodeCompatibilityCharacters() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isSourceEcho("ＴＥＳＴ １２３", "test123", "en")
        )
    }

    @Test fun sourceEchoDetectionIgnoresFormattingPunctuation() {
        assertTrue(
            SubtitleTranslationQualityPolicy.isSourceEcho("Hello, world!", "hello world", "en")
        )
    }

    @Test fun chineseSourceTextIsNotRejectedAsSourceEcho() {
        assertFalse(SubtitleTranslationQualityPolicy.isSourceEcho("你好", "你好", "zh_CN"))
    }

    @Test fun rejectsOversizedSubtitleCacheBeforeBuildingHashSet() {
        val repeated = Triple(0L, 1L, "same cue")
        assertFalse(SubtitleCacheValidationPolicy.hasValidTimeline(List(500_001) { repeated }))
    }

    @Test fun acceptsDistinctCuesWithTheSameStartTime() {
        assertTrue(
            SubtitleCacheValidationPolicy.hasValidTimeline(
                listOf(Triple(0L, 100L, "first"), Triple(0L, 200L, "second"))
            )
        )
    }

    @Test fun rejectsNegativeModelStorageRequirementEvenWhenSpaceIsUnknown() {
        assertFalse(ModelStoragePreflightPolicy.hasEnoughSpace(usableBytes = 0L, requiredBytes = -1L))
    }

    @Test fun unknownStorageMeasurementDoesNotBlockValidRequirement() {
        assertTrue(ModelStoragePreflightPolicy.hasEnoughSpace(usableBytes = 0L, requiredBytes = 1024L))
    }

    @Test fun timelineNormalizationSortCanBeCancelledDuringComparisons() {
        val cues = listOf(
            Triple(800L, 900L, "third"),
            Triple(100L, 300L, "first"),
            Triple(400L, 700L, "second"),
        )
        var checks = 0
        var cancelled = false
        try {
            TranscriptTimelinePolicy.normalize(cues, 1_000L) {
                checks++
                if (checks > 2) throw CancellationException("test cancellation")
            }
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test fun timelineOrderingRemainsDeterministicForEqualTimestamps() {
        val normalized = TranscriptTimelinePolicy.normalize(
            listOf(
                Triple(10L, 30L, "z"),
                Triple(10L, 20L, "b"),
                Triple(10L, 20L, "a"),
            ),
            1_000L,
        )
        assertEquals(listOf("a", "b", "z"), normalized.map { it.third })
    }

    @Test fun whisperGroupsSupplementaryCjkByCodePointCount() {
        val tokens = listOf("𠀀".repeat(20), "𠀀".repeat(10))
        val cues = WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
            tokens = tokens,
            timestampsSeconds = listOf(0f, 0.5f),
            durationsSeconds = listOf(0.4f, 0.4f),
            segmentStartMs = 0L,
            segmentEndMs = 1_000L,
            expectedText = tokens.joinToString(""),
        )
        assertEquals(2, cues.size)
    }

    @Test fun whisperRejectsExcessiveTokenArraysBeforeJoiningText() {
        val count = 200_001
        val tokens = List(count) { "a" }
        val timestamps = List(count) { 0f }
        assertTrue(
            WhisperSubtitleTimelinePolicy.fromTokenTimestamps(
                tokens = tokens,
                timestampsSeconds = timestamps,
                durationsSeconds = emptyList(),
                segmentStartMs = 0L,
                segmentEndMs = 10_000L,
                expectedText = "a",
            ).isEmpty()
        )
    }
}
