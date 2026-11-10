package io.translatetube.local.data

import android.content.Context
import java.security.MessageDigest

/**
 * Durable tombstones for destructive history/cache operations.
 *
 * Keys are stored only as SHA-256 hashes so media URLs and local document URIs are never copied
 * into this recovery journal. Every write is committed synchronously before file cleanup starts.
 */
internal object PendingVideoHistoryDeletionStore {
    private const val PREFS_NAME = "pending_video_history_deletions"
    private const val KEY_VIDEO_HASHES = "pending_video_hashes"
    private const val KEY_CLEAR_PROCESSING = "clear_processing_pending"
    private const val KEY_DELETE_ALL_HISTORY = "delete_all_history_pending"
    private val safeHash = Regex("^[0-9a-f]{64}$")

    @Synchronized
    fun markVideoPending(context: Context, videoKey: String): Boolean {
        if (videoKey.isBlank()) return false
        val hash = videoKeyHash(videoKey)
        val preferences = preferences(context)
        val hashes = preferences.getStringSet(KEY_VIDEO_HASHES, emptySet()).orEmpty().toMutableSet()
        hashes += hash
        return preferences.edit().putStringSet(KEY_VIDEO_HASHES, hashes).commit()
    }

    @Synchronized
    fun clearVideoPending(context: Context, videoKey: String): Boolean =
        if (videoKey.isBlank()) false else clearVideoPendingHash(context, videoKeyHash(videoKey))

    @Synchronized
    fun clearVideoPendingHash(context: Context, hash: String): Boolean {
        if (!safeHash.matches(hash)) return false
        val preferences = preferences(context)
        val hashes = preferences.getStringSet(KEY_VIDEO_HASHES, emptySet()).orEmpty().toMutableSet()
        hashes.remove(hash)
        return preferences.edit().putStringSet(KEY_VIDEO_HASHES, hashes).commit()
    }

    @Synchronized
    fun pendingVideoHashes(context: Context): Set<String> =
        preferences(context).getStringSet(KEY_VIDEO_HASHES, emptySet()).orEmpty()
            .filter(safeHash::matches)
            .toSet()

    @Synchronized
    fun markProcessingClearPending(context: Context): Boolean =
        preferences(context).edit().putBoolean(KEY_CLEAR_PROCESSING, true).commit()

    @Synchronized
    fun isProcessingClearPending(context: Context): Boolean =
        preferences(context).getBoolean(KEY_CLEAR_PROCESSING, false)

    @Synchronized
    fun clearProcessingClearPending(context: Context): Boolean =
        preferences(context).edit().remove(KEY_CLEAR_PROCESSING).commit()

    @Synchronized
    fun markDeleteAllHistoryPending(context: Context): Boolean =
        preferences(context).edit().putBoolean(KEY_DELETE_ALL_HISTORY, true).commit()

    @Synchronized
    fun isDeleteAllHistoryPending(context: Context): Boolean =
        preferences(context).getBoolean(KEY_DELETE_ALL_HISTORY, false)

    @Synchronized
    fun clearDeleteAllHistoryPending(context: Context): Boolean =
        preferences(context).edit().remove(KEY_DELETE_ALL_HISTORY).commit()

    @Synchronized
    fun completeDeleteAll(context: Context): Boolean =
        clearDeleteAllHistoryPending(context)

    @Synchronized
    fun hasPendingOperations(context: Context): Boolean {
        val preferences = preferences(context)
        return preferences.getBoolean(KEY_CLEAR_PROCESSING, false) ||
            preferences.getBoolean(KEY_DELETE_ALL_HISTORY, false) ||
            pendingVideoHashes(context).isNotEmpty()
    }

    /** Prevent any restore while a global clear is pending, or this one video is being deleted. */
    @Synchronized
    fun blocksPlayback(context: Context, videoKey: String): Boolean {
        val preferences = preferences(context)
        if (preferences.getBoolean(KEY_CLEAR_PROCESSING, false) ||
            preferences.getBoolean(KEY_DELETE_ALL_HISTORY, false)
        ) return true
        return videoKey.isNotBlank() && videoKeyHash(videoKey) in
            preferences.getStringSet(KEY_VIDEO_HASHES, emptySet()).orEmpty()
    }

    fun videoKeyHash(videoKey: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(videoKey.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
