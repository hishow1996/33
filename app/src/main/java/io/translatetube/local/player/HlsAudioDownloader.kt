package io.translatetube.local.player

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
 * Downloads a finite, unencrypted HLS VOD rendition into a bounded cache for MediaExtractor/Whisper.
 * Complete segment files survive a failed/cancelled attempt and are reused on retry. This class
 * deliberately does not decrypt encrypted playlists or bypass DRM.
 */
internal object HlsAudioDownloader {
    private const val MAX_PLAYLIST_BYTES = 2L * 1024L * 1024L
    private const val MAX_SEGMENTS = 10_000
    private const val MAX_MASTER_DEPTH = 4
    private const val MAX_BYTES = 160L * 1024L * 1024L

    /** Counts only up to budget + 1, so corrupted cache folders cannot overflow byte totals. */
    internal fun boundedCacheByteTotal(sizes: Iterable<Long>, budget: Long = MAX_BYTES): Long {
        val safeBudget = budget.coerceAtLeast(0L)
        val overBudget = if (safeBudget == Long.MAX_VALUE) Long.MAX_VALUE else safeBudget + 1L
        var total = 0L
        for (size in sizes) {
            if (size < 0L || size > safeBudget - total) return overBudget
            total += size
        }
        return total
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

        // Start with the lowest-bandwidth variant, but do not choose its video-only URL when it
        // references an audio group absent from the master playlist. Try the next viable variant
        // instead, and only use a variant URL directly when it is muxed or its group has a URI-less
        // member. This prevents a later MediaExtractor failure caused by downloading video without
        // the audio rendition the master explicitly associates with it.
        for (variant in variants.sortedBy { it.bandwidth }) {
            val group = variant.audioGroup ?: return resolveUrl(playlistUrl, variant.uri)
            val groupRows = audioRows.filter { row -> row["GROUP-ID"] == group }
            if (groupRows.isEmpty()) continue
            // A missing URI attribute means audio is muxed into the variant. An explicitly empty
            // URI is different: it's a malformed external-rendition declaration, not a muxed row.
            if (groupRows.any { it.containsKey("URI") && it["URI"].isNullOrBlank() }) {
                throw IllegalStateException("HLS 音轨组 $group 声明了空 URI，无法读取音轨。")
            }

            val externalAudio = groupRows.filter { !it["URI"].isNullOrBlank() }
            if (externalAudio.isEmpty()) return resolveUrl(playlistUrl, variant.uri)

            val selectedAudio = externalAudio.minWithOrNull(
                compareBy<Map<String, String>>(
                    { if (it["DEFAULT"].equals("YES", ignoreCase = true)) 0 else 1 },
                    { if (it["AUTOSELECT"].equals("YES", ignoreCase = true)) 0 else 1 },
                )
            ) ?: continue
            return resolveUrl(playlistUrl, selectedAudio.getValue("URI"))
        }
        throw IllegalStateException("HLS 主清单引用了不存在或不可用的音轨组，无法安全选择音频。")
    }

    suspend fun download(
        playlistUrl: String,
        destination: File,
        requestHeaders: Map<String, String> = emptyMap(),
        awaitResume: suspend () -> Unit = {},
        userAgent: String? = null,
        onProgress: (String) -> Unit = {},
    ): File = downloadMutex.withLock {
        downloadLocked(playlistUrl, destination, requestHeaders, awaitResume, userAgent, onProgress)
    }

    private suspend fun downloadLocked(
        playlistUrl: String,
        destination: File,
        requestHeaders: Map<String, String>,
        awaitResume: suspend () -> Unit,
        userAgent: String?,
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

                while (true) {
                    awaitResume()
                    ownerJob?.ensureActive()
                    val next = selectMediaPlaylistUrl(fetched.finalUrl, fetched.text) ?: break
                    masterDepth++
                    if (masterDepth > MAX_MASTER_DEPTH) {
                        throw IllegalStateException("HLS 主清单嵌套层数过多，已停止解析。")
                    }
                    fetched = fetchText(parseHttpUrl(next), mediaHeaders, rootOrigin, ownerJob, userAgent)
                    awaitResume()
                    ensurePlaylistIsUnencrypted(fetched.text)
                }

                val playlist = parseMediaPlaylist(fetched.finalUrl, fetched.text)
                val sources = buildList {
                    playlist.initSegment?.let(::add)
                    addAll(playlist.segments)
                }
                if (sources.isEmpty()) throw IllegalStateException("HLS 清单没有可读取的音频片段。")

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
                var cachedBytes = 0L
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
                    if (reusable) {
                        cachedBytes += segmentFile.length()
                    } else {
                        segmentFile.delete()
                        File(segmentFile.parentFile, segmentFile.name + ".part").delete()
                        File(segmentFile.parentFile, segmentFile.name + ".installing").delete()
                    }
                }

                // The total must include valid segments at later indexes too, otherwise a missing
                // early segment could grow the cache beyond its budget before those files are seen.
                if (cachedBytes > MAX_BYTES) {
                    segmentFiles.forEach { it.delete() }
                    reusableSegments.fill(false)
                    cachedBytes = 0L
                }

                val cachedSegments = mutableListOf<File>()
                var totalCachedBytes = cachedBytes
                sources.forEachIndexed { index, source ->
                    awaitResume()
                    ownerJob?.ensureActive()
                    val segmentFile = segmentFiles[index]
                    val reusable = reusableSegments[index]
                    val segment = if (reusable) {
                        segmentFile.setLastModified(System.currentTimeMillis())
                        segmentFile
                    } else {
                        onProgress("正在缓存网页音轨 ${index + 1}/${sources.size}")
                        downloadSegment(
                            url = source.url,
                            byteRangeStart = source.byteRangeStart,
                            byteRangeLength = source.byteRangeLength,
                            destination = segmentFile,
                            headers = mediaHeaders,
                            rootOrigin = rootOrigin,
                            cachedBytes = totalCachedBytes,
                            ownerJob = ownerJob,
                            awaitResume = awaitResume,
                            userAgent = userAgent,
                            onRetry = { attempt ->
                                onProgress("网络不稳定，正在重试音轨片段 ${index + 1}/${sources.size}（第 ${attempt} 次）")
                            },
                        ).also { totalCachedBytes += it.length() }
                    }
                    if (totalCachedBytes > MAX_BYTES) {
                        segmentCacheDirectory?.deleteRecursively()
                        throw IllegalStateException("网页音轨缓存总量超过 160 MB 限制，已清理旧分段以保护手机存储。")
                    }
                    cachedSegments += segment
                    segmentFile.parentFile?.setLastModified(System.currentTimeMillis())
                    pruneSegmentCaches(cacheParent, keep = cacheDirectory)
                    if (reusable) onProgress("复用网页音轨片段 ${index + 1}/${sources.size}")
                }

                // Merge on disk, not in memory: this is suitable for older phones with limited RAM.
                var mergedBytes = 0L
                FileOutputStream(assemblyPart, false).buffered().use { output ->
                    cachedSegments.forEachIndexed { index, segment ->
                        awaitResume()
                        ownerJob?.ensureActive()
                        onProgress("正在合并网页音轨 ${index + 1}/${cachedSegments.size}")
                        segment.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ownerJob?.ensureActive()
                                awaitResume()
                                val count = io.translatetube.local.util.ProgressSafeInput.read(input, buffer)
                                if (count < 0) break
                                mergedBytes += count
                                if (mergedBytes > MAX_BYTES) {
                                    throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
                                }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    output.flush()
                }

                // Persist merged bytes before the completed cache is published under its final name.
                java.io.RandomAccessFile(assemblyPart, "rw").use { it.fd.sync() }
                if (!AsrAudioCachePolicy.isCompleteDownload(-1L, assemblyPart.length())) {
                    throw IllegalStateException("HLS 音频缓存为空或超过 160 MB 限制。")
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
                onProgress("网页音轨缓存完成，准备解码")
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

    /** Keep incomplete/cancelled HLS retries short-lived and bounded across all video tasks. */
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
        fun size(directory: File): Long = boundedCacheByteTotal(
            directory.walkTopDown().filter { it.isFile }.map { it.length() }.asIterable(),
        )

        directories.filter { it.absolutePath != keep.absolutePath }.forEach { directory ->
            val activity = lastActivity(directory)
            if (activity <= 0L || now < activity || now - activity > AsrAudioCachePolicy.TTL_MS) {
                directory.deleteRecursively()
            }
        }
        val surviving = parent.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".segments") }
            .orEmpty()
        var totalBytes = boundedCacheByteTotal(surviving.asSequence().map { directory -> size(directory) }.asIterable())
        for (directory in surviving.sortedBy { item -> lastActivity(item) }) {
            if (totalBytes <= MAX_BYTES) break
            if (directory.absolutePath == keep.absolutePath) continue
            if (directory.deleteRecursively()) {
                // Recompute rather than subtracting an individually capped directory size; once a
                // total crosses the budget, an exact aggregate is intentionally no longer retained.
                val remaining = surviving.filter { it.exists() }
                totalBytes = boundedCacheByteTotal(remaining.asSequence().map { size(it) }.asIterable())
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
        cachedBytes: Long,
        ownerJob: Job?,
        awaitResume: suspend () -> Unit,
        onRetry: (attemptNumber: Int) -> Unit,
        userAgent: String?,
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
        if (byteRangeLength != null &&
            (byteRangeLength > MAX_BYTES || cachedBytes > MAX_BYTES - byteRangeLength)
        ) {
            throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
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
                    validateSegmentResponse(
                        code = response.code,
                        byteRangeStart = byteRangeStart,
                        byteRangeEnd = byteRangeEnd,
                        contentRangeHeader = response.header("Content-Range"),
                    )
                    val body = response.body ?: throw IllegalStateException("HLS 片段响应为空。")
                    if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                        throw IllegalStateException("服务器返回了网页或 JSON，而不是 HLS 媒体片段；链接可能过期或需要登录。")
                    }
                    val expectedBytes = body.contentLength()
                    if (byteRangeLength != null && expectedBytes >= 0L && expectedBytes != byteRangeLength) {
                        throw IllegalStateException("HLS BYTERANGE 响应长度与清单声明不一致。")
                    }
                    if (expectedBytes > MAX_BYTES || cachedBytes + expectedBytes.coerceAtLeast(0L) > MAX_BYTES) {
                        throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
                    }
                    var actualBytes = 0L
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
                                        throw IllegalStateException(
                                            "HLS 片段返回了登录页、错误页或文本清单，而不是音频数据；链接可能过期或需要登录。"
                                        )
                                    }
                                }
                                actualBytes += count
                                if (cachedBytes + actualBytes > MAX_BYTES) {
                                    throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.flush()
                        }
                    }
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
        return MediaPlaylist(initSegment, segments)
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
            validateManifestResponseStatus(response.code)
            val body = response.body ?: throw IllegalStateException("HLS 清单响应为空。")
            if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                throw IllegalStateException("服务器返回了网页或 JSON，而不是 HLS 清单；链接可能过期或需要登录。")
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
