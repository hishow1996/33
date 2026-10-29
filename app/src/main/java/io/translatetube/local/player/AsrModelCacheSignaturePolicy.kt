package io.translatetube.local.player

import java.security.MessageDigest

/** Produces a bounded stable identity from model files actually loaded by local ASR. */
internal object AsrModelCacheSignaturePolicy {
    fun fingerprint(modelId: String, files: Map<String, String>): String {
        val canonical = buildString {
            append(modelId.trim().lowercase())
            append('\n')
            files.toSortedMap().forEach { (name, metadata) ->
                append(name)
                append('=')
                append(metadata)
                append('\n')
            }
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .take(12)
            .joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
    }
}
