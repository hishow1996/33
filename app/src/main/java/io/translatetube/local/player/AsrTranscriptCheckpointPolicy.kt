package io.translatetube.local.player

import java.security.MessageDigest

/** Stable identity and bounds for partial Whisper transcripts tied to a PCM/VAD layout. */
internal object AsrTranscriptCheckpointPolicy {
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
    fun areRowsValid(
        rows: List<Triple<Long, Long, String>>,
        processedSegments: List<Pair<Long, Long>>,
    ): Boolean {
        if (rows.size > processedSegments.size) return false
        val segmentIndexes = HashMap<Pair<Long, Long>, Int>(processedSegments.size)
        for ((index, bounds) in processedSegments.withIndex()) {
            // Ambiguous bounds make a checkpoint impossible to validate unambiguously.
            if (segmentIndexes.put(bounds, index) != null) return false
        }
        var previousSegmentIndex = -1
        for ((start, end, text) in rows) {
            if (start < 0L || end <= start || text.isBlank()) return false
            val segmentIndex = segmentIndexes[start to end] ?: return false
            // Start timestamps alone do not order segments when boundaries share a start time.
            // Require the exact VAD segment order; this also rejects duplicates in one pass.
            if (segmentIndex <= previousSegmentIndex) return false
            previousSegmentIndex = segmentIndex
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
        savedSignature.isNotBlank() && savedSignature == expectedSignature &&
            expectedTotalSegments > 0 && savedTotalSegments == expectedTotalSegments &&
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
