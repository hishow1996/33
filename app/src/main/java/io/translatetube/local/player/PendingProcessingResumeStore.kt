package io.translatetube.local.player

import android.content.Context

/**
 * Minimal durable journal for the Home resume card.
 *
 * The Activity may be removed while the foreground service continues. Keep this record in the
 * service so a later cold start can show the exact interrupted phase even after PlayerUiState is
 * lost. Subtitle rows remain in InProgressProcessingCheckpointStore.
 */
internal object PendingProcessingResumeStore {
    const val PREFS_NAME = "pending_video_resume"
    const val KEY_SOURCE = "source_key"
    const val KEY_ACTIVE = "active_key"
    const val KEY_GENERATION = "generation"
    const val KEY_MEDIA_REFERRER = "media_referrer"
    const val KEY_MEDIA_USER_AGENT = "media_user_agent"
    const val KEY_LAST_PHASE = "last_phase"
    const val KEY_LAST_PROGRESS = "last_progress"
    const val KEY_LAST_UPDATED_AT = "last_updated_at"

    /** Returns the generation only when this exact media key still owns the Home resume task. */
    fun activeGeneration(context: Context, sessionKey: String): Long? {
        if (sessionKey.isBlank()) return null
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (preferences.getString(KEY_ACTIVE, null) != sessionKey) return null
        return preferences.getLong(KEY_GENERATION, 0L).takeIf { it > 0L }
    }

    fun record(
        context: Context,
        sessionKey: String,
        expectedGeneration: Long,
        phase: Phase,
        progress: String,
    ) {
        if (sessionKey.isBlank() || expectedGeneration <= 0L || phase == Phase.IDLE) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // A key alone is insufficient: a new request may reopen the same video while an old
        // service write is still queued on IO. Both key and generation must still match.
        if (!PendingProcessingResumeWritePolicy.shouldWrite(
                activeSessionKey = preferences.getString(KEY_ACTIVE, null),
                activeGeneration = preferences.getLong(KEY_GENERATION, 0L),
                expectedSessionKey = sessionKey,
                expectedGeneration = expectedGeneration,
            )
        ) return
        preferences.edit()
            .putString(KEY_LAST_PHASE, phase.name)
            .putString(KEY_LAST_PROGRESS, progress.trim().take(180))
            .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }
}
