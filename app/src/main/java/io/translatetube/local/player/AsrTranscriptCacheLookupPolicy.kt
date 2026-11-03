package io.translatetube.local.player

/**
 * Keeps completed source transcripts separate from resumable ASR manifests and segment shards.
 *
 * Partial records intentionally share the selected engine's source-cache prefix so ASR can resume
 * efficiently. They must never be promoted as if they were a complete subtitle timeline.
 */
internal object AsrTranscriptCacheLookupPolicy {
    fun isCompletedSourceTranscriptCacheKey(
        cacheKey: String,
        keyPrefix: String,
    ): Boolean =
        cacheKey.startsWith(keyPrefix) &&
            !cacheKey.contains("|asr-progress-") &&
            !cacheKey.contains("|chunk")
}
