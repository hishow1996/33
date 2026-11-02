package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerRecognitionCachePolicyTest {
    private val cues = sequenceOf(
        SpeakerRecognitionCacheIdentity.Cue(0L, 1_500L, "Hello there"),
        SpeakerRecognitionCacheIdentity.Cue(1_500L, 3_000L, "How are you?"),
    )

    private fun identity(
        session: String = "youtube:abc",
        audio: String = "youtube-audio:abc",
        model: String = "speaker-model-v1",
        rows: Sequence<SpeakerRecognitionCacheIdentity.Cue> = cues,
    ) = SpeakerRecognitionCacheIdentity.create(session, audio, rows, model)

    @Test
    fun fingerprintIsDeterministic() {
        assertEquals(identity(), identity())
    }

    @Test
    fun fingerprintChangesForSourceTimelineTextOrModelChanges() {
        val base = identity()
        assertNotEquals(base, identity(audio = "youtube-audio:other"))
        assertNotEquals(base, identity(session = "youtube:other"))
        assertNotEquals(base, identity(model = "speaker-model-v2"))
        assertNotEquals(
            base,
            identity(rows = sequenceOf(
                SpeakerRecognitionCacheIdentity.Cue(0L, 1_500L, "Hello there"),
                SpeakerRecognitionCacheIdentity.Cue(1_500L, 3_100L, "How are you?"),
            )),
        )
        assertNotEquals(
            base,
            identity(rows = sequenceOf(
                SpeakerRecognitionCacheIdentity.Cue(0L, 1_500L, "Hello there"),
                SpeakerRecognitionCacheIdentity.Cue(1_500L, 3_000L, "Different text"),
            )),
        )
    }

    @Test
    fun invalidCueRowsAreExcludedAndEmptyTimelinesCannotBeCached() {
        assertEquals(
            "",
            SpeakerRecognitionCacheIdentity.create(
                "video",
                "audio",
                sequenceOf(SpeakerRecognitionCacheIdentity.Cue(0L, 0L, "invalid")),
                "model",
            ),
        )
    }

    @Test
    fun cacheHitRequiresExactIdentityAndUsefulAssignments() {
        val key = identity()
        assertTrue(SpeakerRecognitionCachePolicy.canReuse(key, key, 2, 1, 1))
        assertFalse(SpeakerRecognitionCachePolicy.canReuse("old", key, 2, 2, 1))
        assertFalse(SpeakerRecognitionCachePolicy.canReuse(key, key, 2, 0, 1))
        assertFalse(SpeakerRecognitionCachePolicy.canReuse(key, key, 2, 1, 0))
        assertFalse(SpeakerRecognitionCachePolicy.canReuse(key, key, 0, 0, 1))
    }
}
