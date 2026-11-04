package io.translatetube.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPauseCoordinationPolicyTest {
    @Test
    fun pausesBothEnginesWhenTheirPauseStatesHaveDiverged() {
        assertTrue(
            ModelPauseCoordinationPolicy.shouldPauseAfterToggle(
                managedActive = true,
                managedPaused = true,
                piperActive = true,
                piperPaused = false,
            )
        )
    }

    @Test
    fun resumesAllActiveEnginesOnlyWhenTheyAreAllPaused() {
        assertFalse(
            ModelPauseCoordinationPolicy.shouldPauseAfterToggle(
                managedActive = true,
                managedPaused = true,
                piperActive = true,
                piperPaused = true,
            )
        )
        assertFalse(
            ModelPauseCoordinationPolicy.shouldPauseAfterToggle(
                managedActive = false,
                managedPaused = false,
                piperActive = true,
                piperPaused = true,
            )
        )
    }

    @Test
    fun handlesEitherEngineActiveIndependently() {
        assertTrue(
            ModelPauseCoordinationPolicy.shouldPauseAfterToggle(
                managedActive = true,
                managedPaused = false,
                piperActive = false,
                piperPaused = false,
            )
        )
        assertTrue(
            ModelPauseCoordinationPolicy.shouldPauseAfterToggle(
                managedActive = false,
                managedPaused = false,
                piperActive = true,
                piperPaused = false,
            )
        )
    }
}
