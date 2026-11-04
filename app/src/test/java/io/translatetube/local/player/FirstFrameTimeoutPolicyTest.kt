package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFrameTimeoutPolicyTest {
    private fun next(
        stage: Int,
        buffering: Boolean = false,
        bufferingMs: Long = 0L,
        rebuilt: Boolean = false,
    ) = FirstFrameTimeoutPolicy.nextAction(stage, buffering, bufferingMs, rebuilt)

    @Test
    fun escalatesFromSoftReloadToOneRebuildToGivingUp() {
        var stage = FirstFrameTimeoutPolicy.SOFT_RELOAD_STAGE

        var action = next(stage)
        assertEquals(FirstFrameTimeoutAction.SOFT_RELOAD, action)
        stage = FirstFrameTimeoutPolicy.stageAfter(stage, action)

        action = next(stage)
        assertEquals(FirstFrameTimeoutAction.REBUILD_PLAYER, action)
        stage = FirstFrameTimeoutPolicy.stageAfter(stage, action)

        action = next(stage, rebuilt = true)
        assertEquals(FirstFrameTimeoutAction.GIVE_UP, action)
        stage = FirstFrameTimeoutPolicy.stageAfter(stage, action)

        assertEquals(FirstFrameTimeoutAction.DONE, next(stage))
    }

    @Test
    fun neverRebuildsTheSameSessionTwice() {
        assertEquals(
            FirstFrameTimeoutAction.GIVE_UP,
            next(FirstFrameTimeoutPolicy.REBUILD_STAGE, rebuilt = true),
        )
    }

    @Test
    fun genuinelyDownloadingVideoIsNotTreatedAsBlackScreen() {
        assertEquals(
            FirstFrameTimeoutAction.WAIT,
            next(FirstFrameTimeoutPolicy.SOFT_RELOAD_STAGE, buffering = true, bufferingMs = 12_000L),
        )
        assertEquals(
            FirstFrameTimeoutAction.SOFT_RELOAD,
            next(
                FirstFrameTimeoutPolicy.SOFT_RELOAD_STAGE,
                buffering = true,
                bufferingMs = FirstFrameTimeoutPolicy.BUFFERING_GRACE_MS,
            ),
        )
    }

    @Test
    fun waitingDoesNotAdvanceTheStage() {
        assertEquals(
            FirstFrameTimeoutPolicy.REBUILD_STAGE,
            FirstFrameTimeoutPolicy.stageAfter(
                FirstFrameTimeoutPolicy.REBUILD_STAGE,
                FirstFrameTimeoutAction.WAIT,
            ),
        )
    }

    @Test
    fun rebuildCoordinatorAllowsOneRequestPerSessionUntilAPictureAppears() {
        val before = PlayerRebuildCoordinator.request.value.token
        val key = "youtube:rebuild-test"

        assertFalse(PlayerRebuildCoordinator.wasRebuilt(key))
        assertTrue(PlayerRebuildCoordinator.requestRebuild(key))
        assertEquals(before + 1, PlayerRebuildCoordinator.request.value.token)
        assertEquals(key, PlayerRebuildCoordinator.request.value.sessionKey)
        assertTrue(PlayerRebuildCoordinator.wasRebuilt(key))

        // A second request for the same video must not loop.
        assertFalse(PlayerRebuildCoordinator.requestRebuild(key))
        assertEquals(before + 1, PlayerRebuildCoordinator.request.value.token)

        PlayerRebuildCoordinator.clear(key)
        assertFalse(PlayerRebuildCoordinator.wasRebuilt(key))
        assertFalse(PlayerRebuildCoordinator.requestRebuild(""))
    }
}
