package io.translatetube.local.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DubbingCachePathTest {
    @Test
    fun contentUriKeysMapToOneSafeDirectoryName() {
        val name = DubbingCachePath.directoryName("local:content://provider/document/video%3A42")
        assertEquals(32, name.length)
        assertTrue(name.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(name, DubbingCachePath.directoryName("local:content://provider/document/video%3A42"))
    }

    @Test
    fun differentVideoKeysDoNotShareCacheDirectoryNames() {
        assertNotEquals(
            DubbingCachePath.directoryName("youtube:abc"),
            DubbingCachePath.directoryName("youtube:abd"),
        )
    }
}
