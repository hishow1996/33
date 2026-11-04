package io.translatetube.local.player

import android.content.Context
import java.security.MessageDigest

/**
 * Privacy-safe per-session breadcrumb for a user-triggered speaker-recognition task.
 *
 * Native inference owns transient state and is not resumed after process death. This journal
 * restores an explicit INTERRUPTED state, never a false running indicator. A process-local token
 * guard prevents a late callback from an older recognition pass overwriting a newer task.
 */
internal object SpeakerRecognitionTaskJournal {
    private const val PREFS_NAME = "speaker_recognition_task_journal_v1"
    private const val SEPARATOR = "."
    private const val KEY_TASK_TOKEN = "task_token"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STAGE = "stage"
    private const val KEY_MESSAGE = "message"
    private const val KEY_PERCENT = "progress_percent"
    private const val KEY_COMPLETED = "completed_units"
    private const val KEY_TOTAL = "total_units"
    private const val KEY_ITEM = "progress_item"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val MAX_TRACKED_SESSIONS = 24

    // The map intentionally lives only for this process. A new process has no old native task
    // callbacks, so its first begin() is allowed to replace the persisted interrupted token.
    private val activeTokensByPrefix = linkedMapOf<String, Long>()

    @Synchronized
    fun begin(
        context: Context,
        sessionKey: String,
        state: SpeakerRecognitionUiState,
    ) {
        val token = state.taskToken ?: return
        if (sessionKey.isBlank() || token <= 0L || !state.isActive) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        pruneExpired(prefs)
        val prefix = sessionPrefix(sessionKey)
        activeTokensByPrefix[prefix] = token
        trimTrackedSessions()
        persistActiveState(prefs, prefix, token, state)
    }

    @Synchronized
    fun save(
        context: Context,
        sessionKey: String,
        state: SpeakerRecognitionUiState,
    ) {
        val token = state.taskToken ?: return
        if (sessionKey.isBlank() || token <= 0L || !state.isActive) return
        val prefix = sessionPrefix(sessionKey)
        val activeToken = activeTokensByPrefix[prefix]
        if (!SpeakerRecognitionJournalTaskPolicy.acceptsUpdate(activeToken, token)) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        persistActiveState(prefs, prefix, token, state)
    }

    @Synchronized
    fun restoreInterrupted(
        context: Context,
        sessionKey: String,
    ): SpeakerRecognitionUiState? {
        if (sessionKey.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prefix = sessionPrefix(sessionKey)
        if (!prefs.getBoolean(prefix + KEY_ACTIVE, false)) return null

        val now = System.currentTimeMillis()
        val updatedAt = prefs.getLong(prefix + KEY_UPDATED_AT, 0L)
        if (updatedAt <= 0L || updatedAt > now + 60_000L || now - updatedAt > MAX_AGE_MS) {
            clearPrefix(prefs, prefix)
            activeTokensByPrefix.remove(prefix)
            return null
        }

        val stage = runCatching {
            SpeakerRecognitionStage.valueOf(
                prefs.getString(prefix + KEY_STAGE, SpeakerRecognitionStage.PREPARING_AUDIO.name).orEmpty(),
            )
        }.getOrDefault(SpeakerRecognitionStage.PREPARING_AUDIO)
        val completed = prefs.getLong(prefix + KEY_COMPLETED, -1L).takeIf { it >= 0L }
        val total = prefs.getLong(prefix + KEY_TOTAL, -1L).takeIf { it > 0L }
        val oldMessage = prefs.getString(prefix + KEY_MESSAGE, null).orEmpty()
        val progressItem = prefs.getString(prefix + KEY_ITEM, null).orEmpty().ifBlank { null }
        val restoredPercent = prefs.getInt(prefix + KEY_PERCENT, -1).takeIf { it in 0..100 }
        val progressSummary = when {
            stage == SpeakerRecognitionStage.DOWNLOADING_MODELS && completed != null ->
                if (restoredPercent != null) {
                    " · 已下载 " + restoredPercent + "%（" + completed / (1024L * 1024L) + " MB）"
                } else {
                    " · 已下载 " + completed / (1024L * 1024L) + " MB"
                }
            stage == SpeakerRecognitionStage.ANALYZING_AUDIO &&
                progressItem != null && restoredPercent != null ->
                " · 当前分段进度 " + restoredPercent + "%"
            stage == SpeakerRecognitionStage.LINKING_SUBTITLES &&
                completed != null && total != null ->
                " · 已关联 " + completed.coerceAtMost(total) + "/" + total + " 行" +
                    (restoredPercent?.let { "（" + it + "%）" } ?: "")
            completed != null && total != null ->
                " · 已完成 " + completed.coerceAtMost(total) + "/" + total
            restoredPercent != null ->
                " · 上次进度 " + restoredPercent + "%"
            else -> ""
        }
        val itemSummary = if (progressItem != null && !oldMessage.contains(progressItem)) {
            " · " + progressItem
        } else ""
        val detail = if (oldMessage.isNotBlank()) " · " + oldMessage else ""
        val token = prefs.getLong(prefix + KEY_TASK_TOKEN, 0L).takeIf { it > 0L }

        return SpeakerRecognitionUiState(
            phase = SpeakerRecognitionPhase.INTERRUPTED,
            stage = stage,
            message = "上次识别已中断" + itemSummary + progressSummary + detail,
            progressPercent = restoredPercent,
            completedUnits = completed,
            totalUnits = total,
            progressItem = progressItem,
            taskToken = token,
        )
    }

    @Synchronized
    fun clear(
        context: Context,
        sessionKey: String,
        taskToken: Long,
    ) {
        if (sessionKey.isBlank()) return
        val prefix = sessionPrefix(sessionKey)
        val activeToken = activeTokensByPrefix[prefix]
        if (activeToken != null && activeToken != taskToken) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getLong(prefix + KEY_TASK_TOKEN, 0L) == taskToken) {
            clearPrefix(prefs, prefix)
        }
        if (activeTokensByPrefix[prefix] == taskToken) {
            activeTokensByPrefix.remove(prefix)
        }
    }

    private fun persistActiveState(
        prefs: android.content.SharedPreferences,
        prefix: String,
        token: Long,
        state: SpeakerRecognitionUiState,
    ) {
        prefs.edit()
            .putLong(prefix + KEY_TASK_TOKEN, token)
            .putBoolean(prefix + KEY_ACTIVE, true)
            .putString(prefix + KEY_STAGE, state.stage.name)
            .putString(prefix + KEY_MESSAGE, state.message.take(160))
            .putInt(prefix + KEY_PERCENT, state.progressPercent ?: -1)
            .putLong(prefix + KEY_COMPLETED, state.completedUnits ?: -1L)
            .putLong(prefix + KEY_TOTAL, state.totalUnits ?: -1L)
            .putString(prefix + KEY_ITEM, state.progressItem.orEmpty().take(80))
            .putLong(prefix + KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun trimTrackedSessions() {
        while (activeTokensByPrefix.size > MAX_TRACKED_SESSIONS) {
            val oldest = activeTokensByPrefix.keys.firstOrNull() ?: break
            activeTokensByPrefix.remove(oldest)
        }
    }

    private fun pruneExpired(prefs: android.content.SharedPreferences) {
        val now = System.currentTimeMillis()
        val updateKeys = prefs.all.keys.filter { it.endsWith(SEPARATOR + KEY_UPDATED_AT) }
        updateKeys.forEach { updateKey ->
            val updatedAt = prefs.getLong(updateKey, 0L)
            if (updatedAt <= 0L || updatedAt > now + 60_000L || now - updatedAt > MAX_AGE_MS) {
                val prefix = updateKey.removeSuffix(KEY_UPDATED_AT)
                clearPrefix(prefs, prefix)
                activeTokensByPrefix.remove(prefix)
            }
        }
    }

    private fun clearPrefix(
        prefs: android.content.SharedPreferences,
        prefix: String,
    ) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { key ->
            editor.remove(key)
        }
        editor.apply()
    }

    private fun sessionPrefix(sessionKey: String): String = fingerprint(sessionKey) + SEPARATOR

    private fun fingerprint(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
