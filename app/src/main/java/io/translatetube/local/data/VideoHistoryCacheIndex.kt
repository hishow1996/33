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

    fun rememberAudioCacheFile(
        context: Context,
        videoKey: String,
        fileName: String,
        sourceIdentity: String? = null,
    ) {
        rememberFileName(context, videoKey, audioStorageKey(videoKey), fileName, safeAudioFileName)
        if (videoKey.isBlank() || !safeAudioFileName.matches(fileName) || sourceIdentity.isNullOrBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(audioSourceIdentityStorageKey(videoKey, fileName), shortDigest(sourceIdentity))
            .apply()
    }

    fun audioCacheFileNames(context: Context, videoKey: String): Set<String> =
        fileNames(context, videoKey, audioStorageKey(videoKey), safeAudioFileName)

    /** A shared remote-audio file must remain while another history item still references it. */
    fun isAudioCacheFileReferencedByAnotherHistory(
        context: Context,
        videoKey: String,
        fileName: String,
    ): Boolean {
        if (videoKey.isBlank() || !safeAudioFileName.matches(fileName)) return false
        val ownKey = audioStorageKey(videoKey)
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all.any { (key, value) ->
            key != ownKey &&
                key.endsWith("-audio") &&
                (value as? Set<*>)?.contains(fileName) == true
        }
    }

    /** Only reuse an alternate indexed cache when its one-way source identity matches this media. */
    fun hasMatchingAudioSourceIdentity(
        context: Context,
        videoKey: String,
        fileName: String,
        sourceIdentity: String,
    ): Boolean {
        if (videoKey.isBlank() || !safeAudioFileName.matches(fileName) || sourceIdentity.isBlank()) return false
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(audioSourceIdentityStorageKey(videoKey, fileName), null)
        return stored != null && stored == shortDigest(sourceIdentity)
    }

    fun rememberLocalPcmCacheFile(context: Context, videoKey: String, fileName: String) {
        rememberFileName(context, videoKey, localPcmStorageKey(videoKey), fileName, safeLocalPcmFileName)
    }

    fun localPcmCacheFileNames(context: Context, videoKey: String): Set<String> =
        fileNames(context, videoKey, localPcmStorageKey(videoKey), safeLocalPcmFileName)

    /** Keep a shared PCM file when another history row still references the same hashed name. */
    fun isLocalPcmCacheFileReferencedByAnotherHistory(
        context: Context,
        videoKey: String,
        fileName: String,
    ): Boolean {
        if (videoKey.isBlank() || !safeLocalPcmFileName.matches(fileName)) return false
        val ownKey = localPcmStorageKey(videoKey)
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return preferences.all.any { (key, value) ->
            key != ownKey &&
                key.endsWith("-local-pcm") &&
                (value as? Set<*>)?.contains(fileName) == true
        }
    }

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

    /**
     * Cache identities are scoped to the history item and its deletion generation. A deleted
     * history item can therefore never reuse an old media URL/content-addressed file when added
     * again, even if another row still references an earlier cache.
     */
    fun generationScopedIdentity(context: Context, videoKey: String, identity: String): String {
        if (videoKey.isBlank()) return identity
        return "$videoKey|history-generation=${cacheGeneration(context, videoKey)}|$identity"
    }

    fun cacheGeneration(context: Context, videoKey: String): Long {
        if (videoKey.isBlank()) return 0L
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(generationStorageKey(videoKey), 0L)
            .coerceAtLeast(0L)
    }

    /** Advance the task namespace before file deletion, retaining indexes so failed cleanup can retry. */
    fun advanceGenerationForDeletion(context: Context, videoKey: String): Boolean {
        if (videoKey.isBlank()) return false
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val current = preferences.getLong(generationStorageKey(videoKey), 0L).coerceAtLeast(0L)
        val next = if (current == Long.MAX_VALUE) 1L else current + 1L
        return preferences.edit().putLong(generationStorageKey(videoKey), next).commit()
    }

    /** Remove per-history cache-index entries after all associated files were deleted. */
    fun removeForVideo(context: Context, videoKey: String): Boolean {
        if (videoKey.isBlank()) return false
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = preferences.edit()
            .remove(audioStorageKey(videoKey))
            .remove(localPcmStorageKey(videoKey))
        val identityPrefix = audioSourceIdentityStoragePrefix(videoKey)
        preferences.all.keys.filter { it.startsWith(identityPrefix) }.forEach { editor.remove(it) }
        return editor.commit()
    }

    fun remove(context: Context, videoKey: String) {
        removeForVideo(context, videoKey)
    }

    fun clearAll(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

    fun audioCacheFileNameForIdentity(identity: String): String =
        "${shortDigest(identity)}.bin"

    private fun audioStorageKey(videoKey: String): String =
        "history-${shortDigest(videoKey)}-audio"

    private fun audioSourceIdentityStoragePrefix(videoKey: String): String =
        "history-${shortDigest(videoKey)}-audio-source-"

    private fun audioSourceIdentityStorageKey(videoKey: String, fileName: String): String =
        audioSourceIdentityStoragePrefix(videoKey) + fileName

    private fun localPcmStorageKey(videoKey: String): String =
        "history-${shortDigest(videoKey)}-local-pcm"

    private fun generationStorageKey(videoKey: String): String =
        "history-${shortDigest(videoKey)}-generation"

    private fun shortDigest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
