package io.translatetube.local.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PiperTtsCacheIdentityPolicyTest {
    @Test
    fun sameEspeakDataProducesStableSignature() {
        val root = Files.createTempDirectory("piper-espeak-signature").toFile()
        try {
            File(root, "voices").mkdirs()
            File(root, "voices/en").writeText("voice-data")
            val first = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)
            val second = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)
            assertEquals(first, second)
            assertEquals(24, first.length)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedEspeakResourceChangesInvalidateSignatureEvenWhenSizeAndMtimeStayTheSame() {
        val root = Files.createTempDirectory("piper-espeak-content").toFile()
        try {
            val nested = File(root, "data/en")
            nested.parentFile?.mkdirs()
            nested.writeText("voice-one")
            val originalMtime = nested.lastModified()
            val original = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)

            nested.writeText("voice-two")
            nested.setLastModified(originalMtime)
            val changed = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)

            assertEquals("same test fixture length", 9L, nested.length())
            assertTrue(nested.lastModified() == originalMtime)
            assertNotEquals(original, changed)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun addingOrRemovingNestedRuntimeResourcesChangesSignature() {
        val root = Files.createTempDirectory("piper-espeak-tree").toFile()
        try {
            val first = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)
            val nested = File(root, "data/phondata")
            nested.parentFile?.mkdirs()
            nested.writeText("phonetics")
            val withResource = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)
            assertNotEquals(first, withResource)
            nested.delete()
            val afterRemoval = PiperTtsCacheIdentityPolicy.espeakDataSignature(root)
            // The now-empty "data" directory remains in the tree, so compare against the state
            // that had the file rather than assuming directory mtimes return to their old values.
            assertNotEquals(withResource, afterRemoval)
        } finally {
            root.deleteRecursively()
        }
    }
}
