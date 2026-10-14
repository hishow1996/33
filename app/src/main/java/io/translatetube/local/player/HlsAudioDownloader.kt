package io.translatetube.local.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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

    private data class FetchedText(val finalUrl: String, val text: String)
    private data class Variant(val uri: String, val bandwidth: Long, val audioGroup: String?)
    private data class MediaPlaylist(val initSegment: String?, val segments: List<String>)

    fun isManifestUrl(value: String): Boolean = runCatching {
        URI(value.trim()).rawPath.orEmpty().endsWith(".m3u8", ignoreCase = true)
    }.getOrDefault(false)

    /**
     * Returns the preferred audio rendition, or the lowest-bandwidth variant if audio is muxed.
     * A null result means this is already a media playlist rather than a master playlist.
     */
    internal fun selectMediaPlaylistUrl(playlistUrl: String, body: String): String? {
        val lines = body.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.none { it == "#EXTM3U" }) return null

        val audioRows = lines.filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .map { parseAttributes(it.substringAfter(':')) }
            .filter { it["TYPE"].equals("AUDIO", ignoreCase = true) && !it["URI"].isNullOrBlank() }

        val variants = mutableListOf<Variant>()
        var nextVariant: Map<String, String>? = null
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) {
                nextVariant = parseAttributes(line.substringAfter(':'))
            } else if (!line.startsWith("#") && nextVariant != null) {
                val attrs = nextVariant
                val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                    ?: attrs["BANDWIDTH"]?.toLongOrNull()
                    ?: Long.MAX_VALUE
                variants += Variant(line, bandwidth, attrs["AUDIO"])
                nextVariant = null
            }
        }
        if (variants.isEmpty()) return null

        val chosenVariant = variants.minByOrNull { it.bandwidth } ?: return null
        val matchingAudio = chosenVariant.audioGroup?.let { group ->
            audioRows.filter { row -> row["GROUP-ID"] == group }
        }?.minWithOrNull(
            compareBy<Map<String, String>>(
                { if (it["DEFAULT"].equals("YES", ignoreCase = true)) 0 else 1 },
                { if (it["AUTOSELECT"].equals("YES", ignoreCase = true)) 0 else 1 },
            )
        )
        return resolveUrl(playlistUrl, matchingAudio?.get("URI") ?: chosenVariant.uri)
    }

    suspend fun download(
        playlistUrl: String,
        destination: File,
        requestHeaders: Map<String, String> = emptyMap(),
        onProgress: (String) -> Unit = {},
    ): File {
        val ownerJob = currentCoroutineContext()[Job]
        val assemblyPart = File(destination.parentFile, destination.name + ".part")
        val cacheParent = destination.absoluteFile.parentFile
            ?: throw IllegalStateException("无法确定网页音轨缓存目录。")
        var segmentCacheDirectory: File? = null
        destination.parentFile?.mkdirs()
        assemblyPart.delete()

        try {
            withContext(Dispatchers.IO) {
                val rootUri = parseHttpUrl(playlistUrl)
                val rootOrigin = rootUri
                var fetched = fetchText(rootUri, requestHeaders, rootOrigin, ownerJob)
                ensurePlaylistIsUnencrypted(fetched.text)
                var masterDepth = 0

                while (true) {
                    ownerJob?.ensureActive()
                    val next = selectMediaPlaylistUrl(fetched.finalUrl, fetched.text) ?: break
                    masterDepth++
                    if (masterDepth > MAX_MASTER_DEPTH) {
                        throw IllegalStateException("HLS 主清单嵌套层数过多，已停止解析。")
                    }
                    fetched = fetchText(parseHttpUrl(next), requestHeaders, rootOrigin, ownerJob)
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
                    val reusable = segmentFile.isFile &&
                        segmentFile.lastModified() > 0L &&
                        age in 0L..AsrAudioCachePolicy.TTL_MS &&
                        AsrAudioCachePolicy.isCompleteDownload(-1L, segmentFile.length())
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
                sources.forEachIndexed { index, segmentUrl ->
                    ownerJob?.ensureActive()
                    val segmentFile = segmentFiles[index]
                    val reusable = reusableSegments[index]
                    val segment = if (reusable) {
                        segmentFile.setLastModified(System.currentTimeMillis())
                        segmentFile
                    } else {
                        onProgress("正在缓存网页音轨 ${index + 1}/${sources.size}")
                        downloadSegment(
                            url = segmentUrl,
                            destination = segmentFile,
                            headers = requestHeaders,
                            rootOrigin = rootOrigin,
                            cachedBytes = totalCachedBytes,
                            ownerJob = ownerJob,
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
                        ownerJob?.ensureActive()
                        onProgress("正在合并网页音轨 ${index + 1}/${cachedSegments.size}")
                        segment.inputStream().buffered().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ownerJob?.ensureActive()
                                val count = input.read(buffer)
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

                if (!AsrAudioCachePolicy.isCompleteDownload(-1L, assemblyPart.length())) {
                    throw IllegalStateException("HLS 音频缓存为空或超过 160 MB 限制。")
                }
                if (destination.exists() && !destination.delete()) {
                    throw IllegalStateException("无法替换旧的 HLS 音频缓存。")
                }
                if (!assemblyPart.renameTo(destination)) {
                    throw IllegalStateException("音轨已合并，但无法原子保存缓存文件；重试时会复用已下载的分段。")
                }
                destination.setLastModified(System.currentTimeMillis())
                cacheDirectory.deleteRecursively()
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

    private fun prepareSegmentCache(directory: File, urls: List<String>) {
        val marker = File(directory, "manifest.sha256")
        // Segment links often contain short-lived signatures. Hash their stable URL identity
        // so a renewed token can resume already-complete segments; actual request URLs stay intact.
        val fingerprint = sha256(urls.joinToString("\n") { MediaUrlCacheIdentity.canonicalUrl(it) })
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
        fun size(directory: File): Long = directory.walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }

        directories.filter { it.absolutePath != keep.absolutePath }.forEach { directory ->
            val activity = lastActivity(directory)
            if (activity <= 0L || now < activity || now - activity > AsrAudioCachePolicy.TTL_MS) {
                directory.deleteRecursively()
            }
        }
        val surviving = parent.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".segments") }
            .orEmpty()
        var totalBytes = surviving.sumOf { directory -> size(directory) }
        for (directory in surviving.sortedBy { item -> lastActivity(item) }) {
            if (totalBytes <= MAX_BYTES) break
            if (directory.absolutePath == keep.absolutePath) continue
            val bytes = size(directory)
            if (directory.deleteRecursively()) totalBytes -= bytes
        }
    }

    private suspend fun downloadSegment(
        url: String,
        destination: File,
        headers: Map<String, String>,
        rootOrigin: String,
        cachedBytes: Long,
        ownerJob: Job?,
        onRetry: (attemptNumber: Int) -> Unit,
    ): File {
        val part = File(destination.parentFile, destination.name + ".part")
        var lastError: Exception? = null
        for (attempt in 1..3) {
            ownerJob?.ensureActive()
            part.delete()
            try {
                SafeMediaHttp.execute(
                    url = url,
                    requestHeaders = headers,
                    allowedCookieOrigin = rootOrigin,
                ).use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("下载 HLS 片段失败（HTTP ${response.code}）。")
                    }
                    val body = response.body ?: throw IllegalStateException("HLS 片段响应为空。")
                    if (isHtmlOrJsonContentType(body.contentType()?.toString())) {
                        throw IllegalStateException("服务器返回了网页或 JSON，而不是 HLS 媒体片段；链接可能过期或需要登录。")
                    }
                    val expectedBytes = body.contentLength()
                    if (expectedBytes > MAX_BYTES || cachedBytes + expectedBytes.coerceAtLeast(0L) > MAX_BYTES) {
                        throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
                    }
                    var actualBytes = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(part, false).buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                ownerJob?.ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
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
                }
                if (!part.renameTo(destination)) {
                    val installing = File(destination.parentFile, destination.name + ".installing")
                    try {
                        installing.delete()
                        part.copyTo(installing, overwrite = true)
                        if (installing.length() != part.length()) {
                            throw IllegalStateException("HLS 片段暂存文件大小不匹配。")
                        }
                        if (!installing.renameTo(destination)) {
                            throw IllegalStateException("无法原子保存 HLS 音轨片段。")
                        }
                        part.delete()
                    } catch (error: Exception) {
                        installing.delete()
                        destination.delete()
                        throw error
                    }
                }
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

    private fun parseMediaPlaylist(baseUrl: String, body: String): MediaPlaylist {
        val lines = body.removePrefix("\uFEFF").lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.none { it == "#EXTM3U" }) {
            throw IllegalStateException("服务器返回的内容不是有效 HLS 清单。")
        }

        if (lines.any { it.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) }) {
            throw IllegalStateException("该 HLS 使用 BYTERANGE 字节范围片段，当前版本暂不支持。")
        }
        ensurePlaylistIsUnencrypted(body)
        if (lines.any {
                it.equals("#EXT-X-DISCONTINUITY", ignoreCase = true) ||
                    it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:", ignoreCase = true)
            }) {
            throw IllegalStateException("该 HLS 含有时间轴切换标记，当前版本不能安全地拼接这些片段。")
        }
        validateNoMissingSegments(body)

        var initSegment: String? = null
        val segments = mutableListOf<String>()
        var endList = false
        for (line in lines) {
            when {
                line.equals("#EXT-X-ENDLIST", ignoreCase = true) -> endList = true
                line.startsWith("#EXT-X-MAP:", ignoreCase = true) -> {
                    val attributes = parseAttributes(line.substringAfter(':'))
                    if (!attributes["BYTERANGE"].isNullOrBlank()) {
                        throw IllegalStateException("该 HLS 初始化片段使用 BYTERANGE，当前暂不支持。")
                    }
                    val path = attributes["URI"]
                        ?: throw IllegalStateException("HLS 初始化片段缺少 URI。")
                    val resolvedInit = resolveUrl(baseUrl, path)
                    if (initSegment != null && initSegment != resolvedInit) {
                        throw IllegalStateException("该 HLS 使用多个不同的初始化片段，当前版本无法安全拼接。")
                    }
                    initSegment = resolvedInit
                }
                line.startsWith("#") -> Unit
                else -> {
                    segments += resolveUrl(baseUrl, line)
                    if (segments.size > MAX_SEGMENTS) {
                        throw IllegalStateException("HLS 片段数量超过 ${MAX_SEGMENTS}，已停止以保护手机资源。")
                    }
                }
            }
        }
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
    ): FetchedText {
        SafeMediaHttp.execute(
            url = url,
            requestHeaders = headers,
            allowedCookieOrigin = rootOrigin,
        ).use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("读取 HLS 清单失败（HTTP ${response.code}）。")
            }
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
                    val count = input.read(bytes)
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

    private fun parseAttributes(input: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("""([A-Z0-9-]+)=(?:"([^"]*)"|([^,]*))""", RegexOption.IGNORE_CASE)
        regex.findAll(input).forEach { match ->
            result[match.groupValues[1].uppercase()] =
                (match.groupValues[2].ifEmpty { match.groupValues[3] }).trim()
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
