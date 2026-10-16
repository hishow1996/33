package io.translatetube.local.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadWaitPolicyTest {
    @Test
    fun waitsOnlyWhileTheJobIsActiveAndPauseSignalIsPending() {
        assertTrue(ModelDownloadWaitPolicy.shouldWait(jobIsActive = true, resumeSignalCompleted = false))
        assertFalse(ModelDownloadWaitPolicy.shouldWait(jobIsActive = true, resumeSignalCompleted = true))
        assertFalse(ModelDownloadWaitPolicy.shouldWait(jobIsActive = false, resumeSignalCompleted = false))
        assertFalse(ModelDownloadWaitPolicy.shouldWait(jobIsActive = false, resumeSignalCompleted = true))
    }
}
