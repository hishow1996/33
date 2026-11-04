package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaCacheIdentityTest {
    private fun key(
        size: Long? = 1_024L,
        modified: Long? = 10_000L,
        name: String? = "video.mp4",
        nonce: String = "nonce-a",
    ) = LocalMediaCacheIdentity.createIdentityKey(
        uri = "content://media/external/video/99",
        sizeBytes = size,
        lastModifiedMs = modified,
        displayName = name,
        unverifiedNonce = nonce,
    )

    @Test
    fun identicalUriAndMetadataProduceStableIdentity() {
        assertEquals(key(), key())
    }

    @Test
    fun sizeModificationTimeAndDisplayNameChangesInvalidateIdentity() {
        val original = key()
        assertNotEquals(original, key(size = 2_048L))
        assertNotEquals(original, key(modified = 20_000L))
        assertNotEquals(original, key(name = "replacement.mp4"))
    }

    @Test
    fun missingSizeOrModifiedTimeDisablesStableReuse() {
        val noSize = key(size = null)
        val noModifiedTime = key(modified = null)
        assertTrue(noSize.startsWith("local-media-unverified-v1:"))
        assertTrue(noModifiedTime.startsWith("local-media-unverified-v1:"))
        assertNotEquals(noSize, key(size = null, nonce = "nonce-b"))
        assertNotEquals(noModifiedTime, key(modified = null, nonce = "nonce-b"))
    }

    @Test
    fun emptyFilesAreNotEligibleForStableReuse() {
        assertTrue(key(size = 0L).startsWith("local-media-unverified-v1:"))
    }
}
