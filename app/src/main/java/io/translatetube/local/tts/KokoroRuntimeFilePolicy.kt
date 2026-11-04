package io.translatetube.local.tts

import java.io.File
import java.security.MessageDigest

/** Android-free validation of the complete file set used by the app's sherpa-onnx Kokoro config. */
internal object KokoroRuntimeFilePolicy {
    private const val MIN_CORE_BYTES = 1L * 1024 * 1024
    // Hash modest support files for content identity, but never read the huge ONNX/voice blobs.
    private const val MAX_CONTENT_HASH_BYTES = 8L * 1024 * 1024
    private val minimumBytes = linkedMapOf(
        "model.int8.onnx" to MIN_CORE_BYTES,
        "voices.bin" to MIN_CORE_BYTES,
        "tokens.txt" to 512L,
        "lexicon-zh.txt" to 1L * 1024 * 1024,
        "phone-zh.fst" to 32L * 1024,
        "date-zh.fst" to 20L * 1024,
        "number-zh.fst" to 32L * 1024,
    )
    private val requiredDirectories = listOf("espeak-ng-data")
    private val signatureRoots = minimumBytes.keys.toList() + requiredDirectories

    fun isValidRequiredFile(name: String, file: File): Boolean =
        file.isFile && file.length() >= (minimumBytes[name] ?: 1L)

    fun missingRuntimeFiles(dir: File): List<String> {
        val missing = mutableListOf<String>()
        for ((name, _) in minimumBytes) {
            if (!isValidRequiredFile(name, File(dir, name))) missing += name
        }
        for (name in requiredDirectories) {
            val directory = File(dir, name)
            if (!directory.isDirectory || directory.walkTopDown().none { it.isFile }) {
                missing += "$name/"
            }
        }
        return missing
    }

    /** Fingerprint metadata only; don't hash hundreds of MB of ONNX payload on every lookup. */
    fun cacheSignature(dir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in signatureRoots) {
            val root = File(dir, name)
            digest.update(
                "$name|${root.exists()}|${root.isDirectory}|${root.length()}|${root.lastModified()}\n"
                    .toByteArray(Charsets.UTF_8)
            )
            if (root.isDirectory) {
                root.walkTopDown().sortedBy { it.relativeTo(dir).path }.forEach { entry ->
                    digest.update(
                        "${entry.relativeTo(dir).path}|${entry.isDirectory}|${entry.length()}|${entry.lastModified()}\n"
                            .toByteArray(Charsets.UTF_8)
                    )
                }
            } else if (root.isFile && root.length() <= MAX_CONTENT_HASH_BYTES) {
                // Size/mtime can be preserved by file-copy tools or a restore. Content hashing
                // protects small lexicon/FST/token files from colliding with stale PCM identities.
                digest.update("content:".toByteArray(Charsets.UTF_8))
                root.inputStream().buffered().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
                digest.update(byteArrayOf(0))
            }
        }
        return digest.digest().take(10).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
