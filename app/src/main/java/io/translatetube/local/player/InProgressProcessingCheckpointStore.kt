package io.translatetube.local.player

import android.content.Context
import android.util.AtomicFile
import io.translatetube.local.subs.SubLine
import io.translatetube.local.yt.SubtitleTrackOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Durable checkpoint for an interrupted, not-yet-completed processing run.
 *
 * The file name contains only one-way media/session hashes. Raw media identity, cookies and API
 * credentials are not stored as identity fields. Source subtitle-track URLs remain part of the
 * persisted track metadata for the existing subtitle workflows. PCM data stays in the dubbing cache.
 */
internal data class InProgressProcessingCheckpoint(
    val state: PlayerUiState,
    val fingerprints: EngineConfigFingerprints,
    val savedAt: Long,
    val requireFreshDubbing: Boolean = false,
    val requireFreshTranslation: Boolean = false,
    val youtubeSubtitleMode: String? = null,
)

internal enum class InProgressCheckpointSaveStatus {
    SAVED,
    UNCHANGED,
    REJECTED,
    FAILED,
}

internal data class InProgressCheckpointSaveResult(
    val status: InProgressCheckpointSaveStatus,
    val reason: String? = null,
    val stage: String? = null,
    val uncompressedBytes: Long? = null,
    val compressedBytes: Long? = null,
    val elapsedMs: Long = 0L,
    val failure: Throwable? = null,
    val cleanupFailure: Throwable? = null,
)

internal enum class InProgressCheckpointLoadStatus {
    RESTORED,
    MISSING,
    DISCARDED,
    FAILED,
}

internal data class InProgressCheckpointLoadResult(
    val status: InProgressCheckpointLoadStatus,
    val checkpoint: InProgressProcessingCheckpoint? = null,
    val reason: String? = null,
    val stage: String? = null,
    val fileBytes: Long? = null,
    val uncompressedBytes: Long? = null,
    val ageMs: Long? = null,
    val failure: Throwable? = null,
)

internal object InProgressProcessingCheckpointStore {
    private const val DIRECTORY_NAME = "in_progress_processing"
    // Versions 2–5 added gzip/restart metadata; version 6 added content fingerprints.
    // Version 7 also fingerprints terminal original-audio translation fallbacks.
    private const val VERSION = 7
    private const val MAX_FILE_BYTES = 24L * 1024L * 1024L
    private const val MAX_UNCOMPRESSED_BYTES = 64L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 96L * 1024L * 1024L
    private const val MAX_FILES = 24
    private const val MAX_LINES = 120_000
    private const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L
    private const val MAX_FUTURE_CLOCK_SKEW_MS = 24L * 60L * 60L * 1000L

    private data class CachedWrite(
        val fingerprint: String,
        val fileLength: Long,
        val lastModified: Long,
    )

    // Process-local cache only. Every hit is also checked against the current file stamp, so an
    // externally removed/replaced file cannot be mistaken for a durable checkpoint.
    private val lastSavedByPath = linkedMapOf<String, CachedWrite>()

    @Synchronized
    fun save(
        context: Context,
        videoKey: String,
        identityKey: String,
        state: PlayerUiState,
        fingerprints: EngineConfigFingerprints,
        requireFreshDubbing: Boolean = false,
        requireFreshTranslation: Boolean = false,
        youtubeSubtitleMode: String? = null,
    ): InProgressCheckpointSaveResult {
        val startedAtNs = System.nanoTime()
        var stage = "validation"
        var uncompressedBytes: Long? = null
        var compressedBytes: Long? = null

        fun result(
            status: InProgressCheckpointSaveStatus,
            reason: String? = null,
            failure: Throwable? = null,
            cleanupFailure: Throwable? = null,
        ) = InProgressCheckpointSaveResult(
            status = status,
            reason = reason,
            stage = stage,
            uncompressedBytes = uncompressedBytes,
            compressedBytes = compressedBytes,
            elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L).coerceAtLeast(0L),
            failure = failure,
            cleanupFailure = cleanupFailure,
        )

        if (!isValidCheckpoint(videoKey, identityKey, state, fingerprints)) {
            return result(
                InProgressCheckpointSaveStatus.REJECTED,
                reason = "invalid_checkpoint_state",
            )
        }

        return try {
            stage = "directory"
            val directory = ensureDirectory(context)
            val keyHash = sha256(videoKey)
            val identityHash = sha256(identityKey)
            val targetFile = checkpointFile(directory, keyHash, identityHash)

            stage = "fingerprint"
            val normalizedYoutubeSubtitleMode = youtubeSubtitleMode?.takeIf { it.isNotBlank() }
            val contentFingerprint = contentFingerprint(
                keyHash = keyHash,
                identityHash = identityHash,
                fingerprints = fingerprints,
                requireFreshDubbing = requireFreshDubbing,
                requireFreshTranslation = requireFreshTranslation,
                youtubeSubtitleMode = normalizedYoutubeSubtitleMode,
                state = state,
            )

            if (isUnchanged(targetFile, contentFingerprint)) {
                return result(
                    InProgressCheckpointSaveStatus.UNCHANGED,
                    reason = "identical_content",
                )
            }

            stage = "serialization"
            val stateObject = encodeState(state)
            val root = JSONObject()
                .put("version", VERSION)
                .put("keyHash", keyHash)
                .put("identityHash", identityHash)
                .put("savedAt", System.currentTimeMillis())
                .put("contentFingerprint", contentFingerprint)
                .put("transcriptionFingerprint", fingerprints.transcription)
                .put("translationFingerprint", fingerprints.translation)
                .put("dubbingFingerprint", fingerprints.dubbing)
                .put("requireFreshDubbing", requireFreshDubbing)
                .put("requireFreshTranslation", requireFreshTranslation)
                .put("youtubeSubtitleMode", normalizedYoutubeSubtitleMode ?: JSONObject.NULL)
                .put("state", stateObject)

            val jsonBytes = root.toString().toByteArray(Charsets.UTF_8)
            uncompressedBytes = jsonBytes.size.toLong()
            if (jsonBytes.isEmpty()) {
                return result(
                    InProgressCheckpointSaveStatus.REJECTED,
                    reason = "empty_serialized_payload",
                )
            }
            if (uncompressedBytes!! > MAX_UNCOMPRESSED_BYTES) {
                return result(
                    InProgressCheckpointSaveStatus.REJECTED,
                    reason = "uncompressed_size_limit",
                )
            }

            stage = "compression"
            val bytes = ByteArrayOutputStream().use { compressed ->
                GZIPOutputStream(compressed).use { gzip -> gzip.write(jsonBytes) }
                compressed.toByteArray()
            }
            compressedBytes = bytes.size.toLong()
            if (bytes.isEmpty()) {
                return result(
                    InProgressCheckpointSaveStatus.REJECTED,
                    reason = "empty_compressed_payload",
                )
            }
            if (compressedBytes!! > MAX_FILE_BYTES) {
                return result(
                    InProgressCheckpointSaveStatus.REJECTED,
                    reason = "compressed_size_limit",
                )
            }

            stage = "write"
            val atomic = AtomicFile(targetFile)
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(bytes)
                stream.flush()
                // Make payload bytes durable before AtomicFile publishes the replacement.
                stream.fd.sync()
                atomic.finishWrite(stream)
                stream = null
            } catch (writeError: Exception) {
                stream?.let { failed ->
                    runCatching { atomic.failWrite(failed) }
                        .onFailure { restoreError -> writeError.addSuppressed(restoreError) }
                }
                lastSavedByPath.remove(targetFile.absolutePath)
                throw writeError
            }

            rememberSavedFile(targetFile, contentFingerprint)

            stage = "prune"
            var cleanupFailure: Throwable? = null
            try {
                prune(directory, keep = targetFile, now = System.currentTimeMillis())
            } catch (error: Exception) {
                // The checkpoint is already committed. A maintenance failure must not be reported
                // as a failed save or trigger the caller to repeat expensive processing.
                cleanupFailure = error
            }

            result(
                InProgressCheckpointSaveStatus.SAVED,
                reason = "written",
                cleanupFailure = cleanupFailure,
            )
        } catch (error: Exception) {
            result(
                InProgressCheckpointSaveStatus.FAILED,
                reason = "checkpoint_" + stage + "_failed",
                failure = error,
            )
        }
    }

    /** Compatibility wrapper for callers that only need the restored value. */
    @Synchronized
    fun load(
        context: Context,
        videoKey: String,
        identityKey: String,
    ): InProgressProcessingCheckpoint? =
        loadWithResult(context, videoKey, identityKey).checkpoint

    @Synchronized
    fun loadWithResult(
        context: Context,
        videoKey: String,
        identityKey: String,
    ): InProgressCheckpointLoadResult {
        if (!isSupportedKey(videoKey) || identityKey.isBlank()) {
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.MISSING,
                reason = "unsupported_identity",
            )
        }

        val keyHash = sha256(videoKey)
        val identityHash = sha256(identityKey)
        val file = checkpointFile(directory(context), keyHash, identityHash)
        val backup = File(file.absolutePath + ".bak")
        if (!file.isFile && !backup.isFile) {
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.MISSING,
                reason = "not_found",
            )
        }
        // AtomicFile.openRead() prefers the backup whenever it exists, even if a new base file
        // is also present after an interrupted replacement. Apply the same precedence here.
        val onDiskBytes = (if (backup.isFile) backup.length() else file.length()).coerceAtLeast(0L)
        if (onDiskBytes > MAX_FILE_BYTES) {
            discard(file)
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.DISCARDED,
                reason = "compressed_size_limit",
                stage = "read",
                fileBytes = onDiskBytes,
            )
        }

        var storedBytes: ByteArray
        try {
            // AtomicFile.openRead() restores a backup left by an interrupted replacement before
            // opening the base file. Do not reject a missing/short base file before this call.
            storedBytes = AtomicFile(file).openRead().use { input ->
                readBounded(input, MAX_FILE_BYTES)
            }
        } catch (error: Exception) {
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.FAILED,
                reason = "checkpoint_read_failed",
                stage = "read",
                fileBytes = onDiskBytes,
                failure = error,
            )
        }

        if (storedBytes.isEmpty()) {
            discard(file)
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.DISCARDED,
                reason = "empty_file",
                stage = "read",
                fileBytes = 0L,
            )
        }

        val jsonBytes = try {
            if (
                storedBytes.size >= 2 &&
                storedBytes[0] == 0x1f.toByte() &&
                storedBytes[1] == 0x8b.toByte()
            ) {
                GZIPInputStream(ByteArrayInputStream(storedBytes)).use { gzip ->
                    readBounded(gzip, MAX_UNCOMPRESSED_BYTES)
                }
            } else {
                // Backward compatibility with plain-JSON checkpoints written by version 1.
                require(storedBytes.size.toLong() <= MAX_UNCOMPRESSED_BYTES) {
                    "处理中间检查点文件超过解压大小限制"
                }
                storedBytes
            }
        } catch (error: Exception) {
            discard(file)
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.FAILED,
                reason = "checkpoint_decompression_failed",
                stage = "decompression",
                fileBytes = storedBytes.size.toLong(),
                failure = error,
            )
        }

        if (jsonBytes.isEmpty()) {
            discard(file)
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.DISCARDED,
                reason = "empty_payload",
                stage = "decompression",
                fileBytes = storedBytes.size.toLong(),
                uncompressedBytes = 0L,
            )
        }

        val root = try {
            JSONObject(String(jsonBytes, Charsets.UTF_8))
        } catch (error: Exception) {
            discard(file)
            return InProgressCheckpointLoadResult(
                InProgressCheckpointLoadStatus.FAILED,
                reason = "checkpoint_json_parse_failed",
                stage = "json_parse",
                fileBytes = storedBytes.size.toLong(),
                uncompressedBytes = jsonBytes.size.toLong(),
                failure = error,
            )
        }

        val version = root.optInt("version", -1)
        if (
            version !in 1..VERSION ||
            root.optString("keyHash") != keyHash ||
            root.optString("identityHash") != identityHash
        ) {
            discard(file)
            return discarded("metadata_mismatch", storedBytes.size, jsonBytes.size)
        }

        val now = System.currentTimeMillis()
        val savedAt = root.optLong("savedAt", 0L)
        if (InProgressProcessingCheckpointStoragePolicy.isExpired(
                savedAt = savedAt,
                nowMs = now,
                maxAgeMs = MAX_AGE_MS,
                maxFutureSkewMs = MAX_FUTURE_CLOCK_SKEW_MS,
            )
        ) {
            discard(file)
            return discarded("checkpoint_expired_or_timestamp_invalid", storedBytes.size, jsonBytes.size)
        }

        val stateObject = root.optJSONObject("state")
        if (stateObject == null) {
            discard(file)
            return discarded("state_missing", storedBytes.size, jsonBytes.size)
        }

        if (stateObject.optString("sourceLang").isBlank()) {
            discard(file)
            return discarded("source_language_missing", storedBytes.size, jsonBytes.size)
        }

        val fingerprints = EngineConfigFingerprints(
            transcription = root.optString("transcriptionFingerprint"),
            translation = root.optString("translationFingerprint"),
            dubbing = root.optString("dubbingFingerprint"),
        )
        if (
            fingerprints.transcription.isBlank() ||
            fingerprints.translation.isBlank() ||
            fingerprints.dubbing.isBlank()
        ) {
            discard(file)
            return discarded("engine_fingerprint_missing", storedBytes.size, jsonBytes.size)
        }

        val restored = decodeState(videoKey, stateObject)
        if (restored == null) {
            discard(file)
            return discarded("state_validation_failed", storedBytes.size, jsonBytes.size)
        }

        if (version >= 6) {
            val expectedFingerprint = contentFingerprint(
                keyHash = keyHash,
                identityHash = identityHash,
                fingerprints = fingerprints,
                requireFreshDubbing = root.optBoolean("requireFreshDubbing", false),
                requireFreshTranslation = root.optBoolean("requireFreshTranslation", false),
                youtubeSubtitleMode = readNullableString(root, "youtubeSubtitleMode"),
                state = restored,
                version = version,
            )
            if (root.optString("contentFingerprint") != expectedFingerprint) {
                discard(file)
                return discarded("content_fingerprint_mismatch", storedBytes.size, jsonBytes.size)
            }
        }

        val youtubeSubtitleMode = readNullableString(root, "youtubeSubtitleMode")
        val contentHash = if (version >= 6) root.optString("contentFingerprint") else ""
        if (now >= file.lastModified()) runCatching { file.setLastModified(now) }
        if (contentHash.isNotBlank()) rememberSavedFile(file, contentHash)

        return InProgressCheckpointLoadResult(
            status = InProgressCheckpointLoadStatus.RESTORED,
            checkpoint = InProgressProcessingCheckpoint(
                state = restored,
                fingerprints = fingerprints,
                savedAt = savedAt,
                requireFreshDubbing = root.optBoolean("requireFreshDubbing", false),
                requireFreshTranslation = root.optBoolean("requireFreshTranslation", false),
                youtubeSubtitleMode = youtubeSubtitleMode,
            ),
            fileBytes = storedBytes.size.toLong(),
            uncompressedBytes = jsonBytes.size.toLong(),
            ageMs = (now - savedAt).coerceAtLeast(0L),
        )
    }

    @Synchronized
    fun delete(context: Context, videoKey: String, identityKey: String): Boolean {
        if (!isSupportedKey(videoKey) || identityKey.isBlank()) return false
        val file = checkpointFile(
            directory(context),
            sha256(videoKey),
            sha256(identityKey),
        )
        return try {
            AtomicFile(file).delete()
            lastSavedByPath.remove(file.absolutePath)
            !file.exists() && !File(file.absolutePath + ".bak").exists()
        } catch (_: Exception) {
            lastSavedByPath.remove(file.absolutePath)
            false
        }
    }

    private fun isValidCheckpoint(
        videoKey: String,
        identityKey: String,
        state: PlayerUiState,
        fingerprints: EngineConfigFingerprints,
    ): Boolean {
        if (
            !isSupportedKey(videoKey) ||
            identityKey.isBlank() ||
            state.key != videoKey ||
            state.lines.isEmpty() ||
            state.lines.size > MAX_LINES ||
            state.sourceLang.isBlank() ||
            state.manualTranslationWaiting ||
            !InProgressProcessingCheckpointPhasePolicy.canPersist(state.phase) ||
            fingerprints.transcription.isBlank() ||
            fingerprints.translation.isBlank() ||
            fingerprints.dubbing.isBlank()
        ) return false

        // Avoid zipWithNext() allocating one Pair per adjacent cue on large transcripts.
        var previousStartMs = -1L
        for (line in state.lines) {
            if (
                line.startMs < 0L ||
                line.endMs <= line.startMs ||
                line.text.isBlank() ||
                line.startMs < previousStartMs
            ) return false
            previousStartMs = line.startMs
        }
        return true
    }

    private fun encodeState(state: PlayerUiState): JSONObject {
        val lines = JSONArray()
        state.lines.forEach { line ->
            lines.put(
                JSONObject()
                    .put("s", line.startMs)
                    .put("e", line.endMs)
                    .put("t", line.text)
                    .put("zh", line.zh?.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                    .put("sp", line.speakerId)
                    .put("oa", line.translationFallbackOriginalAudio),
            )
        }

        val tracks = JSONArray()
        state.availableSubtitleTracks.filter { it.id.isNotBlank() && it.url.isNotBlank() }.forEach { track ->
            tracks.put(
                JSONObject()
                    .put("id", track.id)
                    .put("languageTag", track.languageTag)
                    .put("autoGenerated", track.isAutoGenerated)
                    .put("url", track.url),
            )
        }

        return JSONObject()
            .put("title", state.title)
            .put("channel", state.channel)
            .put("thumbUrl", state.thumbUrl ?: JSONObject.NULL)
            .put("durationMs", state.durationMs.coerceAtLeast(0L))
            .put("lines", lines)
            .put("sourceLang", state.sourceLang)
            .put("engineName", state.engineName)
            .put("translationCacheKey", state.translationCacheKey)
            .put(
                "videoAspect",
                (state.videoAspect.takeIf { it.isFinite() && it in 0.2f..5f } ?: (16f / 9f)).toDouble(),
            )
            .put("audioOnly", state.audioOnly)
            .put("qualityOptions", JSONArray(state.qualityOptions.filter { it.isNotBlank() }))
            .put("selectedQuality", state.selectedQuality)
            .put("subtitleTracks", tracks)
            .put("subtitleTrackStatus", state.subtitleTrackStatus)
            .put("processingFlow", state.processingFlow.name)
    }

    private fun decodeState(videoKey: String, obj: JSONObject): PlayerUiState? = runCatching {
        val rows = obj.optJSONArray("lines") ?: return null
        if (rows.length() !in 1..MAX_LINES) return null

        val lines = ArrayList<SubLine>(rows.length())
        var previousStartMs = -1L
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: return null
            val start = row.optLong("s", -1L)
            val end = row.optLong("e", -1L)
            val original = row.optString("t")
            if (start < 0L || end <= start || original.isBlank() || start < previousStartMs) return null
            previousStartMs = start
            val rawTranslation = row.opt("zh")
            val translation = if (rawTranslation == null || rawTranslation == JSONObject.NULL) {
                null
            } else {
                rawTranslation.toString().takeIf { it.isNotBlank() }
            }
            lines += SubLine(
                index, start, end, original, translation, row.optInt("sp", -1),
                row.optBoolean("oa", false),
            )
        }

        val qualityArray = obj.optJSONArray("qualityOptions") ?: JSONArray()
        val qualities = ArrayList<String>(qualityArray.length())
        for (index in 0 until qualityArray.length()) {
            qualityArray.optString(index).takeIf { it.isNotBlank() }?.let(qualities::add)
        }

        val trackArray = obj.optJSONArray("subtitleTracks") ?: JSONArray()
        val tracks = ArrayList<SubtitleTrackOption>(trackArray.length())
        for (index in 0 until trackArray.length()) {
            val track = trackArray.optJSONObject(index) ?: return null
            val id = track.optString("id")
            val url = track.optString("url")
            if (id.isBlank() || url.isBlank()) return null
            tracks += SubtitleTrackOption(
                id = id,
                languageTag = track.optString("languageTag"),
                isAutoGenerated = track.optBoolean("autoGenerated", false),
                url = url,
            )
        }

        val thumb = readNullableString(obj, "thumbUrl", allowBlank = true)
        val flow = runCatching {
            ProcessingFlow.valueOf(obj.optString("processingFlow"))
        }.getOrNull() ?: return null

        PlayerUiState(
            phase = Phase.TRANSLATING,
            progress = "正在恢复上次字幕处理进度…",
            key = videoKey,
            title = obj.optString("title"),
            channel = obj.optString("channel"),
            thumbUrl = thumb,
            durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
            lines = lines,
            translatedLineCount = lines.count { !it.zh.isNullOrBlank() || it.translationFallbackOriginalAudio },
            totalLines = lines.size,
            audioOnly = obj.optBoolean("audioOnly", false),
            videoAspect = obj.optDouble("videoAspect", 16.0 / 9.0).toFloat()
                .takeIf { it.isFinite() && it in 0.2f..5f } ?: (16f / 9f),
            sourceLang = obj.optString("sourceLang"),
            engineName = obj.optString("engineName"),
            translationCacheKey = obj.optString("translationCacheKey"),
            qualityOptions = qualities,
            selectedQuality = obj.optString("selectedQuality"),
            availableSubtitleTracks = tracks,
            subtitleTrackStatus = obj.optString("subtitleTrackStatus"),
            processingFlow = flow,
        )
    }.getOrNull()

    private fun contentFingerprint(
        keyHash: String,
        identityHash: String,
        fingerprints: EngineConfigFingerprints,
        requireFreshDubbing: Boolean,
        requireFreshTranslation: Boolean,
        youtubeSubtitleMode: String?,
        state: PlayerUiState,
        version: Int = VERSION,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun updateField(value: String?) {
            if (value == null) {
                digest.update(0.toByte())
                return
            }
            digest.update(1.toByte())
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
            digest.update(0.toByte())
        }

        // Only fields written to the durable checkpoint participate. Playback position, UI text,
        // prepared-cue counters and transient task progress deliberately do not trigger disk writes.
        updateField(version.toString())
        updateField(keyHash)
        updateField(identityHash)
        updateField(fingerprints.transcription)
        updateField(fingerprints.translation)
        updateField(fingerprints.dubbing)
        updateField(requireFreshDubbing.toString())
        updateField(requireFreshTranslation.toString())
        updateField(youtubeSubtitleMode)
        updateField(state.title)
        updateField(state.channel)
        updateField(state.thumbUrl)
        updateField(state.durationMs.coerceAtLeast(0L).toString())
        updateField(state.sourceLang)
        updateField(state.engineName)
        updateField(state.translationCacheKey)
        val normalizedAspect = state.videoAspect.takeIf {
            it.isFinite() && it in 0.2f..5f
        } ?: (16f / 9f)
        updateField(normalizedAspect.toDouble().toString())
        updateField(state.audioOnly.toString())

        val qualities = state.qualityOptions.filter { it.isNotBlank() }
        updateField(qualities.size.toString())
        qualities.forEach { updateField(it) }
        updateField(state.selectedQuality)
        updateField(state.subtitleTrackStatus)
        updateField(state.processingFlow.name)

        val tracks = state.availableSubtitleTracks.filter {
            it.id.isNotBlank() && it.url.isNotBlank()
        }
        updateField(tracks.size.toString())
        tracks.forEach { track ->
            updateField(track.id)
            updateField(track.languageTag)
            updateField(track.isAutoGenerated.toString())
            updateField(track.url)
        }

        updateField(state.lines.size.toString())
        state.lines.forEach { line ->
            updateField(line.startMs.toString())
            updateField(line.endMs.toString())
            updateField(line.text)
            updateField(line.zh?.takeIf { it.isNotBlank() })
            if (version >= 7) updateField(line.translationFallbackOriginalAudio.toString())
            updateField(line.speakerId.toString())
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun readNullableString(
        obj: JSONObject,
        key: String,
        allowBlank: Boolean = false,
    ): String? {
        val value = obj.opt(key) ?: return null
        if (value == JSONObject.NULL) return null
        val text = value.toString()
        return if (allowBlank || text.isNotBlank()) text else null
    }

    private fun discarded(
        reason: String,
        fileBytes: Int,
        uncompressedBytes: Int,
    ) = InProgressCheckpointLoadResult(
        status = InProgressCheckpointLoadStatus.DISCARDED,
        reason = reason,
        fileBytes = fileBytes.toLong(),
        uncompressedBytes = uncompressedBytes.toLong(),
    )

    private fun isUnchanged(file: File, fingerprint: String): Boolean {
        val cached = lastSavedByPath[file.absolutePath] ?: return false
        // A pending AtomicFile backup means the last replacement did not finish cleanly.
        // Never skip a write while openRead() would prefer that backup over the base file.
        if (File(file.absolutePath + ".bak").exists()) return false
        val length = file.length()
        val lastModified = file.lastModified()
        return InProgressProcessingCheckpointStoragePolicy.isUnchanged(
            cachedFingerprint = cached.fingerprint,
            requestedFingerprint = fingerprint,
            fileExists = file.isFile,
            cachedLength = cached.fileLength,
            currentLength = length,
            cachedLastModified = cached.lastModified,
            currentLastModified = lastModified,
        )
    }

    private fun rememberSavedFile(file: File, fingerprint: String) {
        if (!file.isFile) {
            lastSavedByPath.remove(file.absolutePath)
            return
        }
        lastSavedByPath[file.absolutePath] = CachedWrite(
            fingerprint = fingerprint,
            fileLength = file.length(),
            lastModified = file.lastModified(),
        )
        while (lastSavedByPath.size > MAX_FILES) {
            val oldestKey = lastSavedByPath.keys.firstOrNull() ?: break
            lastSavedByPath.remove(oldestKey)
        }
    }

    private fun discard(file: File) {
        lastSavedByPath.remove(file.absolutePath)
        runCatching { AtomicFile(file).delete() }
    }

    /** Read with a hard cap so a corrupted compressed payload cannot exhaust memory. */
    private fun readBounded(input: InputStream, maxBytes: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total += count.toLong()
            require(total <= maxBytes) { "处理中间检查点超过读取大小限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun ensureDirectory(context: Context): File {
        val directory = directory(context)
        if (directory.exists() && !directory.isDirectory) {
            throw IOException("处理中间检查点路径不是目录")
        }
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("无法创建处理中间检查点目录")
        }
        return directory
    }

    private fun checkpointFile(directory: File, keyHash: String, identityHash: String): File =
        File(directory, "progress_" + keyHash.take(24) + "_" + identityHash.take(24) + ".json")

    private fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIRECTORY_NAME)

    private fun prune(directory: File, keep: File, now: Long) {
        val candidates = directory.listFiles { _, name ->
            name.startsWith("progress_") &&
                (name.endsWith(".json") || name.endsWith(".json.bak"))
        }?.map { file ->
            val path = file.absolutePath
            File(if (path.endsWith(".json.bak")) path.removeSuffix(".bak") else path)
        }?.distinctBy { it.absolutePath }?.filter { base ->
            base.isFile || File(base.absolutePath + ".bak").isFile
        } ?: return

        fun effectiveLength(base: File): Long {
            val backup = File(base.absolutePath + ".bak")
            return (if (backup.isFile) backup.length() else base.length()).coerceAtLeast(0L)
        }

        fun effectiveLastModified(base: File): Long {
            val backup = File(base.absolutePath + ".bak")
            return if (backup.isFile) backup.lastModified() else base.lastModified()
        }

        candidates.filter { base ->
            base.absolutePath != keep.absolutePath &&
                InProgressProcessingCheckpointStoragePolicy.isExpiredFile(
                    lastModifiedMs = effectiveLastModified(base),
                    nowMs = now,
                    maxAgeMs = MAX_AGE_MS,
                    maxFutureSkewMs = MAX_FUTURE_CLOCK_SKEW_MS,
                    fileBytes = effectiveLength(base),
                    maxFileBytes = MAX_FILE_BYTES,
                )
        }.forEach { base ->
            if (!deleteFile(base)) {
                throw IOException("无法清理过期的处理中间检查点文件")
            }
            lastSavedByPath.remove(base.absolutePath)
        }

        val remainingFiles = directory.listFiles { _, name ->
            name.startsWith("progress_") &&
                (name.endsWith(".json") || name.endsWith(".json.bak"))
        }?.map { file ->
            val path = file.absolutePath
            File(if (path.endsWith(".json.bak")) path.removeSuffix(".bak") else path)
        }?.distinctBy { it.absolutePath }?.filter { base ->
            base.isFile || File(base.absolutePath + ".bak").isFile
        }?.sortedByDescending { file -> effectiveLastModified(file) } ?: return

        var total = remainingFiles.sumOf { file -> effectiveLength(file) }
        var count = remainingFiles.size
        for (file in remainingFiles.asReversed()) {
            if (count <= MAX_FILES && total <= MAX_TOTAL_BYTES) break
            if (file.absolutePath == keep.absolutePath) continue
            val size = effectiveLength(file)
            if (!deleteFile(file)) {
                throw IOException("无法清理超出容量限制的处理中间检查点文件")
            }
            lastSavedByPath.remove(file.absolutePath)
            total = (total - size).coerceAtLeast(0L)
            count--
        }
    }

    private fun deleteFile(file: File): Boolean = runCatching {
        AtomicFile(file).delete()
        !file.exists() && !File(file.absolutePath + ".bak").exists()
    }.getOrDefault(false)

    private fun isSupportedKey(key: String): Boolean =
        key.startsWith("remote:", ignoreCase = true) ||
            key.startsWith("local:", ignoreCase = true) ||
            key.startsWith("youtube:", ignoreCase = true)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
