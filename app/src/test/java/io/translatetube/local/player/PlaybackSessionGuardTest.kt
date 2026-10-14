package io.translatetube.local.player

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionGuardTest {
    @Test
    fun acceptsMatchingVideoAndUiSessionKeys() {
        PlaybackSessionGuard.ensureCurrent(
            sessionKey = "youtube:video-a",
            activeVideoKey = "youtube:video-a",
            activeUiKey = "youtube:video-a",
        )
    }

    @Test
    fun rejectsOldVideoAfterTheActiveSessionChanges() {
        val failure = runCatching {
            PlaybackSessionGuard.ensureCurrent(
                sessionKey = "youtube:old",
                activeVideoKey = "youtube:new",
                activeUiKey = "youtube:new",
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is CancellationException)
    }

    @Test
    fun rejectsSameVideoWhenItsUiSessionHasAlreadyBeenReplaced() {
        val failure = runCatching {
            PlaybackSessionGuard.ensureCurrent(
                sessionKey = "youtube:same",
                activeVideoKey = "youtube:same",
                activeUiKey = "youtube:same|new-import",
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure is CancellationException)
    }
}
