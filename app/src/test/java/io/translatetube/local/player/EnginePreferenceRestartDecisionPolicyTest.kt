package io.translatetube.local.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePreferenceRestartDecisionPolicyTest {
    @Test
    fun unchangedConfigurationAllowsTheDebouncedDecision() {
        assertTrue(
            EnginePreferenceRestartDecisionPolicy.isStillCurrent(
                decisionFingerprint = "config-a",
                currentFingerprint = "config-a",
            ),
        )
    }

    @Test
    fun switchingBackWhileIdentityLookupIsSuspendedInvalidatesOldDecision() {
        assertFalse(
            EnginePreferenceRestartDecisionPolicy.isStillCurrent(
                decisionFingerprint = "config-b",
                currentFingerprint = "config-a",
            ),
        )
    }

    @Test
    fun emptyDecisionFingerprintCannotAuthorizeARestart() {
        assertFalse(
            EnginePreferenceRestartDecisionPolicy.isStillCurrent(
                decisionFingerprint = "",
                currentFingerprint = "",
            ),
        )
    }
}
