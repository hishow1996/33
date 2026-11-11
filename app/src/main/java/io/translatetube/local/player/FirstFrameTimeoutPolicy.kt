package io.translatetube.local.player

/** What the service should do after the first-frame wait window elapsed without a picture. */
internal enum class FirstFrameTimeoutAction {
    /** Still legitimately loading (slow network); keep waiting and do not log an error yet. */
    WAIT,

    /** Re-prepare the current player once and resume playback. */
    SOFT_RELOAD,

    /** Tear the player service down and reopen the current video from scratch. */
    REBUILD_PLAYER,

    /** Automatic recovery was already used; leave the manual hint and log the final failure. */
    GIVE_UP,

    /** Every step of this escalation was already taken for the current session. */
    DONE,
}

/**
 * Escalation for "black screen / play button does nothing": a soft reload first, then one full
 * player rebuild per video session, then a final error. Stages advance one per timeout window.
 */
internal object FirstFrameTimeoutPolicy {
    const val SOFT_RELOAD_STAGE = 0
    const val REBUILD_STAGE = 1
    const val GIVE_UP_STAGE = 2
    const val FINISHED_STAGE = 3

    /** A video that is genuinely downloading data is not stuck; give it this long before acting. */
    const val BUFFERING_GRACE_MS = 30_000L

    fun nextAction(
        stage: Int,
        isBuffering: Boolean,
        bufferingDurationMs: Long,
        rebuildAlreadyUsed: Boolean,
    ): FirstFrameTimeoutAction {
        if (stage >= FINISHED_STAGE) return FirstFrameTimeoutAction.DONE
        if (isBuffering && bufferingDurationMs < BUFFERING_GRACE_MS) return FirstFrameTimeoutAction.WAIT
        return when (stage) {
            SOFT_RELOAD_STAGE -> FirstFrameTimeoutAction.SOFT_RELOAD
            REBUILD_STAGE ->
                if (rebuildAlreadyUsed) FirstFrameTimeoutAction.GIVE_UP else FirstFrameTimeoutAction.REBUILD_PLAYER
            else -> FirstFrameTimeoutAction.GIVE_UP
        }
    }

    /**
     * Whether the "no picture yet" wait window should be running. A video view that is on screen
     * but not ready (texture never became available, zero size) or not bound to the player (the
     * bind threw) is exactly a black screen, so it must count too. Requiring a ready, bound view
     * here made the watchdog blind to those cases. A view that is not attached (processing page
     * in front, Activity gone) is not a black screen and never counts.
     */
    fun shouldCountNoFrameWait(
        monitorVideo: Boolean,
        mediaAttached: Boolean,
        hasRenderedFirstFrame: Boolean,
        audioOnly: Boolean,
        viewAttached: Boolean,
    ): Boolean = monitorVideo && mediaAttached && !hasRenderedFirstFrame && !audioOnly && viewAttached

    /** The stage to continue from after [action] was carried out. */
    fun stageAfter(stage: Int, action: FirstFrameTimeoutAction): Int = when (action) {
        FirstFrameTimeoutAction.SOFT_RELOAD -> REBUILD_STAGE
        FirstFrameTimeoutAction.REBUILD_PLAYER -> GIVE_UP_STAGE
        FirstFrameTimeoutAction.GIVE_UP, FirstFrameTimeoutAction.DONE -> FINISHED_STAGE
        FirstFrameTimeoutAction.WAIT -> stage
    }
}

/**
 * Asks the player screen to stop and restart the playback service for [sessionKey]. A new
 * [token] means a new request; the screen ignores requests for other videos.
 */
internal data class PlayerRebuildRequest(val token: Long, val sessionKey: String)

/** Process-wide rebuild bookkeeping; survives the service restart it triggers. */
internal object PlayerRebuildCoordinator {
    private val lock = Any()
    private val _request = kotlinx.coroutines.flow.MutableStateFlow(PlayerRebuildRequest(0L, ""))
    val request: kotlinx.coroutines.flow.StateFlow<PlayerRebuildRequest> = _request
    private var rebuiltSessionKey = ""

    /** Publishes a rebuild request unless this session was already rebuilt once. */
    fun requestRebuild(sessionKey: String): Boolean = synchronized(lock) {
        if (sessionKey.isBlank() || rebuiltSessionKey == sessionKey) return@synchronized false
        rebuiltSessionKey = sessionKey
        _request.value = PlayerRebuildRequest(_request.value.token + 1L, sessionKey)
        true
    }

    fun wasRebuilt(sessionKey: String): Boolean = synchronized(lock) {
        sessionKey.isNotBlank() && rebuiltSessionKey == sessionKey
    }

    /** Called once a picture was shown, so a later unrelated black screen may rebuild again. */
    fun clear(sessionKey: String) {
        synchronized(lock) { if (rebuiltSessionKey == sessionKey) rebuiltSessionKey = "" }
    }
}
