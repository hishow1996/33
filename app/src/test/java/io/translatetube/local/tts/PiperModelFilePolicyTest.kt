package io.translatetube.local.tts

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
}
