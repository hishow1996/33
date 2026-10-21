package io.translatetube.local.player

import io.translatetube.local.util.AudioDownloadProgressFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.security.MessageDigest

/**
 * Downloads a finite, unencrypted HLS VOD rendition into a disk cache for MediaExtractor/Whisper.
 * Complete segment files survive a failed/cancelled attempt and are reused on retry. This class
 * deliberately does not decrypt encrypted playlists or bypass DRM.
 */
internal object HlsAudioDownloader {
    internal enum class AudioSourceKind(val displayName: String) {
        INDEPENDENT("独立音轨"),
        MUXED("音画混合流"),
        UNKNOWN("音轨类型待确认"),
    }

    internal data class PlaylistSelection(
        val url: String,
        val sourceKind: AudioSourceKind,
        /** Declared rendition bitrate; only populated for a muxed video variant, never an audio-only URL. */
        val declaredBandwidth: Long? = null,
        /** HLS RESOLUTION declared for the selected muxed variant, e.g. 640x360. */
        val resolution: String? = null,
    )

    private const val MAX_PLAYLIST_BYTES = 2L * 1024L * 1024L
    private const val MAX_SEGMENTS = 10_000
    private const val MAX_MASTER_DEPTH = 4
    /** Sums byte counts safely without introducing an application-level media-size limit. */
    internal fun boundedCacheByteTotal(sizes: Iterable<Long>, budget: Long = Long.MAX_VALUE): Long {
        val safeBudget = budget.coerceAtLeast(0L)
        val overBudget = if (safeBudget == Long.MAX_VALUE) Long.MAX_VALUE else safeBudget + 1L
        var total = 0L
        for (size in sizes) {
            if (size < 0L || size > safeBudget - total) return overBudget
            total += size
        }
        return total
    }

    /** Estimate a playlist's final media bytes from downloaded samples and their durations. */
    internal fun estimateTotalBytesFromSamples(
        sampledBytes: Long,
        sampledDurationSeconds: Double,
        totalDurationSeconds: Double,
    ): Long? {
        if (sampledBytes <= 0L ||
            !sampledDurationSeconds.isFinite() || sampledDurationSeconds <= 0.0 ||
            !totalDurationSeconds.isFinite() || totalDurationSeconds <= 0.0
        ) return null
        val estimate = sampledBytes.toDouble() * totalDurationSeconds / sampledDurationSeconds
        if (!estimate.isFinite() || estimate < 1.0 || estimate >= Long.MAX_VALUE.toDouble()) return null
        return estimate.toLong().coerceAtLeast(sampledBytes)
    }

    // Cache pruning is folder-wide. Serialize HLS downloads so one task cannot treat another
    // task's in-progress segment directory as stale data and delete it during pruning.
    private val downloadMutex = kotlinx.coroutines.sync.Mutex()

    private data class FetchedText(val finalUrl: String, val text: String)
    private data class Variant(val uri: String, val bandwidth: Long, val audioGroup: String?)

    internal data class SegmentSource(
        val url: String,
        val byteRangeStart: Long? = null,
        val byteRangeLength: Long? = null,
    ) {
        init {
            require((byteRangeStart == null) == (byteRangeLength == null))
            require(byteRangeStart == null || byteRangeStart >= 0L)
            require(byteRangeLength == null || byteRangeLength > 0L)
            if (byteRangeStart != null && byteRangeLength != null) {
                require(byteRangeStart <= Long.MAX_VALUE - byteRangeLength)
            }
        }
    }

    internal data class MediaPlaylist(
        val initSegment: SegmentSource?,
        val segments: List<SegmentSource>,
        val segmentDurationsSeconds: List<Double> = emptyList(),
    )

    private data class ByteRangeSpec(val length: Long, val offset: Long?)

    internal fun headersWithoutInheritedRange(headers: Map<String, String>): Map<String, String> =
        headers.filterKeys { !it.equals("Range", ignoreCase = true) }

    /** Manifest fetches do not request byte ranges, so partial responses cannot be treated as complete. */
    internal fun validateManifestResponseStatus(code: Int) =
        SafeMediaHttp.validateCompleteGetResponse(code, "HLS 清单")
    /** Validate that un-ranged segments are whole responses and ranged segments match exactly. */
    internal fun validateSegmentResponse(
        code: Int,
        byteRangeStart: Long?,
        byteRangeEnd: Long?,
        contentRangeHeader: String?,
    ) {
        if (byteRangeStart == null && byteRangeEnd == null) {
            SafeMediaHttp.validateCompleteGetResponse(code, "HLS 音轨片段")
            return
        }
        if (byteRangeStart == null || byteRangeEnd == null ||
            byteRangeStart < 0L || byteRangeEnd < byteRangeStart
        ) {
            throw IllegalArgumentException("HLS 字节范围无效。")
        }
        val contentRange = contentRangeHeader.orEmpty().trim()
        val expectedContentRange = Regex("""(?i)^bytes\s+""" + byteRangeStart + "-" + byteRangeEnd + """/(?:\d+|\*)$""")
        if (code != 206 || !expectedContentRange.matches(contentRange)) {
            throw IllegalStateException("服务器未按 HLS BYTERANGE 返回请求的字节范围（HTTP " + code + "）。")
        }
        val totalLength = contentRange.substringAfterLast('/').toLongOrNull()
        if (totalLength != null && totalLength <= byteRangeEnd) {
            throw IllegalStateException("服务器返回的 HLS BYTERANGE 总长度无效。")
        }
    }

    /** Remove the retry cache under the same lock used by downloads, avoiding races with reuse. */
    internal suspend fun cleanupSegmentCache(destination: File): Boolean = downloadMutex.withLock {
        val parent = destination.absoluteFile.parentFile ?: return@withLock false
        val stem = destination.name.substringBeforeLast('.', destination.name)
        val cacheDirectory = File(parent, "$stem.segments")
        !cacheDirectory.exists() || cacheDirectory.deleteRecursively()
    }

    fun isManifestUrl(value: String): Boolean {
        // Do not normalize untrusted control characters away before validating a manifest URL.
        if (value.isBlank() || value.any { Character.isISOControl(it) }) return false
        return runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
        val port = uri.port
        scheme in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null &&
            !uri.rawAuthority.orEmpty().endsWith(":") &&
            (port == -1 || port in 1..65535) &&
            uri.rawPath.orEmpty().endsWith(".m3u8", ignoreCase = true)
        }.getOrDefault(false)
    }

    /**
     * Returns the preferred audio rendition, or the lowest-bandwidth variant if audio is muxed.
     * A null result means this is already a media playlist rather than a master playlist.
     */
    internal fun selectMediaPlaylistUrl(playlistUrl: String, body: String): String? {
        val lines = body.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.none { it == "#EXTM3U" }) return null

        // Keep URI-less AUDIO group members too: in HLS they can represent audio muxed into the
        // variant. Dropping them makes a valid muxed variant look like a missing group.
        val audioRows = lines.filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .map { parseAttributes(it.substringAfter(':')) }
            .filter { it["TYPE"].equals("AUDIO", ignoreCase = true) }

        val variants = mutableListOf<Variant>()
        var nextVariant: Map<String, String>? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                if (nextVariant != null) {
                    throw IllegalStateException("HLS 主清单在上一条 STREAM-INF 后缺少媒体变体地址。")
                }
                nextVariant = parseAttributes(line.substringAfter(':'))
            } else if (!line.startsWith("#") && nextVariant != null) {
                val attrs = nextVariant
                val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0L }
                    ?: attrs["BANDWIDTH"]?.toLongOrNull()?.takeIf { it > 0L }
                // A STREAM-INF without a positive declared bandwidth is not a usable variant.
                // Do not silently assign it Long.MAX_VALUE and accidentally choose it when every
                // row in the master is malformed.
                if (bandwidth != null) variants += Variant(line, bandwidth, attrs["AUDIO"])
                nextVariant = null
            }
        }
        if (nextVariant != null) {
            throw IllegalStateException("HLS 主清单的 STREAM-INF 后缺少媒体变体地址。")
        }
        if (variants.isEmpty()) {
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) }) {
                throw IllegalStateException("HLS 主清单没有声明有效带宽的媒体变体。")
            }
            return null
        }

        val sortedVariants = variants.sortedBy { it.bandwidth }

        // Prefer a separately declared audio rendition even when the lowest-bitrate video variant
        // is muxed. Returning that muxed variant immediately used to download video packets from
        // the network and only discard them later during transcription.
        val externalAudioCandidates = ArrayList<Pair<Variant, Map<String, String>>>()
        for (variant in sortedVariants) {
            val group = variant.audioGroup ?: continue
            val groupRows = audioRows.filter { row -> row["GROUP-ID"] == group }
            if (groupRows.isEmpty()) continue

            // An explicitly empty URI is malformed, unlike a missing URI (which means muxed audio).
            if (groupRows.any { it.containsKey("URI") && it["URI"].isNullOrBlank() }) {
                throw IllegalStateException("HLS 音轨组 $group 声明了空 URI，无法读取音轨。")
            }

            val externalAudio = groupRows.filter { !it["URI"].isNullOrBlank() }
            if (externalAudio.isEmpty()) continue

            val selectedAudio = externalAudio.minWithOrNull(
                compareBy<Map<String, String>>(
                    { if (it["DEFAULT"].equals("YES", ignoreCase = true)) 0 else 1 },
                    { if (it["AUTOSELECT"].equals("YES", ignoreCase = true)) 0 else 1 },
                )
            ) ?: continue
            externalAudioCandidates += variant to selectedAudio
        }

        // If multiple video variants point at external audio, use the group associated with the
        // lowest-bandwidth viable variant. Only the audio URI itself is returned and downloaded.
        externalAudioCandidates.minByOrNull { it.first.bandwidth }?.let { (_, audio) ->
            return resolveUrl(playlistUrl, audio.getValue("URI"))
        }

        // No separate audio rendition exists. Use the lowest-bandwidth media variant that can
        // actually carry audio in-band; skip variants whose declared audio group is missing.
        for (variant in sortedVariants) {
            val group = variant.audioGroup ?: return resolveUrl(playlistUrl, variant.uri)
            val groupRows = audioRows.filter { row -> row["GROUP-ID"] == group }
            if (groupRows.isEmpty()) continue
            if (groupRows.any { it.containsKey("URI") && it["URI"].isNullOrBlank() }) {
                throw IllegalStateException("HLS 音轨组 $group 声明了空 URI，无法读取音轨。")
            }
            if (groupRows.none { !it["URI"].isNullOrBlank() }) {
                // URI-less AUDIO group entries denote audio muxed into this media variant.
                return resolveUrl(playlistUrl, variant.uri)
            }
        }

        throw IllegalStateException("HLS 主清单引用了不存在或不可用的音轨组，无法安全选择音频。")
    }


    /**
     * Attach source-type metadata to a master-playlist choice. Media playlists by themselves
     * cannot reliably tell whether their segments carry audio only or muxed audio/video.
     */
    internal fun selectMediaPlaylistWithKind(
        playlistUrl: String,
        body: String,
    ): PlaylistSelection? {
        val selectedUrl = selectMediaPlaylistUrl(playlistUrl, body) ?: return null
        val lines = body.removePrefix("\uFEFF")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        val audioRows = lines
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .map { parseAttributes(it.substringAfter(':')) }
            .filter { it["TYPE"].equals("AUDIO", ignoreCase = true) }
        val independentUrls = audioRows
            .mapNotNull { row -> row["URI"]?.takeIf { it.isNotBlank() } }
            .map { reference -> resolveUrl(playlistUrl, reference) }
            .toSet()
        val variantAttributesByUrl = mutableMapOf<String, Map<String, String>>()
        var pendingVariantAttributes: Map<String, String>? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                pendingVariantAttributes = parseAttributes(line.substringAfter(':'))
            } else if (!line.startsWith("#") && pendingVariantAttributes != null) {
                variantAttributesByUrl[resolveUrl(playlistUrl, line)] = pendingVariantAttributes!!
                pendingVariantAttributes = null
            }
        }
        // A separate AUDIO rendition does not inherit the video's aggregate bitrate or resolution.
        // Only expose declared values when the selected URL is itself a mixed audio/video variant.
        val selectedVariant = variantAttributesByUrl[selectedUrl]
        val declaredBandwidth = selectedVariant?.get("AVERAGE-BANDWIDTH")?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: selectedVariant?.get("BANDWIDTH")?.toLongOrNull()?.takeIf { it > 0L }
        val resolution = selectedVariant?.get("RESOLUTION")?.takeIf {
            Regex("""^\d{2,5}x\d{2,5}$""").matches(it)
        }
        return PlaylistSelection(
            url = selectedUrl,
            sourceKind = if (selectedUrl in independentUrls) {
                AudioSourceKind.INDEPENDENT
            } else {
                AudioSourceKind.MUXED
            },
            declaredBandwidth = if (selectedUrl in independentUrls) null else declaredBandwidth,
            resolution = if (selectedUrl in independentUrls) null else resolution,
        )
    }

    suspend fun download(
        playlistUrl: String,
        destination: File,
        requestHeaders: Map<String, String> = emptyMap(),
        awaitResume: suspend () -> Unit = {},
        userAgent: String? = null,
        initialSourceKind: AudioSourceKind = AudioSourceKind.UNKNOWN,
        onProgress: (String) -> Unit = {},
    ): File = downloadMutex.withLock {
        downloadLocked(
            playlistUrl, destination, requestHeaders, awaitResume, userAgent,
            initialSourceKind, onProgress,
        )
    }

    private suspend fun downloadLocked(
        playlistUrl: String,
        destination: File,
        requestHeaders: Map<String, String>,
        awaitResume: suspend () -> Unit,
        userAgent: String?,
        initialSourceKind: AudioSourceKind,
        onProgress: (String) -> Unit,
    ): File {
        val ownerJob = currentCoroutineContext()[Job]
        // A captured WebView Range belongs to its original request. Do not reuse it for a
        // playlist or a different segment; byte ranges below are derived from the HLS manifest.
        val mediaHeaders = headersWithoutInheritedRange(requestHeaders)
        val assemblyPart = File(destination.parentFile, destination.name + ".part")
        val cacheParent = destination.absoluteFile.parentFile
            ?: throw IllegalStateException("无法确定网页音轨缓存目录。")
        var segmentCacheDirectory: File? = null
        destination.parentFile?.mkdirs()
        assemblyPart.delete()

        try {
            withContext(Dispatchers.IO) {
                awaitResume()
                val rootUri = parseHttpUrl(playlistUrl)
                val rootOrigin = rootUri
                var fetched = fetchText(rootUri, mediaHeaders, rootOrigin, ownerJob, userAgent)
                awaitResume()
                ensurePlaylistIsUnencrypted(fetched.text)
                var masterDepth = 0
                var sourceKind = initialSourceKind
                onProgress("${sourceKind.displayName} · 正在检查 HLS 清单，判断是否独立音轨…")

                while (true) {
                    awaitResume()
                    ownerJob?.ensureActive()
                    val selection = selectMediaPlaylistWithKind(fetched.finalUrl, fetched.text) ?: break
                    sourceKind = selection.sourceKind
                    onProgress("${sourceKind.displayName} · 已识别 HLS 音轨来源，准备下载分段")
                    masterDepth++
                    if (masterDepth > MAX_MASTER_DEPTH) {
                        throw IllegalStateException("HLS 主清单嵌套层数过多，已停止解析。")
                    }
                    fetched = fetchText(parseHttpUrl(selection.url), mediaHeaders, rootOrigin, ownerJob, userAgent)
                    awaitResume()
                    ensurePlaylistIsUnencrypted(fetched.text)
                }
                if (sourceKind == AudioSourceKind.UNKNOWN) {
                    onProgress("${sourceKind.displayName} · 已读取 HLS 清单，正在核对音频分段")
                }

                val playlist = parseMediaPlaylist(fetched.finalUrl, fetched.text)
                val sources = buildList {
                    playlist.initSegment?.let(::add)
                    addAll(playlist.segments)
                }
                if (sources.isEmpty()) throw IllegalStateException("HLS 清单没有可读取的音频片段。")
                val knownTotalBytes = sources.map { it.byteRangeLength }
                    .takeIf { lengths -> lengths.isNotEmpty() && lengths.all { it != null } }
                    ?.let { lengths -> boundedCacheByteTotal(lengths.mapNotNull { it }) }
                    ?.takeIf { it > 0L }
                val initSegmentOffset = if (playlist.initSegment != null) 1 else 0
                val totalMediaDurationSeconds = playlist.segmentDurationsSeconds.sum()
                var completedMediaBytes = 0L
                var completedMediaDurationSeconds = 0.0
                var initSegmentBytes = 0L

                // A hash fingerprint avoids persisting signed URLs while still invalidating a
                // segment cache when the playlist or any signed segment URL changes.
                val cacheDirectory = File(cacheParent, destination.name.substringBeforeLast('.', destination.name) + ".segments")
                segmentCacheDirectory = cacheDirectory
                prepareSegmentCache(cacheDirectory, sources)
                pruneSegmentCaches(cacheParent, keep = cacheDirectory)

                val segmentFiles = sources.indices.map { index ->
                    File(cacheDirectory, index.toString().padStart(5, '0') + ".seg")
                }
                val reusableSegments = BooleanArray(sources.size)
                segmentFiles.forEachIndexed { index, segmentFile ->
                    val age = System.currentTimeMillis() - segmentFile.lastModified()
                    val expectedRangeLength = sources[index].byteRangeLength
                    val hasValidLength = if (expectedRangeLength != null) {
                        segmentFile.length() == expectedRangeLength
                    } else {
                        AsrAudioCachePolicy.isCompleteDownload(-1L, segmentFile.length())
                    }
                    val reusable = segmentFile.isFile &&
                        segmentFile.lastModified() > 0L &&
                        age in 0L..AsrAudioCachePolicy.TTL_MS &&
                        hasValidLength
                    reusableSegments[index] = reusable
                    if (!reusable) {
                        segmentFile.delete()
                        File(segmentFile.parentFile, segmentFile.name + ".part").delete()
                        File(segmentFile.parentFile, segmentFile.name + ".installing").delete()
                    }
                }

                val cachedSegments = mutableListOf<File>()
                sources.forEachIndexed { index, source ->
                    awaitResume()
                    ownerJob?.ensureActive()
                    val segmentFile = segmentFiles[index]
                    val reusable = reusableSegments[index]
                    val bytesBeforeSegment = boundedCacheByteTotal(cachedSegments.map { it.length() })
                    val segmentProgress: (Long, Long?) -> Unit = { segmentBytes, segmentTotal ->
                        val downloadedSoFar = boundedCacheByteTotal(listOf(bytesBeforeSegment, segmentBytes))
                        val mediaIndex = index - initSegmentOffset
                        val mediaDurationSeconds = playlist.segmentDurationsSeconds.getOrNull(mediaIndex) ?: 0.0
                        val currentSampleBytes = segmentTotal?.takeIf {
                            it > 0L && mediaIndex >= 0 && mediaDurationSeconds > 0.0
                        }
                        val sampledBytes = if (currentSampleBytes != null) {
                            boundedCacheByteTotal(listOf(completedMediaBytes, currentSampleBytes))
                        } else completedMediaBytes
                        val sampledDurationSeconds = completedMediaDurationSeconds +
                            if (currentSampleBytes != null) mediaDurationSeconds else 0.0
                        val estimatedMediaBytes = if (knownTotalBytes == null) {
                            estimateTotalBytesFromSamples(
                                sampledBytes = sampledBytes,
                                sampledDurationSeconds = sampledDurationSeconds,
                                totalDurationSeconds = totalMediaDurationSeconds,
                            )
                        } else null
                        val estimatedTotalBytes = estimatedMediaBytes?.let {
                            boundedCacheByteTotal(listOf(initSegmentBytes, it)).coerceAtLeast(downloadedSoFar)
                        }
                        val totalForDisplay = knownTotalBytes ?: estimatedTotalBytes
                        val segmentFraction = if (segmentTotal != null && segmentTotal > 0L) {
                            (segmentBytes.toDouble() / segmentTotal.toDouble()).coerceIn(0.0, 1.0)
                        } else {
                            0.0
                        }
                        val percent = if (knownTotalBytes != null) {
                            (downloadedSoFar.toDouble() * 100.0 / knownTotalBytes.toDouble())
                                .toInt().coerceIn(0, 100)
                        } else {
                            ((index.toDouble() + segmentFraction) * 100.0 / sources.size)
                                .toInt().coerceIn(0, 99)
                        }
                        onProgress(
                            AudioDownloadProgressFormatter.formatProgress(
                                label = "${sourceKind.displayName} · 正在缓存网页音轨 ${index + 1}/${sources.size}",
                                downloadedBytes = downloadedSoFar,
                                totalBytes = totalForDisplay,
                                percent = percent,
                                totalIsEstimate = knownTotalBytes == null && estimatedTotalBytes != null,
                            )
                        )
                    }
                    val segment = if (reusable) {
                        segmentFile.setLastModified(System.currentTimeMillis())
                        segmentFile
                    } else {
                        segmentProgress(0L, source.byteRangeLength)
                        downloadSegment(
                            url = source.url,
                            byteRangeStart = source.byteRangeStart,
                            byteRangeLength = source.byteRangeLength,
                            destination = segmentFile,
                            headers = mediaHeaders,
                            rootOrigin = rootOrigin,
                            ownerJob = ownerJob,
                            awaitResume = awaitResume,
                            userAgent = userAgent,
                            onRetry = { attempt ->
                                onProgress(
                                    "网络不稳定，正在重试音轨片段 ${index + 1}/${sources.size}（第 ${attempt} 次）；已缓存 ${AudioDownloadProgressFormatter.formatBytes(bytesBeforeSegment)}"
                                )
                            },
                            onBytesProgress = segmentProgress,
                        )
                    }
                    cachedSegments += segment
                    segmentFile.parentFile?.setLastModified(System.currentTimeMillis())
                    pruneSegmentCaches(cacheParent, keep = cacheDirectory)
                    segmentProgress(segment.length(), source.byteRangeLength ?: segment.length())
                    val mediaIndex = index - initSegmentOffset
                    if (mediaIndex < 0) {
                        initSegmentBytes = segment.length()
                    } else {
                        val durationSeconds = playlist.segmentDurationsSeconds.getOrNull(mediaIndex) ?: 0.0
                        completedMediaBytes = boundedCacheByteTotal(listOf(completedMediaBytes, segment.length()))
                        if (durationSeconds.isFinite() && durationSeconds > 0.0) {
                            completedMediaDurationSeconds += durationSeconds
                        }
                    }
                }

                // Merge on disk, not in memory: this is suitable for older phones with limited RAM.
                val totalMergedBytes = boundedCacheByteTotal(cachedSegments.map { it.length() })
                    .takeIf { it > 0L }
                var assembledBytes = 0L
                var lastMergeProgressBytes = 0L
                var lastMergeProgressAtMs = android.os.SystemClock.elapsedRealtime()
                FileOutputStream(assemblyPart, false).buffered().use { output ->
                    cachedSegments.forEachIndexed { index, segment ->
                        awaitResume()
                        ownerJob?.ensureActive()
                        val label = "${sourceKind.displayName} · 正在合并音轨 ${index + 1}/${cachedSegments.size}"
                        segment.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ownerJob?.ensureActive()
                                awaitResume()
                                val count = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                assembledBytes = boundedCacheByteTotal(listOf(assembledBytes, count.toLong()))
                                val nowMs = android.os.SystemClock.elapsedRealtime()
                                if (
                                    assembledBytes - lastMergeProgressBytes >= 512L * 1024L ||
                                    nowMs - lastMergeProgressAtMs >= 350L ||
                                    (totalMergedBytes != null && assembledBytes >= totalMergedBytes)
                                ) {
                                    val percent = totalMergedBytes?.let { total ->
                                        (assembledBytes.toDouble() * 100.0 / total.toDouble()).toInt().coerceIn(0, 100)
                                    }
                                    onProgress(
                                        AudioDownloadProgressFormatter.formatProgress(
                                            label = label,
                                            downloadedBytes = assembledBytes,
                                            totalBytes = totalMergedBytes,
                                            percent = percent,
                                        )
                                    )
                                    lastMergeProgressBytes = assembledBytes
                                    lastMergeProgressAtMs = nowMs
                                }
                            }
                        }
                    }
                    output.flush()
                }

                // Persist merged bytes before the completed cache is published under its final name.
                java.io.RandomAccessFile(assemblyPart, "rw").use { it.fd.sync() }
                if (!AsrAudioCachePolicy.isCompleteDownload(-1L, assemblyPart.length())) {
                    throw IllegalStateException("HLS 音频缓存为空，无法继续处理。")
                }
                // assemblyPart is complete and resides beside destination. Replace the old
                // regular file with a same-directory rename so a failed install cannot erase a
                // previously usable audio cache.
                if (!assemblyPart.renameTo(destination)) {
                    throw IllegalStateException("音轨已合并，但无法原子替换缓存文件；旧缓存仍保留，重试时会复用已下载的分段。")
                }
                destination.setLastModified(System.currentTimeMillis())
                // Keep complete segments until MediaExtractor accepts the merged file. The caller
                // clears this directory after successful decode; a decode failure can then retry
                // assembly without downloading every network segment again.
                onProgress(
                    AudioDownloadProgressFormatter.formatProgress(
                        label = "${sourceKind.displayName} · 音轨缓存完成，准备转写",
                        downloadedBytes = destination.length(),
                        totalBytes = destination.length(),
                        percent = 100,
                    )
                )
            }
            return destination
        } catch (error: Throwable) {
            assemblyPart.delete()
            // Keep only fully downloaded segments so a retry can resume without re-fetching them.
            segmentCacheDirectory?.let { pruneSegmentCaches(cacheParent, keep = it) }
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw error
        }
    }

    private fun prepareSegmentCache(directory: File, sources: List<SegmentSource>) {
        val marker = File(directory, "manifest.sha256")
        // Include byte ranges as well as canonical URLs so different slices of one object never
        // reuse each other's cached bytes. Signed query values are still excluded from the marker.
        val fingerprint = sha256(sources.joinToString("\n") { source ->
            "${MediaUrlCacheIdentity.canonicalUrl(source.url)}|${source.byteRangeStart ?: ""}|${source.byteRangeLength ?: ""}"
        })
        val hasMatchingManifest = marker.isFile &&
            runCatching { marker.readText(Charsets.UTF_8).trim() == fingerprint }.getOrDefault(false)
        if (!hasMatchingManifest) {
            directory.deleteRecursively()
            if (!directory.mkdirs() && !directory.isDirectory) {
                throw IllegalStateException("无法创建 HLS 分段缓存目录。")
            }
            val markerPart = File(directory, "manifest.sha256.part")
            markerPart.writeText(fingerprint, Charsets.UTF_8)
            if (!markerPart.renameTo(marker)) {
                markerPart.copyTo(marker, overwrite = true)
                markerPart.delete()
            }
        } else {
            directory.listFiles()
                ?.filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".installing")) }
                ?.forEach { it.delete() }
        }
    }

    /** Remove expired segment directories, but never evict audio by byte count. */
    private fun pruneSegmentCaches(parent: File, keep: File) {
        val now = System.currentTimeMillis()
        val directories = parent.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".segments") }
            .orEmpty()

        fun lastActivity(directory: File): Long {
            var latest = directory.lastModified()
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                latest = maxOf(latest, file.lastModified())
            }
            return latest
        }

        directories.filter { it.absolutePath != keep.absolutePath }.forEach { directory ->
            val activity = lastActivity(directory)
            if (activity <= 0L || now < activity || now - activity > AsrAudioCachePolicy.TTL_MS) {
                directory.deleteRecursively()
            }
        }
    }

    private suspend fun downloadSegment(
        url: String,
        byteRangeStart: Long?,
        byteRangeLength: Long?,
        destination: File,
        headers: Map<String, String>,
        rootOrigin: String,
        ownerJob: Job?,
        awaitResume: suspend () -> Unit,
        onRetry: (attemptNumber: Int) -> Unit,
        userAgent: String?,
        onBytesProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): File {
        val part = File(destination.parentFile, destination.name + ".part")
        val byteRangeEnd = if (byteRangeStart != null && byteRangeLength != null &&
            byteRangeStart >= 0L && byteRangeLength > 0L &&
            byteRangeStart <= Long.MAX_VALUE - byteRangeLength
        ) {
            byteRangeStart + byteRangeLength - 1L
        } else if (byteRangeStart == null && byteRangeLength == null) {
            null
        } else {
            throw IllegalArgumentException("HLS 字节范围无效。")
        }
        val segmentHeaders = if (byteRangeEnd != null) {
            headers + ("Range" to "bytes=${byteRangeStart}-${byteRangeEnd}")
        } else {
            headers
        }
        var lastError: Exception? = null
        for (attempt in 1..3) {
            awaitResume()
            ownerJob?.ensureActive()
            part.delete()
            try {
                SafeMediaHttp.execute(
                    url = url,
                    requestHeaders = segmentHeaders,
                    allowedCookieOrigin = rootOrigin,
                    userAgent = userAgent ?: SafeMediaHttp.DEFAULT_USER_AGENT,
                    ownerJob = ownerJob,
                ).use { response ->
                    if (response.code !in 200..299) {
                        throw IllegalStateException(
                            "HLS 音轨片段请求失败；" + responseEvidence(response, "音轨片段请求")
                        )
                    }
                    try {
                        validateSegmentResponse(
                            code = response.code,
                            byteRangeStart = byteRangeStart,
                            byteRangeEnd = byteRangeEnd,
                            contentRangeHeader = response.header("Content-Range"),
                        )
                    } catch (validationFailure: Exception) {
                        throw IllegalStateException(
                            (validationFailure.message ?: "HLS 音轨片段 HTTP 响应校验失败") +
                                "；" + responseEvidence(response, "音轨片段响应校验"),
                            validationFailure,
                        )
                    }
                    val body = response.body ?: throw IllegalStateException(
                        "HLS 片段响应为空；HTTP ${response.code}；Content-Type=" +
                            (response.header("Content-Type") ?: "未提供")
                    )
                    if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                        throw IllegalStateException(
                            "服务器返回了网页或 JSON，而不是 HLS 媒体片段；链接可能过期或需要登录；" +
                                responseEvidence(response, "音轨片段内容类型校验")
                        )
                    }
                    val expectedBytes = body.contentLength()
                    if (byteRangeLength != null && expectedBytes >= 0L && expectedBytes != byteRangeLength) {
                        throw IllegalStateException("HLS BYTERANGE 响应长度与清单声明不一致。")
                    }
                    var actualBytes = 0L
                    var lastProgressBytes = 0L
                    var lastProgressAtMs = android.os.SystemClock.elapsedRealtime()
                    val payloadPrefix = ByteArray(512)
                    var payloadPrefixSize = 0
                    body.byteStream().use { input ->
                        FileOutputStream(part, false).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ownerJob?.ensureActive()
                                awaitResume()
                                val count = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                                if (count < 0) break
                                if (payloadPrefixSize < payloadPrefix.size) {
                                    val copyCount = minOf(count, payloadPrefix.size - payloadPrefixSize)
                                    System.arraycopy(buffer, 0, payloadPrefix, payloadPrefixSize, copyCount)
                                    payloadPrefixSize += copyCount
                                    if (isLikelyTextErrorPayload(payloadPrefix, payloadPrefixSize)) {
                                        val textPrefix = payloadPrefix.copyOfRange(0, payloadPrefixSize)
                                            .toString(Charsets.UTF_8)
                                        throw IllegalStateException(
                                            "HLS 片段返回了登录页、错误页或文本清单，而不是音频数据；" +
                                                "响应特征=" + summarizeHlsErrorText(
                                                    textPrefix,
                                                    response.header("Content-Type").orEmpty(),
                                                )
                                        )
                                    }
                                }
                                if (actualBytes > Long.MAX_VALUE - count.toLong()) {
                                    throw IllegalStateException("HLS 音轨大小超过当前文件格式可表示的范围。")
                                }
                                actualBytes += count.toLong()
                                output.write(buffer, 0, count)
                                val nowMs = android.os.SystemClock.elapsedRealtime()
                                val progressTotalBytes = expectedBytes.takeIf { it > 0L }
                                    ?: byteRangeLength?.takeIf { it > 0L }
                                if (
                                    actualBytes - lastProgressBytes >= 512L * 1024L ||
                                    nowMs - lastProgressAtMs >= 350L ||
                                    (progressTotalBytes != null && actualBytes >= progressTotalBytes)
                                ) {
                                    onBytesProgress(actualBytes, progressTotalBytes)
                                    lastProgressBytes = actualBytes
                                    lastProgressAtMs = nowMs
                                }
                            }
                            output.flush()
                        }
                    }
                    onBytesProgress(
                        actualBytes,
                        expectedBytes.takeIf { it > 0L } ?: byteRangeLength?.takeIf { it > 0L },
                    )
                    if (!AsrAudioCachePolicy.isCompleteDownload(expectedBytes, actualBytes)) {
                        throw IllegalStateException("HLS 片段下载不完整：预期 ${expectedBytes} 字节，实际收到 ${actualBytes} 字节。")
                    }
                    if (byteRangeLength != null && actualBytes != byteRangeLength) {
                        throw IllegalStateException("HLS BYTERANGE 片段不完整：清单要求 ${byteRangeLength} 字节，实际收到 ${actualBytes} 字节。")
                    }
                }
                // Flush the completed payload before atomically publishing its final cache name.
                java.io.RandomAccessFile(part, "rw").use { it.fd.sync() }
                installCompletedSegment(part, destination, ownerJob, awaitResume)
                destination.setLastModified(System.currentTimeMillis())
                return destination
            } catch (error: Exception) {
                part.delete()
                if (error is kotlinx.coroutines.CancellationException) throw error
                lastError = error
                if (attempt == 3 || !MediaDownloadRetryPolicy.shouldRetry(error)) throw error
                onRetry(attempt + 1)
                kotlinx.coroutines.delay(250L * attempt)
            }
        }
        throw IllegalStateException("HLS 音轨片段下载失败。", lastError)
    }


    /**
     * Atomically publish one completed segment. The fallback copy is chunked and cancellable.
     * Failure must not delete an existing destination, which may still be the last good cache.
     */
    internal suspend fun installCompletedSegment(
        part: File,
        destination: File,
        ownerJob: Job? = null,
        awaitResume: suspend () -> Unit = {},
    ) {
        if (part.renameTo(destination)) return
        val installing = File(destination.parentFile, destination.name + ".installing")
        try {
            installing.delete()
            java.io.FileInputStream(part).use { input ->
                FileOutputStream(installing, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ownerJob?.ensureActive()
                        awaitResume()
                        val count = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (installing.length() != part.length() ||
                !AsrAudioCachePolicy.isCompleteDownload(-1L, installing.length())
            ) {
                throw IllegalStateException("HLS 片段暂存文件大小不匹配。")
            }
            ownerJob?.ensureActive()
            if (!installing.renameTo(destination)) {
                throw IllegalStateException("无法原子保存 HLS 音轨片段；旧文件已保留。")
            }
            part.delete()
        } catch (error: Exception) {
            installing.delete()
            throw error
        }
    }

    internal fun ensurePlaylistIsUnencrypted(body: String) {
        val lines = body.removePrefix("\uFEFF").lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toList()
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) }) {
            throw IllegalStateException("该 HLS 声明了加密会话密钥；当前仅处理未加密媒体，不会尝试解密受保护内容。")
        }
        val encryptedKey = lines
            .filter { it.startsWith("#EXT-X-KEY:", ignoreCase = true) }
            .firstOrNull { line ->
                !parseAttributes(line.substringAfter(':'))["METHOD"].equals("NONE", ignoreCase = true)
            }
        if (encryptedKey != null) {
            throw IllegalStateException("该 HLS 媒体清单使用加密密钥；当前仅处理未加密媒体，不会尝试解密受保护内容。")
        }
    }

    internal fun validateNoMissingSegments(body: String) {
        val hasGap = body.removePrefix("\uFEFF").lineSequence()
            .map(String::trim)
            .any { it.equals("#EXT-X-GAP", ignoreCase = true) }
        if (hasGap) {
            throw IllegalStateException("该 HLS 标记了缺失片段，可能导致字幕时间轴错位；当前版本暂不处理。")
        }
    }

    internal fun parseMediaPlaylist(baseUrl: String, body: String): MediaPlaylist {
        val lines = body.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.none { it == "#EXTM3U" }) {
            throw IllegalStateException("服务器返回的内容不是有效 HLS 清单。")
        }

        ensurePlaylistIsUnencrypted(body)
        // EXT-X-DISCONTINUITY（片头/广告切换导致的时间轴跳变）不再拒绝：
        // 音频转写只关心语音内容，片段间的时间轴跳变不影响 ASR 结果；
        // 若个别分段编码参数不兼容，会在解码阶段失败并由上层回落处理。
        validateNoMissingSegments(body)

        var initSegment: SegmentSource? = null
        val segments = mutableListOf<SegmentSource>()
        val segmentDurationsSeconds = mutableListOf<Double>()
        var pendingByteRange: ByteRangeSpec? = null
        var previousRangeUrl: String? = null
        var previousRangeEndExclusive: Long? = null
        var pendingDurationSeconds: Double? = null
        var mediaSegmentSeen = false
        var endList = false
        for (line in lines) {
            when {
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    if (endList) throw IllegalStateException("HLS 清单重复声明 ENDLIST。")
                    if (pendingByteRange != null) {
                        throw IllegalStateException("HLS BYTERANGE 后缺少对应的媒体片段。")
                    }
                    if (pendingDurationSeconds != null) {
                        throw IllegalStateException("HLS EXTINF 后缺少对应的媒体片段。")
                    }
                    endList = true
                }
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    if (endList) throw IllegalStateException("HLS ENDLIST 后不能继续声明媒体片段。")
                    if (pendingDurationSeconds != null) {
                        throw IllegalStateException("HLS 同一媒体片段前出现了多个 EXTINF。")
                    }
                    val durationText = line.substringAfter(':').substringBefore(',').trim()
                    val duration = durationText.toDoubleOrNull()
                    if (duration == null || !duration.isFinite() || duration <= 0.0) {
                        throw IllegalStateException("HLS EXTINF 时长无效。")
                    }
                    pendingDurationSeconds = duration
                }
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    if (mediaSegmentSeen) {
                        throw IllegalStateException("HLS 初始化片段必须出现在媒体片段之前；不支持中途切换初始化片段。")
                    }
                    val attributes = parseAttributes(line.substringAfter(':'))
                    val path = attributes["URI"]?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("HLS 初始化片段缺少有效 URI。")
                    val resolvedInit = resolveUrl(baseUrl, path)
                    val rawRange = attributes["BYTERANGE"]
                    val initSource = if (rawRange.isNullOrBlank()) {
                        SegmentSource(resolvedInit)
                    } else {
                        val range = parseByteRangeSpec(rawRange)
                        val start = range.offset
                            ?: throw IllegalStateException("HLS 初始化片段 BYTERANGE 缺少明确偏移量。")
                        SegmentSource(resolvedInit, start, range.length)
                    }
                    if (initSegment != null && initSegment != initSource) {
                        throw IllegalStateException("该 HLS 使用多个不同的初始化片段，当前版本无法安全拼接。")
                    }
                    initSegment = initSource
                }
                line.startsWith("#EXT-X-BYTERANGE:", ignoreCase = true) -> {
                    if (pendingByteRange != null) {
                        throw IllegalStateException("HLS 在同一媒体片段前声明了多个 BYTERANGE。")
                    }
                    pendingByteRange = parseByteRangeSpec(line.substringAfter(':'))
                }
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    throw IllegalStateException("HLS BYTERANGE 标签格式无效。")
                }
                line.startsWith("#") -> Unit
                else -> {
                    if (endList) throw IllegalStateException("HLS ENDLIST 后出现了额外媒体片段，已拒绝不完整/错序清单。")
                    if (pendingDurationSeconds == null) {
                        throw IllegalStateException("HLS 媒体片段缺少有效的 EXTINF 时长。")
                    }
                    val resolved = resolveUrl(baseUrl, line)
                    val range = pendingByteRange
                    val source = if (range == null) {
                        SegmentSource(resolved)
                    } else {
                        val start = range.offset
                            ?: previousRangeEndExclusive.takeIf { previousRangeUrl == resolved }
                            ?: throw IllegalStateException(
                                "HLS BYTERANGE 未给出偏移量，且前一个片段不是同一资源的字节范围。"
                            )
                        SegmentSource(resolved, start, range.length)
                    }
                    segments += source
                    segmentDurationsSeconds += requireNotNull(pendingDurationSeconds)
                    mediaSegmentSeen = true
                    pendingDurationSeconds = null
                    if (range != null) {
                        previousRangeUrl = resolved
                        previousRangeEndExclusive = source.byteRangeStart!! + range.length
                    } else {
                        previousRangeUrl = null
                        previousRangeEndExclusive = null
                    }
                    pendingByteRange = null
                    if (segments.size > MAX_SEGMENTS) {
                        throw IllegalStateException("HLS 片段数量超过 ${MAX_SEGMENTS}，已停止以保护手机资源。")
                    }
                }
            }
        }
        if (pendingByteRange != null) throw IllegalStateException("HLS BYTERANGE 后缺少对应的媒体片段。")
        if (pendingDurationSeconds != null) throw IllegalStateException("HLS EXTINF 后缺少对应的媒体片段。")
        if (!endList) {
            throw IllegalStateException("该 HLS 是直播或尚未结束的动态清单；当前只缓存完整视频，不会误把直播片段当成完整视频。")
        }
        if (segments.isEmpty()) throw IllegalStateException("HLS 清单没有完整媒体片段。")
        return MediaPlaylist(initSegment, segments, segmentDurationsSeconds)
    }

    /**
     * Include HTTP metadata with an HLS failure so the app log distinguishes expired links,
     * authentication pages, unsupported byte ranges, and malformed responses.
     * Response body summaries are capped and sanitized; raw URLs, cookies and query values are
     * deliberately excluded.
     */
    private fun responseEvidence(response: okhttp3.Response, stage: String): String {
        val body = response.body
        val bodySummary = summarizeHlsErrorText(
            body?.let { responseBody ->
                runCatching { safeReadResponsePrefix(responseBody.byteStream(), 4 * 1024) }
                    .getOrElse { "读取响应体失败：${it.javaClass.simpleName}" }
            }.orEmpty(),
            body?.contentType()?.toString().orEmpty(),
        )
        return buildString {
            append(stage).append("：")
            append("HTTP ").append(response.code).append(' ').append(response.message).append("；")
            append("Content-Type=").append(body?.contentType()?.toString() ?: "未提供").append("；")
            append("Content-Length=").append(
                response.header("Content-Length")
                    ?: body?.contentLength()?.takeIf { it >= 0L }?.toString()
                    ?: "未提供"
            ).append("；")
            append("Content-Range=").append(response.header("Content-Range") ?: "未提供").append("；")
            append("Accept-Ranges=").append(response.header("Accept-Ranges") ?: "未提供").append("；")
            append("Retry-After=").append(response.header("Retry-After") ?: "未提供").append("；")
            append("Server=").append(response.header("Server") ?: "未提供").append("；")
            append("响应摘要=").append(bodySummary)
        }
    }

    private fun safeReadResponsePrefix(input: java.io.InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 1024))
        input.use { stream ->
            val buffer = ByteArray(1024)
            var total = 0
            while (total < maxBytes) {
                val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (count <= 0) break
                output.write(buffer, 0, count)
                total += count
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun summarizeHlsErrorText(value: String, contentType: String): String {
        val prefix = value.removePrefix("\uFEFF").trim()
        if (prefix.isBlank()) return "响应体为空或服务器未返回正文"
        if (contentType.contains("html", ignoreCase = true) || prefix.startsWith("<")) {
            val title = Regex("""(?is)<title[^>]*>(.*?)</title>""")
                .find(prefix)?.groupValues?.getOrNull(1)
                ?.replace(Regex("""<[^>]+>"""), " ")
                ?.trim()
            return title?.takeIf { it.isNotBlank() }?.let {
                "HTML 错误页标题：" + sanitizeHlsDiagnosticText(it)
            } ?: "HTML 登录页/防护错误页（正文已隐藏）"
        }
        if (contentType.contains("json", ignoreCase = true) || prefix.startsWith("{")) {
            val fields = Regex(
                """"(code|type|error|message|detail|title|success)"\s*:\s*("(?:\\.|[^"\\])*"|true|false|null|-?\d+)""",
                RegexOption.IGNORE_CASE,
            ).findAll(prefix).take(8).map { match ->
                match.groupValues[1] + "=" +
                    sanitizeHlsDiagnosticText(match.groupValues[2].trim('"'))
            }.toList()
            return if (fields.isNotEmpty()) {
                "JSON 错误字段：" + fields.joinToString("; ").take(240)
            } else {
                "JSON 错误响应（正文已隐藏）"
            }
        }
        return sanitizeHlsDiagnosticText(prefix.lineSequence().firstOrNull(String::isNotBlank).orEmpty())
            .ifBlank { "文本错误响应（摘要为空）" }
    }

    private fun sanitizeHlsDiagnosticText(value: String): String {
        var safe = value
        safe = Regex("""(?i)\b(Bearer|Basic)\s+\S+""")
            .replace(safe) { it.groupValues[1] + " [已隐藏]" }
        safe = Regex("""(?i)\b(token|api[_-]?key|signature|sig|authorization|cookie|secret|password|credential|session|jwt|expires)\b\s*[:=]\s*["']?[^&\s,"'<>]+""")
            .replace(safe) { it.groupValues[1] + "=[已隐藏]" }
        safe = Regex("""https?://[^\s"'<>]+""", RegexOption.IGNORE_CASE)
            .replace(safe, "[URL 已隐藏]")
        safe = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9_./+=-]{48,}(?![A-Za-z0-9])""")
            .replace(safe, "[长令牌已隐藏]")
        return safe.replace(Regex("""\s+"""), " ").trim().take(240)
    }

    private fun fetchText(
        url: String,
        headers: Map<String, String>,
        rootOrigin: String,
        ownerJob: Job?,
        userAgent: String?,
    ): FetchedText {
        SafeMediaHttp.execute(
            url = url,
            requestHeaders = headers,
            allowedCookieOrigin = rootOrigin,
            userAgent = userAgent ?: SafeMediaHttp.DEFAULT_USER_AGENT,
            ownerJob = ownerJob,
        ).use { response ->
            if (response.code != 200) {
                throw IllegalStateException(
                    "HLS 清单未返回 HTTP 200 完整响应；" + responseEvidence(response, "清单请求")
                )
            }
            val body = response.body ?: throw IllegalStateException(
                "HLS 清单响应为空；HTTP ${response.code}；Content-Type=" +
                    (response.header("Content-Type") ?: "未提供")
            )
            if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                throw IllegalStateException(
                    "服务器返回了网页或 JSON，而不是 HLS 清单；链接可能过期或需要登录；" +
                        responseEvidence(response, "清单内容类型校验")
                )
            }
            if (body.contentLength() > MAX_PLAYLIST_BYTES) {
                throw IllegalStateException("HLS 清单超过 2 MB 限制，已停止解析。")
            }
            val buffer = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val bytes = ByteArray(16 * 1024)
                while (true) {
                    ownerJob?.ensureActive()
                    val count = io.translatetube.local.util.ProgressSafeInput.read(input, bytes)
                    if (count < 0) break
                    if (buffer.size().toLong() + count > MAX_PLAYLIST_BYTES) {
                        throw IllegalStateException("HLS 清单超过 2 MB 限制，已停止解析。")
                    }
                    buffer.write(bytes, 0, count)
                }
            }
            return FetchedText(response.request.url.toString(), String(buffer.toByteArray(), Charsets.UTF_8))
        }
    }

    private fun parseByteRangeSpec(value: String): ByteRangeSpec {
        val match = Regex("""^(\d+)(?:@(\d+))?$""").matchEntire(value.trim())
            ?: throw IllegalStateException("HLS BYTERANGE 格式无效。")
        val length = match.groupValues[1].toLongOrNull()
            ?: throw IllegalStateException("HLS BYTERANGE 长度无效。")
        if (length <= 0L) throw IllegalStateException("HLS BYTERANGE 长度必须大于零。")
        val offsetText = match.groupValues[2]
        val offset = if (offsetText.isEmpty()) null else {
            offsetText.toLongOrNull()
                ?: throw IllegalStateException("HLS BYTERANGE 偏移量无效。")
        }
        if (offset != null && offset > Long.MAX_VALUE - length) {
            throw IllegalStateException("HLS BYTERANGE 范围超出了可处理的文件偏移量。")
        }
        return ByteRangeSpec(length, offset)
    }

    private fun parseAttributes(input: String): Map<String, String> {
        if (input.isBlank()) {
            throw IllegalStateException("HLS 属性列表不能为空。")
        }
        val result = linkedMapOf<String, String>()
        var cursor = 0

        fun skipWhitespace() {
            while (cursor < input.length && input[cursor].isWhitespace()) cursor++
        }

        fun malformed(): Nothing =
            throw IllegalStateException("HLS 属性列表格式无效或包含重复属性。")

        while (cursor < input.length) {
            skipWhitespace()
            if (cursor >= input.length) break

            val keyStart = cursor
            while (cursor < input.length &&
                (input[cursor].isLetterOrDigit() || input[cursor] == '-')
            ) {
                cursor++
            }
            if (cursor == keyStart) malformed()
            val key = input.substring(keyStart, cursor).uppercase(java.util.Locale.ROOT)

            skipWhitespace()
            if (cursor >= input.length || input[cursor] != '=') malformed()
            cursor++
            skipWhitespace()
            if (cursor >= input.length) malformed()

            val value: String
            if (input[cursor] == '"') {
                cursor++
                val parsed = StringBuilder()
                var closed = false
                while (cursor < input.length) {
                    val char = input[cursor++]
                    when {
                        char == '"' -> {
                            closed = true
                            break
                        }
                        char == '\\' && cursor < input.length -> parsed.append(input[cursor++])
                        else -> parsed.append(char)
                    }
                }
                if (!closed) malformed()
                value = parsed.toString()
                skipWhitespace()
                if (cursor < input.length && input[cursor] != ',') malformed()
            } else {
                val valueStart = cursor
                while (cursor < input.length && input[cursor] != ',') {
                    if (input[cursor] == '"') malformed()
                    cursor++
                }
                value = input.substring(valueStart, cursor).trim()
                if (value.isEmpty()) malformed()
            }

            if (result.putIfAbsent(key, value) != null) malformed()
            if (cursor < input.length) {
                if (input[cursor] != ',') malformed()
                cursor++
                if (cursor >= input.length) malformed()
            }
        }
        return result
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private fun parseHttpUrl(value: String): String {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: throw IllegalStateException("HLS 媒体地址无效。")
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
            throw IllegalStateException("HLS 仅支持有效的 HTTP/HTTPS 地址。")
        }
        return uri.toASCIIString()
    }

    private fun resolveUrl(base: String, reference: String): String {
        val resolved = runCatching { URI(base).resolve(URI(reference)).toString() }.getOrNull()
            ?: throw IllegalStateException("HLS 片段地址无效。")
        return parseHttpUrl(resolved)
    }
}
