package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingMediaResumeContextPolicyTest {
    @Test
    fun restoresExactPageReferrerForSelectedRemoteMedia() {
        assertEquals(
            "https://example.com/watch/episode-2",
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "webpage:https://example.com/home",
                activeKey = "remote:https://cdn.example.com/video.m3u8",
                storedReferrer = "https://example.com/watch/episode-2",
                requestedTaskKey = "webpage:https://example.com/home",
            ),
        )
    }

    @Test
    fun fallsBackToOriginalPageWhenExactReferrerWasNotStored() {
        assertEquals(
            "https://example.com/home",
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "webpage:https://example.com/home",
                activeKey = "remote:https://cdn.example.com/video.m3u8",
                storedReferrer = null,
                requestedTaskKey = "webpage:https://example.com/home",
            ),
        )
    }

    @Test
    fun acceptsEitherKeyForTheSamePendingTask() {
        assertEquals(
            "https://example.com/watch",
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "webpage:https://example.com/home",
                activeKey = "remote:https://cdn.example.com/video.mp4",
                storedReferrer = "https://example.com/watch",
                requestedTaskKey = "remote:https://cdn.example.com/video.mp4",
            ),
        )
    }

    @Test
    fun doesNotAttachWebContextToUnrelatedOrDirectMediaTasks() {
        assertNull(
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "webpage:https://example.com/home",
                activeKey = "remote:https://cdn.example.com/video.mp4",
                storedReferrer = "https://example.com/watch",
                requestedTaskKey = "youtube:abcdefghijk",
            ),
        )
        assertNull(
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "remote:https://cdn.example.com/video.mp4",
                activeKey = "remote:https://cdn.example.com/video.mp4",
                storedReferrer = null,
                requestedTaskKey = "remote:https://cdn.example.com/video.mp4",
            ),
        )
    }

    @Test
    fun rejectsInvalidStoredReferrerAndUsesValidatedSourceUrl() {
        assertEquals(
            "https://example.com/home",
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "webpage:https://example.com/home",
                activeKey = "remote:https://cdn.example.com/video.mp4",
                storedReferrer = "javascript:alert(1)",
                requestedTaskKey = "webpage:https://example.com/home",
            ),
        )
    }

    @Test
    fun rejectsNonHttpSourceUrls() {
        assertNull(
            PendingMediaResumeContextPolicy.pageReferrerForResume(
                sourceKey = "webpage:file:///private/video",
                activeKey = "remote:https://cdn.example.com/video.mp4",
                storedReferrer = "file:///private/video",
                requestedTaskKey = "webpage:file:///private/video",
            ),
        )
    }
}
