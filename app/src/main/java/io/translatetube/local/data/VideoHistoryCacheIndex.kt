package io.translatetube.local.data

import android.content.Context
import java.security.MessageDigest

/**
 * Per-history index for source-audio cache artifacts. Only validated hashed filenames are stored;
 * media URLs, cookies and document-provider URIs are never persisted in this index.
 */
internal object VideoHistoryCacheIndex {
    private const val PREFS_NAME = "video_history_cache_index"
    private val safeAudioFileName = Regex("^[0-9a-f]{32}\\.bin$")
    private val safeLocalPcmFileName = Regex("^[0-9a-f]{32}\\.pcm16$")

    fun rememberAudioCacheFile(context: Context, videoKey: String, fileName: String) {
        rememberFileName(context, videoKey, audioStorageKey(videoKey), fileName, safeAudioFileName)
    }

    fun audioCacheFileNames(context: Context, videoKey: String): Set<String> =
        fileNames(context, videoKey, audioStorageKey(videoKey), safeAudioFileName)

    fun rememberLocalPcmCacheFile(context: Context, videoKey: String, fileName: String) {
        rememberFileName(context, videoKey, localPcmStorageKey(videoKey), fileName, safeLocalPcmFileName)
    }

    fun localPcmCacheFileNames(context: Context, videoKey: String): Set<String> =
        fileNames(context, videoKey, localPcmStorageKey(videoKey), safeLocalPcmFileName)

    private fun rememberFileName(
        context: Context,
        videoKey: String,
        storageKey: String,
        fileName: String,
        validator: Regex,
    ) {
        if (videoKey.isBlank() || !validator.matches(fileName)) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val names = preferences.getStringSet(storageKey, emptySet()).orEmpty().toMutableSet()
        names += fileName
        preferences.edit().putStringSet(storageKey, names).apply()
    }

    private fun fileNames(
        context: Context,
        videoKey: String,
        storageKey: String,
        validator: Regex,
    ): Set<String> {
        if (videoKey.isBlank()) return emptySet()
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(storageKey, emptySet())
            .orEmpty()
            .filter(validator::matches)
            .toSet()
    }

    fun remove(context: Context, videoKey: String) {
        if (videoKey.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(audioStorageKey(videoKey))
            .remove(localPcmStorageKey(videoKey))
            .apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun audioCacheFileNameForIdentity(identity: String): String =
        "${shortDigest(identity)}.bin"

    private fun audioStorageKey(videoKey: String): String =
        "history-${shortDigest(videoKey)}-audio"

    private fun localPcmStorageKey(videoKey: String): String =
        "history-${shortDigest(videoKey)}-local-pcm"

    private fun shortDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
