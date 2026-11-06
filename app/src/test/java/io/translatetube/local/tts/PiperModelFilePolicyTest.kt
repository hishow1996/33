package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PiperModelFilePolicyTest {
    @Test
    fun acceptsOriginalOnnxFilenameWhenEnglishPiperAssetsArePresent() {
        assertTrue(
            PiperModelFilePolicy.isComplete(
                rootFileNames = setOf("en_US-amy-low.onnx", "tokens.txt"),
                hasEspeakData = true,
                requiresChineseAssets = false,
            )
        )
    }

    @Test
    fun acceptsChineseArchiveLayoutWithoutEspeakDataDirectory() {
        assertTrue(
            PiperModelFilePolicy.isComplete(
                rootFileNames = setOf("zh_CN-chaowen-medium.onnx", "tokens.txt", "lexicon.txt", "phone.fst", "date.fst", "number.fst"),
                hasEspeakData = false,
                requiresChineseAssets = true,
            )
        )
    }

    @Test
    fun chineseModelRequiresLexiconAndAllPronunciationRuleFiles() {
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFileNames = setOf("zh_CN-chaowen-medium.onnx", "tokens.txt", "lexicon.txt", "phone.fst"),
                hasEspeakData = false,
                requiresChineseAssets = true,
            )
        )
    }

    @Test
    fun rejectsModelWithoutTokensOrWithoutRuntimeAssets() {
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFileNames = setOf("en_US-amy-low.onnx"),
                hasEspeakData = true,
                requiresChineseAssets = false,
            )
        )
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFileNames = setOf("en_US-amy-low.onnx", "tokens.txt"),
                hasEspeakData = false,
                requiresChineseAssets = false,
            )
        )
    }
    @Test
    fun rejectsTruncatedOnnxAndEmptyRuntimeFilesEvenWhenNamesExist() {
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFiles = mapOf(
                    "zh_CN-chaowen-medium.onnx" to 4096L,
                    "tokens.txt" to 1024L,
                    "lexicon.txt" to 1024L,
                    "phone.fst" to 100L,
                    "date.fst" to 100L,
                    "number.fst" to 100L,
                ),
                hasEspeakData = false,
                requiresChineseAssets = true,
            )
        )
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFiles = mapOf(
                    "zh_CN-chaowen-medium.onnx" to PiperModelFilePolicy.MIN_ONNX_BYTES,
                    "tokens.txt" to 0L,
                    "lexicon.txt" to 1024L,
                    "phone.fst" to 100L,
                    "date.fst" to 100L,
                    "number.fst" to 100L,
                ),
                hasEspeakData = false,
                requiresChineseAssets = true,
            )
        )
    }

    @Test
    fun acceptsMeloChineseEnglishVitsAssetsWithoutEspeakData() {
        assertTrue(
            PiperModelFilePolicy.isComplete(
                rootFiles = mapOf(
                    "model.onnx" to 163L * 1024L * 1024L,
                    "tokens.txt" to 655L,
                    "lexicon.txt" to 6L * 1024L * 1024L,
                    "phone.fst" to 87L * 1024L,
                    "date.fst" to 58L * 1024L,
                    "number.fst" to 63L * 1024L,
                ),
                hasEspeakData = false,
                requiresChineseAssets = true,
                modelFileName = "model.onnx",
            )
        )
    }

    @Test
    fun matchaRequiresAcousticModelVocoderEspeakLexiconAndRuleFiles() {
        val assets = mapOf(
            "model-steps-3.onnx" to 72L * 1024L * 1024L,
            "vocos-16khz-univ.onnx" to 53L * 1024L * 1024L,
            "tokens.txt" to 21L * 1024L,
            "lexicon.txt" to 1300L * 1024L,
            "phone-zh.fst" to 87L * 1024L,
            "date-zh.fst" to 58L * 1024L,
            "number-zh.fst" to 63L * 1024L,
        )
        assertTrue(
            PiperModelFilePolicy.isComplete(
                rootFiles = assets,
                hasEspeakData = true,
                requiresChineseAssets = true,
                modelFileName = "model-steps-3.onnx",
                vocoderFileName = "vocos-16khz-univ.onnx",
                requiresEspeakDataForChinese = true,
                ruleFstNames = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst"),
            )
        )
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFiles = assets - "vocos-16khz-univ.onnx",
                hasEspeakData = true,
                requiresChineseAssets = true,
                modelFileName = "model-steps-3.onnx",
                vocoderFileName = "vocos-16khz-univ.onnx",
                requiresEspeakDataForChinese = true,
                ruleFstNames = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst"),
            )
        )
        assertFalse(
            PiperModelFilePolicy.isComplete(
                rootFiles = assets,
                hasEspeakData = false,
                requiresChineseAssets = true,
                modelFileName = "model-steps-3.onnx",
                vocoderFileName = "vocos-16khz-univ.onnx",
                requiresEspeakDataForChinese = true,
                ruleFstNames = listOf("phone-zh.fst", "date-zh.fst", "number-zh.fst"),
            )
        )
    }

    @Test
    fun rejectsArchivePathTraversalAbsolutePathsAndForeignRoots() {
        assertEquals("model.onnx", PiperModelFilePolicy.normalizeArchiveEntryName(
            "piper-root/model.onnx", "piper-root"
        ))
        assertEquals("", PiperModelFilePolicy.normalizeArchiveEntryName(
            "piper-root/", "piper-root"
        ))
        assertEquals(null, PiperModelFilePolicy.normalizeArchiveEntryName(
            "piper-root/../outside.onnx", "piper-root"
        ))
        assertEquals(null, PiperModelFilePolicy.normalizeArchiveEntryName(
            "/piper-root/model.onnx", "piper-root"
        ))
        assertEquals(null, PiperModelFilePolicy.normalizeArchiveEntryName(
            "another-root/model.onnx", "piper-root"
        ))
    }



    @Test
    fun restoresBackupOnlyWhenTheCurrentDestinationIsNotComplete() {
        assertTrue(PiperModelFilePolicy.shouldRestoreBackup(
            backupExists = true, backupIsComplete = true, destinationIsComplete = false
        ))
        assertFalse(PiperModelFilePolicy.shouldRestoreBackup(
            backupExists = false, backupIsComplete = true, destinationIsComplete = false
        ))
        assertFalse(PiperModelFilePolicy.shouldRestoreBackup(
            backupExists = true, backupIsComplete = false, destinationIsComplete = false
        ))
        assertFalse(PiperModelFilePolicy.shouldRestoreBackup(
            backupExists = true, backupIsComplete = true, destinationIsComplete = true
        ))
    }







    @Test
    fun rejectsArchiveAndExtractionSizeOverflowAndMismatchedEntryLengths() {
        assertTrue(PiperModelFilePolicy.isWithinLimit(100L, 50L, 150L))
        assertFalse(PiperModelFilePolicy.isWithinLimit(100L, 51L, 150L))
        assertFalse(PiperModelFilePolicy.isWithinLimit(Long.MAX_VALUE, 1L, Long.MAX_VALUE))
        assertFalse(PiperModelFilePolicy.isWithinLimit(0L, -1L, 100L))
        assertTrue(PiperModelFilePolicy.hasExpectedEntryLength(128L, 128L))
        assertFalse(PiperModelFilePolicy.hasExpectedEntryLength(128L, 127L))
        assertFalse(PiperModelFilePolicy.hasExpectedEntryLength(-1L, 0L))
    }
}
