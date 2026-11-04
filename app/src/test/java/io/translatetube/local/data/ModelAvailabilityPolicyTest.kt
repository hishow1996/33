package io.translatetube.local.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelAvailabilityPolicyTest {
    @Test
    fun marksPreviouslyInstalledModelsReadyAfterRestart() {
        assertEquals(ModelStatus.READY, ModelAvailabilityPolicy.initialStatus(isReady = true))
    }

    @Test
    fun doesNotPretendMissingOrIncompleteModelsAreReady() {
        assertEquals(ModelStatus.NOT_DOWNLOADED, ModelAvailabilityPolicy.initialStatus(isReady = false))
    }
}
