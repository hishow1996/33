package io.translatetube.local.player

/** Byte-range and response-validation rules for low-memory, resumable ASR audio downloads. */
internal object AsrAudioSegmentPolicy {
    const val DEFAULT_CHUNK_BYTES = 4L * 1024L * 1024L

    data class ContentRange(
        val start: Long?,
        val endInclusive: Long?,
        val totalBytes: Long?,
    )

    fun rangeEnd(start: Long, chunkBytes: Long, totalBytes: Long?): Long {
        require(start >= 0L) { "range 起点不能为负数" }
        require(chunkBytes > 0L) { "range 分段大小必须大于 0" }
        val unboundedEnd = if (Long.MAX_VALUE - start < chunkBytes - 1L) {
            Long.MAX_VALUE
        } else {
            start + chunkBytes - 1L
        }
        return if (totalBytes != null && totalBytes > 0L) {
            minOf(unboundedEnd, totalBytes - 1L)
        } else {
            unboundedEnd
        }
    }

    /** Supports both "bytes 0-99/200" and the 416 form "bytes */200". */
    fun parseContentRange(header: String?): ContentRange? {
        if (header.isNullOrBlank()) return null
        val value = header.trim()
        val completed = Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""").matchEntire(value)
        if (completed != null) {
            val start = completed.groupValues[1].toLongOrNull() ?: return null
            val end = completed.groupValues[2].toLongOrNull() ?: return null
            val total = completed.groupValues[3].toLongOrNull()
            if (end < start || (total != null && (total <= end || total <= 0L))) return null
            return ContentRange(start, end, total)
        }
        val unsatisfied = Regex("""(?i)^bytes\s+\*/(\d+)$""").matchEntire(value)
            ?: return null
        val total = unsatisfied.groupValues[1].toLongOrNull() ?: return null
        if (total < 0L) return null
        return ContentRange(null, null, total)
    }

    fun canResumePartial(
        partialBytes: Long,
        maxBytes: Long,
        requestedSourceKey: String,
        currentUrlHash: String,
        savedSourceKey: String?,
        savedUrlHash: String?,
        etag: String?,
        lastModified: String?,
        rangeSupported: Boolean?,
    ): Boolean {
        if (partialBytes !in 1L..maxBytes) return false
        if (requestedSourceKey.isBlank() || savedSourceKey != requestedSourceKey) return false
        if (rangeSupported != true) return false
        if (savedUrlHash == currentUrlHash) return true
        // Signed URLs may rotate. Resume across URL changes only with a validator that can be
        // sent as If-Range; otherwise the two partial byte sequences cannot be proven identical.
        return !etag.isNullOrBlank() || !lastModified.isNullOrBlank()
    }

    /** Detect obvious web/error bodies without rejecting valid audio/octet-stream responses. */
    fun isClearlyNotAudio(contentType: String?, prefix: ByteArray): Boolean {
        val mime = contentType.orEmpty().substringBefore(';').trim().lowercase()
        if (mime == "text/html" || mime == "application/xhtml+xml" ||
            mime == "application/json" || mime == "text/json" ||
            mime == "application/vnd.apple.mpegurl" || mime == "application/x-mpegurl" ||
            mime == "audio/x-mpegurl"
        ) return true

        if (prefix.isEmpty()) return false
        val text = prefix.toString(Charsets.UTF_8)
            .filter { it == '\u0000' || it.isLetterOrDigit() || it.isWhitespace() ||
                it in "#<>/:;.=\\\"'{}_[],-" }
            .trimStart()
            .lowercase()
        if (text.startsWith("#extm3u") || text.startsWith("<!doctype html") ||
            text.startsWith("<html") || text.startsWith("<?xml")
        ) return true
        return text.startsWith("{") &&
            (text.contains("\"error\"") || text.contains("\"message\"") ||
                text.contains("\"status\""))
    }
}
