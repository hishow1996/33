package io.translatetube.local.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks per-cue output readiness independently of the contiguous pre-synthesis progress.
 * A later cue can be ready after a seek even while an earlier cue is still synthesizing.
 */
internal class DubbingReadiness {
    private val prepared = ConcurrentHashMap.newKeySet<Int>()
    private val silent = ConcurrentHashMap.newKeySet<Int>()

    fun clear() {
        prepared.clear()
        silent.clear()
    }

    fun markPrepared(index: Int, hasAudio: Boolean) {
        if (index < 0) return
        if (hasAudio) {
            silent.remove(index)
        } else {
            silent.add(index)
        }
        prepared.add(index)
    }

    fun isReady(index: Int): Boolean = index >= 0 && index in prepared

    fun isPrepared(index: Int): Boolean = isReady(index) && index !in silent

    fun isSilent(index: Int): Boolean = isReady(index) && index in silent
}
