package io.translatetube.local.player

import android.content.Context
import android.util.AtomicFile
import io.translatetube.local.subs.SubLine
import io.translatetube.local.yt.SubtitleTrackOption
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Keeps a bounded set of completed processing results per video and engine configuration.
 *
 * The file name contains only one-way hashes. This lets a video reuse an earlier successful
 * ASR/translation/TTS combination without storing the original video URL or API credentials.
 */
internal data class ReadyPlaybackSessionSnapshot(
    val fingerprint: String,
    val ignoreTranscription: Boolean,
    val state: PlayerUiState,
    val savedAt: Long,
    /** Engine-domain fingerprints belong to the result, not to whichever settings are current later. */
    val transcriptionFingerprint: String = "",
    val translationFingerprint: String = "",
    val dubbingFingerprint: String = "",
)

internal object ReadySessionSnapshotStore {
    private const val DIRECTORY_NAME = "ready_session_snapshots"
    private const val SNAPSHOT_VERSION = 1
    private const val MAX_SNAPSHOTS_PER_VIDEO = 8
    private const val MAX_SNAPSHOT_BYTES = 16L * 1024L * 1024L
    private const val MAX_TOTAL_BYTES = 192L * 1024L * 1024L
    private const val MAX_SNAPSHOT_LINES = 120_000
    private val fingerprintPattern = Regex("^[0-9a-f]{24}$")

    fun findForCurrentConfig(
        context: Context,
        videoKey: String,
        fingerprintFor: (ignoreTranscription: Boolean) -> String,
    ): ReadyPlaybackSessionSnapshot? {
        if (!isSupportedVideoKey(videoKey)) return null
        val directory = directory(context)
        val expectedKeyHash = keyHash(videoKey)
        // The fingerprint and transcription-ignore flag are part of the file name; probe no more
        // than two exact candidates instead of parsing all prior snapshots on the calling thread.
        for (ignoreTranscription in listOf(true, false)) {
            val currentFingerprint = runCatching {
                fingerprintFor(ignoreTranscription)
            }.getOrDefault("")
            if (!fingerprintPattern.matches(currentFingerprint)) continue
            val suffix = if (ignoreTranscription) "_ignore_asr.json" else "_include_asr.json"
            val file = File(directory, "ready_" + expectedKeyHash + "_" + currentFingerprint + suffix)
            if (!file.isFile || file.length() < 1L || file.length() > MAX_SNAPSHOT_BYTES) {
                if (file.exists() && file.length() > MAX_SNAPSHOT_BYTES) file.delete()
                continue
            }
            val root = readRoot(file) ?: continue
            if (root.optInt("version", -1) != SNAPSHOT_VERSION) continue
            if (root.optString("keyHash") != expectedKeyHash) continue
            if (root.optString("fingerprint") != currentFingerprint) continue
            if (root.optBoolean("ignoreTranscription", false) != ignoreTranscription) continue

            val stateObject = root.optJSONObject("state") ?: continue
            val state = decodeState(videoKey, stateObject) ?: continue
            val savedAt = root.optLong("savedAt", file.lastModified()).coerceAtLeast(0L)
            runCatching { file.setLastModified(System.currentTimeMillis()) }
            return ReadyPlaybackSessionSnapshot(
                fingerprint = currentFingerprint,
                ignoreTranscription = ignoreTranscription,
                state = state,
                savedAt = savedAt,
                transcriptionFingerprint = root.optString("transcriptionFingerprint"),
                translationFingerprint = root.optString("translationFingerprint"),
                dubbingFingerprint = root.optString("dubbingFingerprint"),
            )
        }
        return null
    }

    /**
     * Finds the latest successful result even when the currently selected engines no longer
     * match it. The caller compares the fingerprints stored with this snapshot and resumes from
     * the earliest affected stage instead of throwing away valid upstream work.
     */
    fun findLatestForVideo(
        context: Context,
        videoKey: String,
    ): ReadyPlaybackSessionSnapshot? {
        if (!isSupportedVideoKey(videoKey)) return null
        val expectedKeyHash = keyHash(videoKey)
        val candidates = directory(context).listFiles { _, name ->
            name.startsWith(filePrefix(videoKey)) && name.endsWith(".json")
        }?.filter { file ->
            file.isFile && file.length() in 1L..MAX_SNAPSHOT_BYTES
        } ?: return null

        return candidates.mapNotNull { file ->
            val root = readRoot(file) ?: return@mapNotNull null
            if (root.optInt("version", -1) != SNAPSHOT_VERSION ||
                root.optString("keyHash") != expectedKeyHash
            ) return@mapNotNull null

            val fingerprint = root.optString("fingerprint")
            if (!fingerprintPattern.matches(fingerprint)) return@mapNotNull null
            val ignoreTranscription = root.optBoolean("ignoreTranscription", false)
            val suffix = if (ignoreTranscription) "_ignore_asr.json" else "_include_asr.json"
            if (file.name != "ready_${expectedKeyHash}_${fingerprint}${suffix}") {
                return@mapNotNull null
            }

            val stateObject = root.optJSONObject("state") ?: return@mapNotNull null
            val state = decodeState(videoKey, stateObject) ?: return@mapNotNull null
            ReadyPlaybackSessionSnapshot(
                fingerprint = fingerprint,
                ignoreTranscription = ignoreTranscription,
                state = state,
                savedAt = root.optLong("savedAt", file.lastModified()).coerceAtLeast(0L),
                transcriptionFingerprint = root.optString("transcriptionFingerprint"),
                translationFingerprint = root.optString("translationFingerprint"),
                dubbingFingerprint = root.optString("dubbingFingerprint"),
            )
        }.maxByOrNull { it.savedAt }
    }

    fun save(
        context: Context,
        videoKey: String,
        fingerprint: String,
        ignoreTranscription: Boolean,
        state: PlayerUiState,
        transcriptionFingerprint: String = "",
        translationFingerprint: String = "",
        dubbingFingerprint: String = "",
    ): Boolean {
        if (!isSupportedVideoKey(videoKey) ||
            !fingerprintPattern.matches(fingerprint) ||
            state.lines.isEmpty() ||
            state.lines.size > MAX_SNAPSHOT_LINES ||
            state.phase != Phase.READY ||
            !state.dubbingOn ||
            state.dubbingHasErrors ||
            state.waitingForTranscriptionEngine ||
            state.waitingForDubbingEngine
        ) return false

        return runCatching {
            val now = System.currentTimeMillis()
            val root = JSONObject()
                .put("version", SNAPSHOT_VERSION)
                .put("keyHash", keyHash(videoKey))
                .put("fingerprint", fingerprint)
                .put("ignoreTranscription", ignoreTranscription)
                .put("savedAt", now)
                .put("transcriptionFingerprint", transcriptionFingerprint)
                .put("translationFingerprint", translationFingerprint)
                .put("dubbingFingerprint", dubbingFingerprint)
                .put("state", encodeState(state))
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            if (bytes.isEmpty() || bytes.size.toLong() > MAX_SNAPSHOT_BYTES) return@runCatching false

            val suffix = if (ignoreTranscription) "_ignore_asr.json" else "_include_asr.json"
            val destination = File(directory(context), "ready_" + keyHash(videoKey) + "_" + fingerprint + suffix)
            val atomic = AtomicFile(destination)
            var stream: FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(bytes)
                atomic.finishWrite(stream)
                stream = null
            } catch (error: Exception) {
                val failedStream = stream
                if (failedStream != null) runCatching { atomic.failWrite(failedStream) }
                throw error
            }
            runCatching { destination.setLastModified(now) }
            prune(context, videoKey, keep = destination)
            true
        }.getOrDefault(false)
    }

    fun remove(context: Context, videoKey: String) {
        if (!isSupportedVideoKey(videoKey)) return
        val prefix = filePrefix(videoKey)
        directory(context).listFiles { _, name ->
            name.startsWith(prefix) && name.endsWith(".json")
        }?.forEach { file -> runCatching { AtomicFile(file).delete() } }
    }

    fun clearAll(context: Context) {
        directory(context).listFiles { _, name ->
            name.startsWith("ready_") && name.endsWith(".json")
        }?.forEach { file -> runCatching { AtomicFile(file).delete() } }
    }

    private fun encodeState(state: PlayerUiState): JSONObject {
        val lines = JSONArray()
        state.lines.forEach { line ->
            lines.put(
                JSONObject()
                    .put("s", line.startMs)
                    .put("e", line.endMs)
                    .put("t", line.text)
                    .put("zh", line.zh ?: JSONObject.NULL)
                    .put("sp", line.speakerId),
            )
        }
        val qualityOptions = JSONArray()
        state.qualityOptions.forEach { qualityOptions.put(it) }
        val subtitleTracks = JSONArray()
        state.availableSubtitleTracks.forEach { track ->
            subtitleTracks.put(
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
            .put("durationMs", state.durationMs)
            .put("lines", lines)
            .put("sourceLang", state.sourceLang)
            .put("engineName", state.engineName)
            .put("translationCacheKey", state.translationCacheKey)
            .put("ttsEngineName", state.ttsEngineName)
            .put("dubbingStatus", state.dubbingStatus)
            .put("videoAspect", state.videoAspect.toDouble())
            .put("audioOnly", state.audioOnly)
            .put("dubbingOn", state.dubbingOn)
            .put("muteOriginal", state.muteOriginal)
            .put("qualityOptions", qualityOptions)
            .put("selectedQuality", state.selectedQuality)
            .put("subtitleTracks", subtitleTracks)
            .put("subtitleTrackStatus", state.subtitleTrackStatus)
            .put("manualTranslationModeEnabled", state.manualTranslationModeEnabled)
            .put("processingFlow", state.processingFlow.name)
    }

    private fun decodeState(videoKey: String, obj: JSONObject): PlayerUiState? {
        return runCatching {
            val rows = obj.optJSONArray("lines") ?: return@runCatching null
            if (rows.length() !in 1..MAX_SNAPSHOT_LINES) return@runCatching null
            val lines = ArrayList<SubLine>(rows.length())
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: return@runCatching null
                val startMs = row.optLong("s", -1L)
                val endMs = row.optLong("e", -1L)
                val text = row.optString("t")
                if (startMs < 0L || endMs <= startMs || text.isBlank()) return@runCatching null
                val rawTranslation = row.opt("zh")
                val translation = if (rawTranslation == null || rawTranslation == JSONObject.NULL) null
                    else rawTranslation.toString().takeIf { it.isNotBlank() }
                lines += SubLine(index, startMs, endMs, text, translation, row.optInt("sp", -1))
            }

            val qualities = obj.optJSONArray("qualityOptions") ?: JSONArray()
            val qualityOptions = ArrayList<String>(qualities.length())
            for (index in 0 until qualities.length()) {
                val quality = qualities.optString(index)
                if (quality.isNotBlank()) qualityOptions += quality
            }

            val trackRows = obj.optJSONArray("subtitleTracks") ?: JSONArray()
            val tracks = ArrayList<SubtitleTrackOption>(trackRows.length())
            for (index in 0 until trackRows.length()) {
                val track = trackRows.optJSONObject(index) ?: return@runCatching null
                val id = track.optString("id")
                val languageTag = track.optString("languageTag")
                val url = track.optString("url")
                if (id.isBlank() || url.isBlank()) return@runCatching null
                tracks += SubtitleTrackOption(id, languageTag, track.optBoolean("autoGenerated", false), url)
            }

            val rawThumb = obj.opt("thumbUrl")
            val thumbUrl = if (rawThumb == null || rawThumb == JSONObject.NULL) null else rawThumb.toString()
            val flow = runCatching {
                ProcessingFlow.valueOf(obj.optString("processingFlow", ProcessingFlow.AUTO.name))
            }.getOrDefault(ProcessingFlow.AUTO)

            PlayerUiState(
                phase = Phase.READY,
                progress = "",
                key = videoKey,
                title = obj.optString("title"),
                channel = obj.optString("channel"),
                thumbUrl = thumbUrl,
                durationMs = obj.optLong("durationMs", 0L).coerceAtLeast(0L),
                lines = lines,
                translatedLineCount = lines.count { !it.zh.isNullOrBlank() },
                activeDubbingLine = -1,
                currentLine = -1,
                preparedUntil = 0,
                preparedCount = 0,
                totalLines = lines.size,
                dubbingOn = obj.optBoolean("dubbingOn", true),
                muteOriginal = obj.optBoolean("muteOriginal", true),
                audioOnly = obj.optBoolean("audioOnly", false),
                error = null,
                sourceLang = obj.optString("sourceLang"),
                engineName = obj.optString("engineName"),
                translationCacheKey = obj.optString("translationCacheKey"),
                ttsEngineName = obj.optString("ttsEngineName"),
                dubbingStatus = "已恢复该视频之前成功的处理结果",
                isPlaying = false,
                videoAspect = obj.optDouble("videoAspect", 16.0 / 9.0).toFloat()
                    .takeIf { it.isFinite() && it in 0.2f..5f } ?: (16f / 9f),
                noModelHint = null,
                waitingForTranscriptionEngine = false,
                waitingForDubbingEngine = false,
                dubbingHasErrors = false,
                qualityOptions = qualityOptions,
                selectedQuality = obj.optString("selectedQuality"),
                availableSubtitleTracks = tracks,
                subtitleTrackStatus = obj.optString("subtitleTrackStatus"),
                manualTranslationModeEnabled = obj.optBoolean("manualTranslationModeEnabled", false),
                manualTranslationWaiting = false,
                isProcessingPaused = false,
                processingFlow = flow,
                failedPhase = null,
                failedProgress = "",
            )
        }.getOrNull()
    }

    private fun readRoot(file: File): JSONObject? {
        return runCatching {
            AtomicFile(file).openRead().use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty() || bytes.size.toLong() > MAX_SNAPSHOT_BYTES) return@runCatching null
                JSONObject(String(bytes, Charsets.UTF_8))
            }
        }.getOrNull()
    }

    private fun prune(context: Context, videoKey: String, keep: File) {
        val directory = directory(context)
        val prefix = filePrefix(videoKey)
        directory.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }?.drop(MAX_SNAPSHOTS_PER_VIDEO)?.forEach { file ->
                if (file.absolutePath != keep.absolutePath) runCatching { AtomicFile(file).delete() }
            }

        val allFiles = directory.listFiles { _, name ->
            name.startsWith("ready_") && name.endsWith(".json")
        }?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: return
        var remaining = allFiles.fold(0L) { total, file ->
            (total + file.length().coerceAtLeast(0L)).coerceAtLeast(total)
        }
        for (file in allFiles.asReversed()) {
            if (remaining <= MAX_TOTAL_BYTES) break
            if (file.absolutePath == keep.absolutePath) continue
            val length = file.length().coerceAtLeast(0L)
            if (runCatching { AtomicFile(file).delete(); true }.getOrDefault(false)) {
                remaining = (remaining - length).coerceAtLeast(0L)
            }
        }
    }

    // These snapshots are required to resume a completed video (including user-imported
    // translated subtitles). cacheDir may be purged by Android at any time, so keep the bounded
    // snapshot store in filesDir and let remove()/clearAll()/prune() manage its lifetime.
    private fun directory(context: Context): File {
        val appContext = context.applicationContext
        val persistentDirectory = File(appContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }

        // Migrate snapshots written by earlier versions under cacheDir. Without this migration,
        // users updating the app would lose access to existing successful sessions on first launch.
        val legacyDirectory = File(appContext.cacheDir, DIRECTORY_NAME)
        if (legacyDirectory.isDirectory) {
            legacyDirectory.listFiles()?.forEach { legacyFile ->
                if (!legacyFile.isFile) return@forEach
                val persistentFile = File(persistentDirectory, legacyFile.name)
                if (persistentFile.exists()) {
                    runCatching { legacyFile.delete() }
                } else {
                    val copied = runCatching {
                        legacyFile.copyTo(persistentFile, overwrite = false)
                        true
                    }.getOrDefault(false)
                    if (copied) runCatching { legacyFile.delete() }
                }
            }
            if (legacyDirectory.listFiles()?.isEmpty() == true) {
                runCatching { legacyDirectory.delete() }
            }
        }
        return persistentDirectory
    }

    private fun filePrefix(videoKey: String): String = "ready_" + keyHash(videoKey) + "_"

    private fun keyHash(videoKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(videoKey.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isSupportedVideoKey(videoKey: String): Boolean =
        videoKey.startsWith("youtube:", ignoreCase = true) ||
            videoKey.startsWith("local:", ignoreCase = true) ||
            videoKey.startsWith("remote:", ignoreCase = true)
}