package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrTranscriptCacheLookupPolicyTest {
    private val prefix = "video|source|remote-asr-local-whisper-large-"

    @Test
    fun acceptsCompletedCurrentAndLegacySourceTranscriptKeys() {
        assertTrue(
            AsrTranscriptCacheLookupPolicy.isCompletedSourceTranscriptCacheKey(
                cacheKey = "${prefix}engine-v1-timed-v5",
                keyPrefix = prefix,
            ),
        )
        assertTrue(
            AsrTranscriptCacheLookupPolicy.isCompletedSourceTranscriptCacheKey(
                cacheKey = "${prefix}legacy-language-auto-config-abcd",
                keyPrefix = prefix,
            ),
        )
    }

    @Test
    fun rejectsPartialManifestAndBothSegmentShardFormats() {
        val partialPrefix = "${prefix}engine-v1-timed-v5|asr-progress-v2"
        val partialKeys = listOf(
            partialPrefix,
            "$partialPrefix|chunk-v4:12",
            "$partialPrefix|chunk:12",
        )
        for (key in partialKeys) {
            assertFalse(
                key,
                AsrTranscriptCacheLookupPolicy.isCompletedSourceTranscriptCacheKey(
                    cacheKey = key,
                    keyPrefix = prefix,
                ),
            )
        }
    }

    @Test
    fun rejectsCacheKeysFromAnotherPrefix() {
        assertFalse(
            AsrTranscriptCacheLookupPolicy.isCompletedSourceTranscriptCacheKey(
                cacheKey = "another-video|source|remote-asr-local-whisper-large-engine-v1",
                keyPrefix = prefix,
            ),
        )
    }
}
