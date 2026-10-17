package io.translatetube.local.data

/** Maps an installed-model directory or its transactional backup name to its model family. */
internal object ModelDirectoryKindPolicy {
    fun isTtsDirectoryName(name: String): Boolean =
        name == "tts" || name == "tts.old"

    fun isSenseVoiceDirectoryName(name: String): Boolean =
        name == "sensevoice" || name == "sensevoice.old"
}
