package io.translatetube.local.data

import java.io.File

/**
 * One source of truth for the Kokoro files referenced by KokoroTts.
 * Large ONNX/voice assets need a minimum size; text, FST and data-directory assets only need
 * to be present and non-empty. This is shared by download-time validation and startup readiness.
 */
internal object ModelAssetReadiness {
    private const val MIN_LARGE_MODEL_BYTES = 1L * 1024L * 1024L

    private val requiredFiles = listOf(
        "model.int8.onnx" to MIN_LARGE_MODEL_BYTES,
        "voices.bin" to MIN_LARGE_MODEL_BYTES,
        "tokens.txt" to 1L,
        "lexicon-zh.txt" to 1L,
        "phone-zh.fst" to 1L,
        "date-zh.fst" to 1L,
        "number-zh.fst" to 1L,
    )

    fun isTtsReady(directory: File): Boolean {
        if (!directory.isDirectory) return false
        if (requiredFiles.any { (name, minimumBytes) ->
                val file = File(directory, name)
                !file.isFile || file.length() < minimumBytes
            }) return false
        return isPopulatedDirectory(File(directory, "espeak-ng-data")) &&
            isPopulatedDirectory(File(directory, "dict"))
    }

    private fun isPopulatedDirectory(directory: File): Boolean =
        directory.isDirectory && directory.listFiles().orEmpty().any { it.isFile && it.length() > 0L }
}
