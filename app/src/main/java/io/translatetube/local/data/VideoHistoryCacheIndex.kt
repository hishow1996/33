package io.translatetube.local.data

import android.content.Context
import java.security.MessageDigest

/** Per-history index for audio cache files; persists only hashed filenames, never URLs or cookies. */
internal object VideoHistoryCacheIndex {
    private const val PREFS_NAME = "video_history_cache_index"
    private val safeAudioFileName = Regex("^[0-9a-f]{32}\\.bin$")

    fun rememberAudioCacheFile(context: Context, videoKey: String, fileName: String) {
        if (videoKey.isBlank() || !safeAudioFileName.matches(fileName)) return
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = storageKey(videoKey)
        val names = preferences.getStringSet(key, emptySet()).orEmpty().toMutableSet()
        names += fileName
        preferences.edit().putStringSet(key, names).apply()
    }

    fun audioCacheFileNames(context: Context, videoKey: String): Set<String> {
        if (videoKey.isBlank()) return emptySet()
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(storageKey(videoKey), emptySet())
            .orEmpty()
            .filter(safeAudioFileName::matches)
            .toSet()
    }

    fun remove(context: Context, videoKey: String) {
        if (videoKey.isBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(storageKey(videoKey))
            .apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun audioCacheFileNameForIdentity(identity: String): String =
        "${shortDigest(identity)}.bin"

    private fun storageKey(videoKey: String): String = "history-${shortDigest(videoKey)}-audio"

    private fun shortDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
