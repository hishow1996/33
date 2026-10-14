package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveFileLeaseRegistryTest {
    @Test
    fun keepsSharedCacheFileLeasedUntilEveryReaderFinishes() {
        val leases = ActiveFileLeaseRegistry()
        val path = "/cache/asr_audio/video.bin"

        leases.acquire(path)
        leases.acquire(path)
        assertEquals(2, leases.leaseCount(path))
        assertTrue(leases.contains(path))

        leases.release(path)
        assertEquals(1, leases.leaseCount(path))
        assertTrue(leases.contains(path))

        leases.release(path)
        assertEquals(0, leases.leaseCount(path))
        assertFalse(leases.contains(path))
    }

    @Test
    fun ignoresBlankPathsAndExtraReleases() {
        val leases = ActiveFileLeaseRegistry()

        leases.acquire(null)
        leases.acquire(" ")
        leases.release(null)
        leases.release("/cache/not-leased.bin")

        assertEquals(0, leases.leaseCount("/cache/not-leased.bin"))
        assertFalse(leases.contains("/cache/not-leased.bin"))
    }
}
