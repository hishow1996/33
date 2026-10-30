package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHintPolicyTest {
    private fun hint(input: PlaybackHintInput) = PlaybackHintPolicy.hintFor(input)

    @Test
    fun noPlaybackProblemProducesNoHint() {
        assertNull(hint(PlaybackHintInput()))
    }

    @Test
    fun loadingAndSlowBufferingUseDifferentMessages() {
        val loading = hint(PlaybackHintInput(isBuffering = true))!!
        assertEquals("视频加载中…", loading.message)
        assertTrue(loading.showSpinner)

        val slow = hint(PlaybackHintInput(isBuffering = true, bufferingDurationMs = 5_000L))!!
        assertEquals("网络较慢，仍在加载…", slow.message)
        assertTrue(slow.showSpinner)
    }

    @Test
    fun loadRetryCountWinsOverOrdinaryBuffering() {
        val h = hint(PlaybackHintInput(isBuffering = true, bufferingDurationMs = 9_000L, loadRetryCount = 3))!!
        assertEquals("automatic-retry", h.key)
        assertEquals("连接不稳定，正在重试（第 3 次）", h.message)
    }

    @Test
    fun expiredHttpAddressRequestsAutomaticRefresh() {
        for (status in listOf(403, 410)) {
            val h = hint(PlaybackHintInput(httpStatusCode = status))!!
            assertEquals("视频地址已失效，正在重新获取…", h.message)
            assertTrue(h.showSpinner)
            assertNull(h.action)
        }
    }

    @Test
    fun failedAddressRefreshShowsRetryAction() {
        val h = hint(PlaybackHintInput(recoveryFailure = "地址已失效，自动重新获取失败"))!!
        assertEquals("视频加载失败：地址已失效，自动重新获取失败", h.message)
        assertEquals(PlaybackHintAction.REPARSE, h.action)
        assertEquals("重试", h.actionLabel)
    }

    @Test
    fun networkFailureOffersRetry() {
        val h = hint(PlaybackHintInput(networkFailure = true, failureMessage = "socket timeout"))!!
        assertEquals("网络连接失败，请检查网络", h.message)
        assertEquals(PlaybackHintAction.RETRY, h.action)
    }

    @Test
    fun exhaustedPlaybackErrorIncludesSafeReasonAndRetry() {
        val h = hint(
            PlaybackHintInput(
                failureMessage = "HTTP 500 while loading https://media.example/video?token=secret",
            ),
        )!!
        assertTrue(h.message.startsWith("视频加载失败：HTTP 500"))
        assertFalse(h.message.contains("https://"))
        assertFalse(h.message.contains("secret"))
        assertEquals(PlaybackHintAction.RETRY, h.action)
    }

    @Test
    fun playbackBlockedByUnreadyDubbingOffersOriginalOnlyAndSettings() {
        val h = hint(
            PlaybackHintInput(
                blockedMessage = "配音模型还在准备中，准备好后自动播放",
                blockedOffersOriginalOnly = true,
            ),
        )!!
        assertTrue(h.showSpinner)
        assertEquals(PlaybackHintAction.PLAY_ORIGINAL_ONLY, h.action)
        assertEquals("仅播放原声", h.actionLabel)
        assertEquals(PlaybackHintAction.OPEN_SETTINGS, h.secondaryAction)
    }

    @Test
    fun failedDubbingReasonRemainsVisible() {
        val h = hint(
            PlaybackHintInput(
                blockedMessage = "配音语音失败：系统 TTS 没有中文语音数据",
                blockedOffersOriginalOnly = true,
            ),
        )!!
        assertTrue(h.message.contains("系统 TTS 没有中文语音数据"))
        assertEquals(PlaybackHintAction.PLAY_ORIGINAL_ONLY, h.action)
    }

    @Test
    fun missingFirstFrameOffersReload() {
        val h = hint(PlaybackHintInput(noFirstFrameTimedOut = true, isBuffering = true))!!
        assertEquals("视频迟迟没有画面", h.message)
        assertEquals(PlaybackHintAction.RELOAD, h.action)
        assertEquals("重新加载", h.actionLabel)
    }

    @Test
    fun stalledRenderedPlaybackOffersReloadInsteadOfClearingHint() {
        val h = hint(
            PlaybackHintInput(
                isPlaying = true,
                hasRenderedFirstFrame = true,
                playbackStalled = true,
            ),
        )!!
        assertEquals("playback-stalled", h.key)
        assertEquals("视频播放似乎卡住了", h.message)
        assertEquals(PlaybackHintAction.RELOAD, h.action)
        assertEquals("重新加载", h.actionLabel)
    }

    @Test
    fun acceptedPlayRequestThatNeverStartsOffersReload() {
        val h = hint(
            PlaybackHintInput(
                isPlaying = false,
                hasRenderedFirstFrame = true,
                playbackStalled = true,
            ),
        )!!
        assertEquals("playback-stalled", h.key)
        assertEquals(PlaybackHintAction.RELOAD, h.action)
    }

    @Test
    fun idlePlayerWithMountedVideoShowsRestoringHint() {
        val h = hint(PlaybackHintInput(hasMedia = true, idleRecovery = true))!!
        assertEquals("正在恢复播放…", h.message)
        assertTrue(h.showSpinner)
    }

    @Test
    fun normalRenderedPlaybackClearsAllHints() {
        assertNull(
            hint(
                PlaybackHintInput(
                    isPlaying = true,
                    hasRenderedFirstFrame = true,
                    networkFailure = true,
                    failureMessage = "old error",
                ),
            ),
        )
    }

    @Test
    fun recoveryAndFailuresTakePriorityOverBuffering() {
        val refreshing = hint(
            PlaybackHintInput(
                isBuffering = true,
                recoveryInProgress = true,
                recoveryMessage = "视频地址已失效，正在重新获取…",
            ),
        )!!
        assertEquals("address-recovery", refreshing.key)

        val network = hint(
            PlaybackHintInput(isBuffering = true, loadRetryCount = 1, networkFailure = true),
        )!!
        assertEquals("network-failure", network.key)
    }

    @Test
    fun shortHintChangesAreHeldForAtLeastOneAndAHalfSeconds() {
        val current = hint(PlaybackHintInput(isBuffering = true))
        val next = hint(PlaybackHintInput(isBuffering = true, bufferingDurationMs = 5_000L))
        assertTrue(PlaybackHintPolicy.shouldKeepCurrent(current, next, 1_000L))
        assertFalse(PlaybackHintPolicy.shouldKeepCurrent(current, next, 1_500L))
    }

    @Test
    fun aHigherPriorityFailureCanReplaceLoadingImmediately() {
        val current = hint(PlaybackHintInput(isBuffering = true))
        val next = hint(PlaybackHintInput(networkFailure = true))
        assertFalse(PlaybackHintPolicy.shouldKeepCurrent(current, next, 100L))
    }

    @Test
    fun ineligibleProcessingStateDoesNotCreatePlaybackOverlay() {
        assertNull(
            hint(PlaybackHintInput(screenEligible = false, isBuffering = true, failureMessage = "bad source")),
        )
    }
}
