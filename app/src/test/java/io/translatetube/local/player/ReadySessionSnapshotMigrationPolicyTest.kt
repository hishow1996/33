package io.translatetube.local.player

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadySessionSnapshotMigrationPolicyTest {
    private val keyHash = "0123456789abcdef0123456789abcdef"
    private val identityHash = "abcdef0123456789abcdef0123456789"
    private val fingerprint = "0123456789abcdef01234567"

    @Test
    fun acceptsACompleteSnapshotWithSeparateMediaIdentity() {
        val (fileName, bytes) = snapshot(identity = identityHash)

        assertTrue(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                fileName = fileName,
                bytes = bytes,
                expectedVersion = 1,
                maxBytes = 1024L,
                maxLines = 10,
            ),
        )
    }

    @Test
    fun acceptsLegacySnapshotWithoutSeparateIdentityHash() {
        val (fileName, bytes) = snapshot(identity = null)

        assertTrue(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                fileName = fileName,
                bytes = bytes,
                expectedVersion = 1,
                maxBytes = 1024L,
                maxLines = 10,
            ),
        )
    }

    @Test
    fun rejectsTruncatedOversizedAndWrongVersionSnapshots() {
        val (fileName, bytes) = snapshot()

        assertFalse(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                fileName, bytes.copyOf(bytes.size - 2), 1, 1024L, 10,
            ),
        )
        assertFalse(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                fileName, bytes, 1, bytes.size.toLong() - 1L, 10,
            ),
        )
        assertFalse(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                fileName, bytes, 2, 1024L, 10,
            ),
        )
    }

    @Test
    fun rejectsFilenameThatDoesNotMatchSnapshotIdentity() {
        val (fileName, bytes) = snapshot()

        assertFalse(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                fileName = fileName.replace(fingerprint, "aaaaaaaaaaaaaaaaaaaaaaaa"),
                bytes = bytes,
                expectedVersion = 1,
                maxBytes = 1024L,
                maxLines = 10,
            ),
        )
    }

    @Test
    fun rejectsInvalidSubtitleTimelineAndTooManyRows() {
        val (invalidTimelineName, invalidTimelineBytes) = snapshot(startMs = 500L, endMs = 400L)
        assertFalse(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                invalidTimelineName, invalidTimelineBytes, 1, 1024L, 10,
            ),
        )

        val (tooManyRowsName, tooManyRowsBytes) = snapshot(lineCount = 2)
        assertFalse(
            ReadySessionSnapshotMigrationPolicy.isUsableSnapshot(
                tooManyRowsName, tooManyRowsBytes, 1, 1024L, 1,
            ),
        )
    }

    private fun snapshot(
        identity: String? = keyHash,
        startMs: Long = 0L,
        endMs: Long = 100L,
        lineCount: Int = 1,
    ): Pair<String, ByteArray> {
        val identityPart = if (identity == null || identity == keyHash) "" else "${identity}_"
        val fileName = "ready_${keyHash}_${identityPart}${fingerprint}_include_asr.json"
        val lines = JSONArray()
        repeat(lineCount) { index ->
            val lineStartMs = startMs + index * 100L
            lines.put(
                JSONObject()
                    .put("s", lineStartMs)
                    .put("e", endMs + index * 100L)
                    .put("t", "hello"),
            )
        }
        val root = JSONObject()
            .put("version", 1)
            .put("keyHash", keyHash)
            .put("fingerprint", fingerprint)
            .put("ignoreTranscription", false)
            .put("state", JSONObject().put("lines", lines))
        if (identity != null && identity != keyHash) root.put("identityHash", identity)
        return fileName to root.toString().toByteArray(Charsets.UTF_8)
    }
}
