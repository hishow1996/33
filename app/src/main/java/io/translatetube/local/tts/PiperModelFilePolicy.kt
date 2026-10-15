package io.translatetube.local.tts

/** Filesystem-independent validation for sherpa-onnx Piper model archives. */
internal object PiperModelFilePolicy {
    val CHINESE_RULE_FST_NAMES = listOf("phone.fst", "date.fst", "number.fst")
    const val MIN_ONNX_BYTES = 1L * 1024L * 1024L

    fun isComplete(
        rootFileNames: Set<String>,
        hasEspeakData: Boolean,
        requiresChineseAssets: Boolean,
    ): Boolean = isComplete(
        rootFiles = rootFileNames.associateWith { name ->
            if (name.endsWith(".onnx", ignoreCase = true)) MIN_ONNX_BYTES else 1L
        },
        hasEspeakData = hasEspeakData,
        requiresChineseAssets = requiresChineseAssets,
    )

    /** Names alone are insufficient: an interrupted download can leave a zero-byte or truncated ONNX. */
    fun isComplete(
        rootFiles: Map<String, Long>,
        hasEspeakData: Boolean,
        requiresChineseAssets: Boolean,
    ): Boolean {
        if (rootFiles.none { (name, bytes) ->
                name.endsWith(".onnx", ignoreCase = true) && bytes >= MIN_ONNX_BYTES
            }
        ) return false
        if ((rootFiles["tokens.txt"] ?: 0L) <= 0L) return false

        return if (requiresChineseAssets) {
            (rootFiles["lexicon.txt"] ?: 0L) > 0L &&
                CHINESE_RULE_FST_NAMES.all { (rootFiles[it] ?: 0L) > 0L }
        } else {
            hasEspeakData
        }
    }

    /** Return the path inside a single-root archive, or null for unsafe or foreign-root entries. */
    fun normalizeArchiveEntryName(entryName: String, expectedRootName: String): String? {
        val normalized = entryName.replace('\\', '/')
        if (normalized.startsWith('/') || '\u0000' in normalized || expectedRootName.isBlank()) return null
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == "." || it == ".." || ':' in it }) return null
        if (parts.first() != expectedRootName) return null
        return parts.drop(1).joinToString("/")
    }

    fun isWithinLimit(currentBytes: Long, addedBytes: Long, maxBytes: Long): Boolean =
        currentBytes >= 0L && addedBytes >= 0L && maxBytes >= 0L &&
            currentBytes <= maxBytes && addedBytes <= maxBytes - currentBytes

    fun hasExpectedEntryLength(expectedBytes: Long, actualBytes: Long): Boolean =
        expectedBytes >= 0L && actualBytes == expectedBytes

    fun shouldRestoreBackup(backupExists: Boolean, destinationIsComplete: Boolean): Boolean =
        backupExists && !destinationIsComplete
}
