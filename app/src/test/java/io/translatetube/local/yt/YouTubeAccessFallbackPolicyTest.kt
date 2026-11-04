package io.translatetube.local.yt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccessFallbackPolicyTest {
    @Test
    fun detectsAnonymousWatchLoginRequired() {
        val error = IllegalStateException(
            "YouTube probably temporarily blocked anonymous watch access with this IP, " +
                "got error LOGIN_REQUIRED: Sign in to confirm that you're not a bot"
        )

        assertTrue(YouTubeAccessFallbackPolicy.shouldTryYtDlp(error))
    }

    @Test
    fun detectsMarkerInNestedCause() {
        val error = IllegalStateException(
            "Stream resolution failed",
            RuntimeException("LOGIN_REQUIRED: Sign in to confirm you're not a bot")
        )

        assertTrue(YouTubeAccessFallbackPolicy.shouldTryYtDlp(error))
    }

    @Test
    fun doesNotFallbackForUnrelatedErrors() {
        assertFalse(
            YouTubeAccessFallbackPolicy.shouldTryYtDlp(
                IllegalStateException("Connection timed out")
            )
        )
    }

    @Test
    fun ageRestrictedVideosAlsoTryYtDlp() {
        assertTrue(
            YouTubeAccessFallbackPolicy.shouldTryYtDlp(
                IllegalStateException("This age-restricted video cannot be watched anonymously"),
            ),
        )
    }
}
