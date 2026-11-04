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

    fun record(
        context: Context,
        sessionKey: String,
        phase: Phase,
        progress: String,
    ) {
        if (sessionKey.isBlank() || phase == Phase.IDLE) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Never let an obsolete service session overwrite a newer Home request.
        if (preferences.getString(KEY_ACTIVE, null) != sessionKey) return
        preferences.edit()
            .putString(KEY_LAST_PHASE, phase.name)
            .putString(KEY_LAST_PROGRESS, progress.trim().take(180))
            .putLong(KEY_LAST_UPDATED_AT, System.currentTimeMillis())
            .commit()
    }
}
