package io.translatetube.local.translate

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable identity for a user-configured API translation profile.
 *
 * API model settings live in Room, whereas PlaybackService watches SharedPreferences for engine
 * changes. This digest safely mirrors the active profile configuration into that watcher.
 */
internal object ApiProfileConfigFingerprint {
    fun create(
        profileId: Long,
        type: String,
        baseUrl: String,
        model: String,
        apiKey: String,
        region: String,
        secondaryCredential: String = "",
    ): String {
        val canonical = listOf(
            profileId.toString(),
            type.trim().uppercase(Locale.ROOT),
            baseUrl.trim().trimEnd('/'),
            model.trim(),
            region.trim().lowercase(Locale.ROOT),
            sha256(apiKey),
            sha256(secondaryCredential),
        ).joinToString("\n")
        return sha256(canonical)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
