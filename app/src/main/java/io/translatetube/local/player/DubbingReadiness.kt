package io.translatetube.local.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-cue output readiness independently of the contiguous pre-synthesis progress.
 * A later cue can be ready after a seek even while an earlier cue is still synthesizing.
 *
 * Store the audio/silent state in one concurrent map. Two independent sets allow a concurrent
 * markPending() to interleave between markPrepared()'s updates and resurrect a cue with the
 * wrong state while the playback ticker is reading readiness.
 */
internal class DubbingReadiness {
    // true = synthesized audio is ready; false = intentionally silent/fallback to source audio.
    private val states = ConcurrentHashMap<Int, Boolean>()

    fun clear() {
        states.clear()
    }

    fun markPrepared(index: Int, hasAudio: Boolean) {
        if (index < 0) return
        states[index] = hasAudio
    }

    /** Reopen a cue after a previously valid cache entry disappears or fails validation. */
    fun markPending(index: Int) {
        if (index < 0) return
        states.remove(index)
    }

    /** Number of ready cues, including out-of-order cues prioritized after a seek. */
    fun readyCount(): Int = states.size

    fun isReady(index: Int): Boolean = index >= 0 && states.containsKey(index)

    fun isPrepared(index: Int): Boolean = index >= 0 && states[index] == true

    fun isSilent(index: Int): Boolean = index >= 0 && states[index] == false
}
