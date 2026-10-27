package io.translatetube.local.player

import java.security.MessageDigest

/** Stable identity and bounds for partial Whisper transcripts tied to a PCM/VAD layout. */
internal object AsrTranscriptCheckpointPolicy {
    /** Checkpoint groups align with the pipeline's periodic progress-save boundary. */
    const val SEGMENTS_PER_CHECKPOINT = 3

    fun chunkCacheKeyPrefix(partialCacheKey: String): String {
        require(partialCacheKey.isNotBlank()) { "ASR 检查点缓存键不能为空" }
        return "$partialCacheKey|chunk:"
    }

    fun chunkCacheKey(partialCacheKey: String, startSegment: Int): String {
        require(startSegment >= 0 && startSegment % SEGMENTS_PER_CHECKPOINT == 0) {
            "ASR 检查点分段起点无效"
        }
        return chunkCacheKeyPrefix(partialCacheKey) + startSegment
    }

    private const val MAX_CHECKPOINT_ROWS = 100_000
    private const val MAX_PROCESSED_SEGMENTS = 100_000
    private val signaturePattern = Regex("^[0-9a-f]{32}$")

    fun signature(pcmSamples: FloatArray, segments: List<Pair<Long, Long>>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${pcmSamples.size}|16000|${segments.size}|".toByteArray(Charsets.UTF_8))

        // Sparse, evenly spaced PCM anchors distinguish same-length tracks without hashing every
        // sample on older devices. The VAD boundaries below add another inexpensive identity check.
        val stride = maxOf(1, pcmSamples.size / 1_024)
        val sampleBuffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (index in pcmSamples.indices step stride) {
            sampleBuffer.clear()
            sampleBuffer.putInt(pcmSamples[index].toRawBits())
            digest.update(sampleBuffer.array())
        }
        val lastSampleIndex = pcmSamples.lastIndex
        if (lastSampleIndex >= 0 && lastSampleIndex % stride != 0) {
            sampleBuffer.clear()
            sampleBuffer.putInt(pcmSamples[lastSampleIndex].toRawBits())
            digest.update(sampleBuffer.array())
        }
        segments.forEach { (start, end) ->
            digest.update("${start},${end};".toByteArray(Charsets.UTF_8))
        }
        return digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }


    fun signature(
        pcmFile: io.translatetube.local.asr.AudioExtract.PcmFile,
        segments: List<Pair<Long, Long>>,
    ): String = signature(pcmFile, segments) {}

    internal fun signature(
        pcmFile: io.translatetube.local.asr.AudioExtract.PcmFile,
        segments: List<Pair<Long, Long>>,
        checkActive: () -> Unit,
    ): String {
        checkActive()
        require(io.translatetube.local.asr.PcmTempFilePolicy.hasCompletePcm16Payload(
            pcmFile.file, pcmFile.sampleCount,
        )) {
            "PCM 临时文件缺失、为空或长度与声明的采样数不匹配"
        }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("${pcmFile.sampleCount}|${pcmFile.sampleRate}|${segments.size}|".toByteArray(Charsets.UTF_8))
        // Use the same sparse, evenly spaced normalized PCM anchors as the in-memory signature.
        val stride = maxOf(1L, pcmFile.sampleCount / 1_024L)
        val sampleBuffer = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        java.io.RandomAccessFile(pcmFile.file, "r").use { input ->
            var index = 0L
            var sampledAnchors = 0
            while (index < pcmFile.sampleCount) {
                if ((sampledAnchors and 0x3f) == 0) checkActive()
                input.seek(index * 2L)
                val raw = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                val normalizedSample = raw.toShort() / 32768f
                sampleBuffer.clear()
                sampleBuffer.putInt(normalizedSample.toRawBits())
                digest.update(sampleBuffer.array())
                index += stride
                sampledAnchors++
            }
            val lastSampleIndex = pcmFile.sampleCount - 1L
            if (lastSampleIndex >= 0L && lastSampleIndex % stride != 0L) {
                checkActive()
                input.seek(lastSampleIndex * 2L)
                val raw = input.readUnsignedByte() or (input.readUnsignedByte() shl 8)
                val normalizedSample = raw.toShort() / 32768f
                sampleBuffer.clear()
                sampleBuffer.putInt(normalizedSample.toRawBits())
                digest.update(sampleBuffer.array())
            }
        }
        checkActive()
        segments.forEachIndexed { segmentIndex, (start, end) ->
            if ((segmentIndex and 0x3ff) == 0) checkActive()
            digest.update("${start},${end};".toByteArray(Charsets.UTF_8))
        }
        checkActive()
        return digest.digest().take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /**
     * Checkpoint rows are written once per processed speech segment, and only non-empty results
     * are stored. Validate exact segment bounds, ordering and uniqueness in linear time so a
     * malformed cache cannot inject duplicate captions or make recovery quadratic on long tracks.
     */
    /**
     * Checkpoint rows may now contain multiple token-aligned captions for each processed VAD
     * segment. Every caption must remain inside a completed segment and the rows must be ordered.
     */
    fun areRowsValid(
        rows: List<Triple<Long, Long, String>>,
        processedSegments: List<Pair<Long, Long>>,
    ): Boolean {
        // The recovery reader is bounded to 100k rows as well; reject hostile/corrupt metadata
        // before allocating the duplicate-detection sets below.
        if (rows.size > MAX_CHECKPOINT_ROWS || processedSegments.size > MAX_PROCESSED_SEGMENTS) return false
        if (processedSegments.isEmpty()) return rows.isEmpty()
        val uniqueSegments = HashSet<Pair<Long, Long>>(processedSegments.size)
        var previousSegmentStart = -1L
        var previousSegmentEnd = -1L
        for ((start, end) in processedSegments) {
            // The row validator advances a single cursor by segment end time. Reject malformed
            // checkpoint metadata whose segment order would make that linear scan skip a valid
            // segment or accept rows from the wrong segment. Equal starts are permitted for
            // overlapping VAD windows, provided their end bounds remain monotonic.
            if (end <= start || start < previousSegmentStart || end < previousSegmentEnd ||
                !uniqueSegments.add(start to end)
            ) return false
            previousSegmentStart = start
            previousSegmentEnd = end
        }

        var segmentCursor = 0
        var previousSegmentIndex = -1
        var previousStart = -1L
        var previousEnd = -1L
        val seenBounds = HashSet<Pair<Long, Long>>()
        for ((start, end, text) in rows) {
            if (start < 0L || end <= start || text.isBlank()) return false

            // Rows are chronological, so advance through VAD segments once rather than rescanning
            // all completed segments for every fine-grained caption on long videos.
            while (segmentCursor < processedSegments.size &&
                end > processedSegments[segmentCursor].second
            ) {
                segmentCursor++
            }
            if (segmentCursor >= processedSegments.size) return false
            val (segmentStart, segmentEnd) = processedSegments[segmentCursor]
            if (start < segmentStart || end > segmentEnd) return false
            if (segmentCursor < previousSegmentIndex) return false
            if (segmentCursor == previousSegmentIndex &&
                (start < previousStart || (start == previousStart && end < previousEnd))
            ) return false
            // Identical intervals can accidentally duplicate a caption during resume.
            if (!seenBounds.add(start to end)) return false

            previousSegmentIndex = segmentCursor
            previousStart = start
            previousEnd = end
        }
        return true
    }

    fun canResume(
        savedSignature: String,
        expectedSignature: String,
        savedTotalSegments: Int,
        expectedTotalSegments: Int,
        processedSegments: Int,
    ): Boolean =
        signaturePattern.matches(savedSignature) &&
            signaturePattern.matches(expectedSignature) &&
            savedSignature == expectedSignature &&
            expectedTotalSegments > 0 &&
            expectedTotalSegments <= MAX_PROCESSED_SEGMENTS &&
            savedTotalSegments == expectedTotalSegments &&
            processedSegments in 0..expectedTotalSegments
}

internal object AsrMemoryPolicy {
    private const val ABSOLUTE_AVAILABLE_THRESHOLD_BYTES = 384L * 1024L * 1024L
    private const val AVAILABLE_MEMORY_RATIO_THRESHOLD = 0.10
    private const val LOW_MEMORY_CLASS_MB = 256

    fun shouldReleaseRecognizer(
        isLowRamDevice: Boolean,
        memoryClassMb: Int,
        systemLowMemory: Boolean,
        availableMemoryBytes: Long,
        totalMemoryBytes: Long,
    ): Boolean {
        if (isLowRamDevice || systemLowMemory || memoryClassMb in 1..LOW_MEMORY_CLASS_MB) return true
        if (availableMemoryBytes <= 0L || totalMemoryBytes <= 0L) return false
        return availableMemoryBytes < ABSOLUTE_AVAILABLE_THRESHOLD_BYTES ||
            availableMemoryBytes.toDouble() / totalMemoryBytes.toDouble() < AVAILABLE_MEMORY_RATIO_THRESHOLD
    }
}
