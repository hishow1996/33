package io.translatetube.local.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogStoreIssueKeyTest {
    @Test
    fun issueKeyIsStableForTheSameCategoryAndIdentity() {
        assertEquals(
            AppLogStore.stableIssueKey("PlaybackPipeline", "session-42"),
            AppLogStore.stableIssueKey("PlaybackPipeline", "session-42"),
        )
    }

    @Test
    fun issueKeyChangesWhenTheUnderlyingIdentityChanges() {
        assertNotEquals(
            AppLogStore.stableIssueKey("PlaybackPipeline", "session-42"),
            AppLogStore.stableIssueKey("PlaybackPipeline", "session-43"),
        )
    }

    @Test
    fun issueKeyDoesNotExposeRawIdentityAndSanitizesItsCategory() {
        val key = AppLogStore.stableIssueKey("Playback Pipeline/HTTP", "https://example.test/watch?token=secret")
        assertTrue(key.startsWith("PlaybackPipelineHTTP:"))
        assertFalse(key.contains("example.test"))
        assertFalse(key.contains("secret"))
        assertFalse(key.contains("token"))
    }
}
