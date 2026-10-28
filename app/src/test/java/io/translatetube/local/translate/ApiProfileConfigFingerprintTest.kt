package io.translatetube.local.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiProfileConfigFingerprintTest {
    private fun fingerprint(
        model: String,
        baseUrl: String = "https://api.example/v1",
        apiKey: String = "test-secret",
        secondaryCredential: String = "",
    ): String = ApiProfileConfigFingerprint.create(
        profileId = 42L,
        type = "OPENAI",
        baseUrl = baseUrl,
        model = model,
        apiKey = apiKey,
        region = "",
        secondaryCredential = secondaryCredential,
    )

    @Test
    fun switchingModelsWithinTheSameApiProfileChangesFingerprint() {
        assertNotEquals(fingerprint("model-a"), fingerprint("model-b"))
    }

    @Test
    fun equivalentUrlFormattingDoesNotChangeFingerprint() {
        assertEquals(
            fingerprint("model-a", baseUrl = "https://api.example/v1/"),
            fingerprint("model-a", baseUrl = " https://api.example/v1 "),
        )
    }

    @Test
    fun credentialMaterialIsRepresentedOnlyByAOneWayDigest() {
        val value = fingerprint("model-a")
        assertTrue(value.matches(Regex("^[0-9a-f]{64}$")))
        assertFalse(value.contains("test-secret"))
        assertNotEquals(value, fingerprint("model-a", apiKey = "different-secret"))
        assertNotEquals(value, fingerprint("model-a", secondaryCredential = "different-id"))
    }
}
