package io.translatetube.local.tts

import java.io.File
import java.security.MessageDigest

/**
 * Fingerprints Piper's recursively consumed eSpeak data without hashing large binary assets.
 * Small runtime files are content-hashed to detect same-size replacements with preserved mtimes.
 */
internal object PiperTtsCacheIdentityPolicy {
    private const val MAX_CONTENT_HASH_BYTES = 1L * 1024L * 1024L

    fun espeakDataSignature(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("piper-espeak-data-v1\n".toByteArray(Charsets.UTF_8))
        digest.update(
            "${directory.exists()}|${directory.isDirectory}\n".toByteArray(Charsets.UTF_8),
        )
        if (directory.isDirectory) {
            directory.walkTopDown()
                .sortedBy { entry ->
                    if (entry == directory) "." else
                        entry.relativeTo(directory).path.replace(File.separatorChar, '/')
                }
                .forEach { entry ->
                    val relativePath = if (entry == directory) "." else
                        entry.relativeTo(directory).path.replace(File.separatorChar, '/')
                    digest.update(
                        "$relativePath|${entry.isDirectory}|${entry.length()}|${entry.lastModified()}\n"
                            .toByteArray(Charsets.UTF_8),
                    )
                    if (entry.isFile && entry.length() <= MAX_CONTENT_HASH_BYTES) {
                        digest.update("content:".toByteArray(Charsets.UTF_8))
                        entry.inputStream().buffered().use { input ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                digest.update(buffer, 0, count)
                            }
                        }
                        digest.update(byteArrayOf(0))
                    }
                }
        }
        return digest.digest().take(12).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
