package io.translatetube.local.tts

/** Filesystem-independent validation for sherpa-onnx Piper model archives. */
internal object PiperModelFilePolicy {
    val CHINESE_RULE_FST_NAMES = listOf("phone.fst", "date.fst", "number.fst")

    fun isComplete(
        rootFileNames: Set<String>,
        hasEspeakData: Boolean,
        requiresChineseAssets: Boolean,
    ): Boolean {
        if (rootFileNames.none { it.endsWith(".onnx", ignoreCase = true) }) return false
        if ("tokens.txt" !in rootFileNames) return false

        return if (requiresChineseAssets) {
            "lexicon.txt" in rootFileNames &&
                CHINESE_RULE_FST_NAMES.all { it in rootFileNames }
        } else {
            hasEspeakData
        }
    }
}
