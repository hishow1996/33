package io.translatetube.local.player

/**
 * Recognizes only the one startup seek used to flush Media3 after the first dubbing cues
 * have been mounted. A time-only exemption could accidentally preserve those old cues when
 * a user performs a real seek immediately after opening the player.
 */
internal object DubbingStartupFlushPolicy {
    fun shouldKeepMountedBus(
        expectedPositionMs: Long,
        actualPositionMs: Long,
        nowNanos: Long,
        expiresAtNanos: Long,
    ): Boolean {
        if (expectedPositionMs < 0L || actualPositionMs < 0L) return false
        if (expiresAtNanos <= 0L || nowNanos >= expiresAtNanos) return false
        return expectedPositionMs == actualPositionMs
    }
}
