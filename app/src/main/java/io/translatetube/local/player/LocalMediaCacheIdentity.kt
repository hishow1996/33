package io.translatetube.local.player

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Cache identity for a local media URI.
 *
 * Stable cache reuse requires the provider to expose both a non-empty size and a modification
 * timestamp. If it does not, use a per-open identity: it is safer to re-parse than to restore a
 * subtitle timeline from a file whose current contents cannot be verified.
 *
 * This is a metadata fingerprint, not a full-file cryptographic digest. It catches normal
 * replacement/update cases without reading a potentially multi-gigabyte video into the hash path.
 */
internal object LocalMediaCacheIdentity {
    fun identityKey(context: Context, uri: Uri): String {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = uri.path?.let(::File)
            val existing = file?.takeIf { it.isFile }
            return createIdentityKey(
                uri = uri.toString(),
                sizeBytes = existing?.length(),
                lastModifiedMs = existing?.lastModified(),
                displayName = existing?.name,
            )
        }

        var displayName: String? = null
        var sizeBytes: Long? = null
        var lastModifiedMs: Long? = null
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull()?.use { c ->
            if (c.moveToFirst()) {
                fun column(vararg names: String): Int =
                    names.firstNotNullOfOrNull { name ->
                        c.getColumnIndex(name).takeIf { it >= 0 }
                    } ?: -1

                val nameIndex = column(OpenableColumns.DISPLAY_NAME, "_display_name", "display_name")
                displayName = nameIndex.takeIf { it >= 0 }?.let { c.getString(it) }
                val sizeIndex = column(OpenableColumns.SIZE, "_size", "size")
                sizeBytes = sizeIndex.takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }

                val documentModifiedIndex = column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val mediaModifiedIndex = column(MediaStore.MediaColumns.DATE_MODIFIED, "date_modified")
                lastModifiedMs = when {
                    documentModifiedIndex >= 0 && !c.isNull(documentModifiedIndex) ->
                        c.getLong(documentModifiedIndex).takeIf { it > 0L }
                    mediaModifiedIndex >= 0 && !c.isNull(mediaModifiedIndex) ->
                        c.getLong(mediaModifiedIndex).takeIf { it > 0L }?.let { value ->
                            if (value < 100_000_000_000L) value * 1_000L else value
                        }
                    else -> null
                }
            }
        }

        // Some providers reject a null projection; retry only the portable OpenableColumns.
        if (sizeBytes == null || displayName.isNullOrBlank()) {
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )
            }.getOrNull()?.use { c ->
                if (c.moveToFirst()) {
                    val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayName.isNullOrBlank() && nameIndex >= 0) {
                        displayName = c.getString(nameIndex)
                    }
                    val sizeIndex = c.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeBytes == null && sizeIndex >= 0 && !c.isNull(sizeIndex)) {
                        sizeBytes = c.getLong(sizeIndex)
                    }
                }
            }
        }

        return createIdentityKey(
            uri = uri.toString(),
            sizeBytes = sizeBytes,
            lastModifiedMs = lastModifiedMs,
            displayName = displayName,
        )
    }

    internal fun createIdentityKey(
        uri: String,
        sizeBytes: Long?,
        lastModifiedMs: Long?,
        displayName: String?,
        unverifiedNonce: String = UUID.randomUUID().toString(),
    ): String {
        val hasReliableMetadata = sizeBytes != null && sizeBytes > 0L &&
            lastModifiedMs != null && lastModifiedMs > 0L
        val material = if (hasReliableMetadata) {
            listOf(
                uri,
                "size=$sizeBytes",
                "modified=$lastModifiedMs",
                "name=${displayName.orEmpty()}",
            ).joinToString("\n")
        } else {
            // Deliberately changes every time so an unverified URI cannot hit old source or READY caches.
            "unverified\n$uri\n$unverifiedNonce"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return if (hasReliableMetadata) "local-media-v1:$digest" else "local-media-unverified-v1:$digest"
    }
}
