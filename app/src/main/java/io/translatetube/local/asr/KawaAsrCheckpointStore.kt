package io.translatetube.local.asr

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Durable per-upload checkpoints for Kawa's independently billable chunk requests.
 * Filenames hash source/config identity and actual chunk PCM bytes; secrets and URLs are not stored.
 */
internal object KawaAsrCheckpointStore {
    internal data class CachedChunk(
        val cues: List<Triple<Long, Long, String>>,
        val language: String,
    )

    private const val VERSION = 1
    private const val MAX_FILE_BYTES = 16L * 1024L * 1024L
    private const val MAX_CUES = 200_000
    private const val MAX_TEXT_CHARS = 10_000
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val PARTIAL_MAX_AGE_MS = 6L * 60L * 60L * 1_000L
    private val digestPattern = Regex("^[0-9a-f]{64}$")

    fun fileFor(directory: File, identity: String, chunkIndex: Int, audioDigest: String): File {
        require(identity.isNotBlank()) { "Kawa 检查点身份不能为空" }
        require(chunkIndex >= 0) { "Kawa 分段编号无效" }
        require(digestPattern.matches(audioDigest)) { "Kawa 音频指纹格式无效" }
        val identityDigest = sha256(identity).take(24)
        return File(directory, "kawa-${identityDigest}-${chunkIndex}-${audioDigest.take(24)}.json")
    }

    /** Hash exactly one provider upload range, with a bounded buffer and cancellation checks. */
    fun digestAudioRange(
        file: File,
        offsetBytes: Long,
        sizeBytes: Long,
        checkActive: () -> Unit = {},
    ): String {
        require(file.isFile && offsetBytes >= 0L && sizeBytes > 0L) {
            "Kawa 音频检查点区域无效"
        }
        require(offsetBytes <= file.length() && sizeBytes <= file.length() - offsetBytes) {
            "Kawa 音频检查点区域超出文件范围"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${offsetBytes}|${sizeBytes}|".toByteArray(Charsets.UTF_8))
        val buffer = ByteArray(64 * 1024)
        RandomAccessFile(file, "r").use { input ->
            input.seek(offsetBytes)
            var remaining = sizeBytes
            while (remaining > 0L) {
                checkActive()
                val count = minOf(buffer.size.toLong(), remaining).toInt()
                input.readFully(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
        }
        checkActive()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun read(
        file: File,
        audioDigest: String,
        chunkIndex: Int,
        range: CloudTranscription.KawaChunkRange,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): CachedChunk? {
        if (!file.isFile) return null
        if (file.length() !in 1L..MAX_FILE_BYTES || !digestPattern.matches(audioDigest)) {
            file.delete()
            return null
        }
        val cached = try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            if (json.optInt("version", -1) != VERSION ||
                json.optString("audioDigest") != audioDigest ||
                json.optInt("chunkIndex", -1) != chunkIndex ||
                json.optLong("offsetBytes", Long.MIN_VALUE) != range.offsetBytes ||
                json.optLong("sizeBytes", Long.MIN_VALUE) != range.sizeBytes ||
                json.optLong("ownedStartMs", Long.MIN_VALUE) != range.ownedStartMs ||
                json.optLong("ownedEndMs", Long.MIN_VALUE) != range.ownedEndMs ||
                json.optLong("durationMs", Long.MIN_VALUE) != durationMs
            ) throw IllegalStateException("Kawa 检查点元数据不匹配")

            val rows = json.optJSONArray("cues")
                ?: throw IllegalStateException("Kawa 检查点缺少字幕列表")
            if (rows.length() > MAX_CUES) throw IllegalStateException("Kawa 检查点字幕数量异常")
            val cues = ArrayList<Triple<Long, Long, String>>(rows.length())
            var previousStart = -1L
            val seenBounds = HashSet<Pair<Long, Long>>()
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index)
                    ?: throw IllegalStateException("Kawa 检查点字幕格式无效")
                val start = row.optLong("s", Long.MIN_VALUE)
                val end = row.optLong("e", Long.MIN_VALUE)
                val text = row.optString("t").trim()
                if (start < range.ownedStartMs || start >= range.ownedEndMs ||
                    end <= start || end > durationMs ||
                    text.isBlank() || text.length > MAX_TEXT_CHARS ||
                    start < previousStart || !seenBounds.add(start to end)
                ) throw IllegalStateException("Kawa 检查点字幕时间轴无效")
                cues += Triple(start, end, text)
                previousStart = start
            }
            CachedChunk(cues, json.optString("language").take(64))
        } catch (_: Exception) {
            file.delete()
            return null
        }
        if (nowMs >= file.lastModified()) file.setLastModified(nowMs)
        return cached
    }

    /** Install only a fully written checkpoint; abandoned .part files can never be restored. */
    fun write(
        file: File,
        audioDigest: String,
        chunkIndex: Int,
        range: CloudTranscription.KawaChunkRange,
        durationMs: Long,
        language: String,
        cues: List<Triple<Long, Long, String>>,
        checkActive: () -> Unit = {},
    ): Boolean {
        require(digestPattern.matches(audioDigest)) { "Kawa 音频指纹格式无效" }
        require(chunkIndex >= 0 && durationMs > 0L) { "Kawa 检查点元数据无效" }
        require(cues.size <= MAX_CUES) { "Kawa 检查点字幕数量超过上限" }
        val rows = JSONArray()
        val sortedCues = cues.sortedWith(compareBy<Triple<Long, Long, String>> { it.first }.thenBy { it.second })
        for ((index, cue) in sortedCues.withIndex()) {
            if ((index and 0xff) == 0) checkActive()
            require(
                cue.first >= range.ownedStartMs && cue.first < range.ownedEndMs &&
                    cue.second > cue.first && cue.second <= durationMs &&
                    cue.third.isNotBlank() && cue.third.length <= MAX_TEXT_CHARS
            ) { "Kawa 分段字幕不符合持久化范围" }
            rows.put(JSONObject().put("s", cue.first).put("e", cue.second).put("t", cue.third))
        }
        val json = JSONObject()
            .put("version", VERSION)
            .put("audioDigest", audioDigest)
            .put("chunkIndex", chunkIndex)
            .put("offsetBytes", range.offsetBytes)
            .put("sizeBytes", range.sizeBytes)
            .put("ownedStartMs", range.ownedStartMs)
            .put("ownedEndMs", range.ownedEndMs)
            .put("durationMs", durationMs)
            .put("language", language.take(64))
            .put("cues", rows)
            .toString()
        val payload = json.toByteArray(Charsets.UTF_8)
        if (payload.size.toLong() > MAX_FILE_BYTES) return false
        file.parentFile?.mkdirs()
        val staged = File.createTempFile("kawa-checkpoint-", ".part", file.parentFile)
        var installed = false
        try {
            FileOutputStream(staged).use { output ->
                output.write(payload)
                output.flush()
                output.fd.sync()
            }
            checkActive()
            if (file.exists() && !file.delete()) {
                throw IllegalStateException("无法替换旧的 Kawa 转写检查点")
            }
            if (!staged.renameTo(file)) throw IllegalStateException("无法提交 Kawa 转写检查点")
            installed = true
            return true
        } finally {
            if (!installed) runCatching { staged.delete() }
        }
    }

    /** Remove only expired Kawa-owned result files and abandoned atomic-write stages. */
    fun cleanupStale(directory: File, nowMs: Long = System.currentTimeMillis()): Int {
        val files = directory.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (!file.isFile) continue
            val isOwned = file.name.startsWith("kawa-") && file.name.endsWith(".json")
            val isPartial = file.name.startsWith("kawa-checkpoint-") && file.name.endsWith(".part")
            if (!isOwned && !isPartial) continue
            val ageLimit = if (isPartial) PARTIAL_MAX_AGE_MS else MAX_AGE_MS
            val modified = file.lastModified()
            if (modified > 0L && nowMs >= modified && nowMs - modified > ageLimit && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
