package io.translatetube.local.player

import org.json.JSONObject

/** Structural validation for ready-session snapshots before a legacy copy can be discarded. */
internal object ReadySessionSnapshotMigrationPolicy {
    private val keyHashPattern = Regex("^[0-9a-f]{32}$")
    private val fingerprintPattern = Regex("^[0-9a-f]{24}$")

    fun isUsableSnapshot(
        fileName: String,
        bytes: ByteArray,
        expectedVersion: Int,
        maxBytes: Long,
        maxLines: Int,
    ): Boolean {
        if (fileName.isBlank() || bytes.isEmpty() || bytes.size.toLong() > maxBytes) return false

        return runCatching {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            if (root.optInt("version", -1) != expectedVersion) return@runCatching false

            val keyHash = root.optString("keyHash")
            if (!keyHashPattern.matches(keyHash)) return@runCatching false

            // Snapshots written before identityHash was introduced are keyed by video alone.
            val identityHash = root.optString("identityHash", keyHash).ifBlank { keyHash }
            if (!keyHashPattern.matches(identityHash)) return@runCatching false

            val fingerprint = root.optString("fingerprint")
            if (!fingerprintPattern.matches(fingerprint)) return@runCatching false

            val ignoreTranscription = root.optBoolean("ignoreTranscription", false)
            val identityPart = if (identityHash == keyHash) "" else "${identityHash}_"
            val suffix = if (ignoreTranscription) "_ignore_asr.json" else "_include_asr.json"
            val expectedFileName = "ready_${keyHash}_${identityPart}${fingerprint}${suffix}"
            if (fileName != expectedFileName) return@runCatching false

            val state = root.optJSONObject("state") ?: return@runCatching false
            val lines = state.optJSONArray("lines") ?: return@runCatching false
            if (lines.length() !in 1..maxLines) return@runCatching false

            for (index in 0 until lines.length()) {
                val row = lines.optJSONObject(index) ?: return@runCatching false
                val startMs = row.optLong("s", Long.MIN_VALUE)
                val endMs = row.optLong("e", Long.MIN_VALUE)
                val text = row.optString("t")
                if (startMs < 0L || endMs <= startMs || text.isBlank()) {
                    return@runCatching false
                }
            }
            true
        }.getOrDefault(false)
    }
}
