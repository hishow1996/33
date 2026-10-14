package io.translatetube.local.player

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import android.webkit.CookieManager
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Disk-backed HLS VOD materializer for ASR.
 *
 * Fetches a bounded manifest, selects a declared audio rendition when possible, then downloads
 * each clear media segment to its own atomically-published cache file. A retry reuses completed
 * segments and resumes an interrupted segment through AsrAudioSegmentDownloader. The final
 * byte stream is assembled on disk for Android MediaExtractor; segment bytes are never collected
 * into a large in-memory array.
 *
 * Encrypted/DRM playlists, live playlists, discontinuities, and byte-range HLS are declined by
 * HlsPlaylistParser until an implementation can handle their timeline and integrity safely.
 */
internal class HlsAudioSegmentCache(
    private val cacheDir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val maxOutputBytes: Long = AsrAudioCachePolicy.MAX_AUDIO_BYTES,
    private val maxSegmentBytes: Long = DEFAULT_MAX_SEGMENT_BYTES,
) {
    fun download(
        playlistUrl: String,
        destination: File,
        sourceKey: String,
        headers: Map<String, String> = emptyMap(),
        checkActive: () -> Unit = {},
        onProgress: (completedSegments: Int, totalSegments: Int) -> Unit = { _, _ -> },
    ): File {
        validateHttpUrl(playlistUrl)
        require(sourceKey.isNotBlank()) { "HLS 音轨缺少来源标识" }
        if (maxOutputBytes <= 0L || maxSegmentBytes <= 0L) {
            throw IllegalArgumentException("HLS 缓存上限无效")
        }
        val parent = destination.parentFile ?: throw IOException("HLS 输出文件没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建 HLS 缓存目录")
        val root = File(cacheDir, SEGMENT_ROOT)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建 HLS 分段缓存目录")
        val sourceFolder = File(root, sha256(sourceKey).take(24))
        if (!sourceFolder.isDirectory && !sourceFolder.mkdirs()) {
            throw IOException("无法创建当前视频的 HLS 分段目录")
        }
        pruneOldSegmentCaches(root, sourceFolder)
        val credentialOrigin = originOf(playlistUrl)

        var currentPlaylistUrl = playlistUrl
        var playlistText: String
        var depth = 0
        while (true) {
            checkActive()
            playlistText = fetchPlaylist(currentPlaylistUrl, headers, credentialOrigin, checkActive)
            val next = HlsPlaylistParser.selectPlaylistUrl(currentPlaylistUrl, playlistText)
                ?: break
            if (++depth > MAX_MANIFEST_DEPTH) {
                throw IOException("HLS 主播放列表嵌套层级超过限制")
            }
            if (next == currentPlaylistUrl) throw IOException("HLS 播放列表引用自身，无法继续解析")
            currentPlaylistUrl = next
        }

        val playlist = try {
            HlsPlaylistParser.parseMediaPlaylist(currentPlaylistUrl, playlistText)
        } catch (e: UnsupportedOperationException) {
            throw IOException(e.message ?: "该 HLS 音轨暂不支持", e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "HLS 音轨清单无效", e)
        }
        if (playlist.segments.size > HlsPlaylistParser.MAX_SEGMENTS) {
            throw IOException("HLS 分段数量超过限制")
        }
        val downloader = AsrAudioSegmentDownloader(
            client = client,
            maxBytes = maxSegmentBytes,
            chunkBytes = SEGMENT_RANGE_CHUNK_BYTES,
        )

        val orderedFiles = ArrayList<File>(playlist.segments.size + 1)
        playlist.initMapUrl?.let { mapUrl ->
            checkActive()
            val mapFile = segmentFile(sourceFolder, "init", mapUrl)
            ensureCachedSegment(
                downloader, mapUrl, mapFile, sourceKey,
                headersForTarget(mapUrl, headers, credentialOrigin), checkActive
            )
            orderedFiles += mapFile
        }

        playlist.segments.forEachIndexed { index, segment ->
            checkActive()
            val segmentFile = segmentFile(sourceFolder, "seg-$index", segment.url)
            ensureCachedSegment(
                downloader, segment.url, segmentFile, "$sourceKey:segment:$index",
                headersForTarget(segment.url, headers, credentialOrigin), checkActive,
            )
            orderedFiles += segmentFile
            onProgress(index + 1, playlist.segments.size)
        }

        var expectedAssemblyBytes = 0L
        orderedFiles.forEach { part ->
            val length = part.length()
            if (!part.isFile || length <= 0L || length > maxSegmentBytes) {
                throw IOException("HLS 分段缓存缺失或无效：${part.name}")
            }
            if (length > maxOutputBytes - expectedAssemblyBytes) {
                throw IOException("HLS 音轨超过 ${maxOutputBytes / MIB} MB 限制；已下载分段保留，可调整限制后重试")
            }
            expectedAssemblyBytes += length
        }
        val available = runCatching { parent.usableSpace }.getOrDefault(-1L)
        if (available > 0L && available < expectedAssemblyBytes + MIN_FREE_STORAGE_BYTES) {
            throw IOException(
                "可用存储空间不足以组装 HLS 音轨（还需要约 ${expectedAssemblyBytes / MIB} MB），" +
                    "已下载分段会保留，清理空间后重试"
            )
        }

        val temporary = File(parent, destination.name + ".hls.tmp")
        if (temporary.exists() && !temporary.delete()) throw IOException("无法清理旧的 HLS 组装临时文件")
        var written = 0L
        try {
            BufferedOutputStream(FileOutputStream(temporary), IO_BUFFER_BYTES).use { output ->
                orderedFiles.forEach { part ->
                    checkActive()
                    if (!part.isFile || part.length() <= 0L || part.length() > maxSegmentBytes) {
                        throw IOException("HLS 分段缓存缺失或无效：${part.name}")
                    }
                    if (part.length() > maxOutputBytes - written) {
                        throw IOException("HLS 音轨超过 ${maxOutputBytes / MIB} MB 限制")
                    }
                    BufferedInputStream(FileInputStream(part), IO_BUFFER_BYTES).use { input ->
                        val buffer = ByteArray(IO_BUFFER_BYTES)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            written += count
                            if (written > maxOutputBytes) {
                                throw IOException("HLS 音轨超过 ${maxOutputBytes / MIB} MB 限制")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                output.flush()
            }
            if (written <= 0L || !temporary.isFile || temporary.length() != written) {
                throw IOException("HLS 音轨组装结果为空或长度不一致")
            }
            checkActive()
            if (destination.exists() && !destination.delete()) {
                throw IOException("无法替换旧的 HLS 音轨缓存")
            }
            if (!temporary.renameTo(destination)) {
                throw IOException("HLS 分段已下载，但无法发布组装后的音轨")
            }
            destination.setLastModified(System.currentTimeMillis())
            // Once the final audio file is safely published, it is the retry cache. Remove
            // duplicate segment files to conserve storage on the phone's internal partition.
            sourceFolder.deleteRecursively()
            return destination
        } catch (e: Exception) {
            temporary.delete()
            // Keep completed segment files after cancellation/network failure; a retry validates
            // each cache entry and continues from the first missing or stale segment.
            throw e
        } finally {
            pruneOldSegmentCaches(root, protected = sourceFolder)
        }
    }

    private fun ensureCachedSegment(
        downloader: AsrAudioSegmentDownloader,
        url: String,
        target: File,
        sourceKey: String,
        headers: Map<String, String>,
        checkActive: () -> Unit,
    ) {
        val now = System.currentTimeMillis()
        if (isReusableSegment(target, now)) {
            target.setLastModified(now)
            return
        }
        target.delete()
        downloader.download(
            url = url,
            destination = target,
            sourceKey = sourceKey,
            headers = headers,
            checkActive = checkActive,
        )
        if (!isReusableSegment(target, System.currentTimeMillis())) {
            target.delete()
            throw IOException("HLS 分段下载后校验失败")
        }
    }

    private fun isReusableSegment(file: File, now: Long): Boolean {
        if (!file.isFile || file.length() !in 1L..maxSegmentBytes) return false
        val modified = file.lastModified()
        if (modified <= 0L || now < modified || now - modified > SEGMENT_TTL_MS) return false
        return true
    }

    private fun fetchPlaylist(
        url: String,
        headers: Map<String, String>,
        credentialOrigin: String,
        checkActive: () -> Unit,
    ): String {
        val request = buildRequest(url, headersForTarget(url, headers, credentialOrigin))
            .header("Accept", "application/vnd.apple.mpegurl, application/x-mpegURL, */*")
            .build()
        SafeMediaHttp.execute(client, request).use { response ->
            checkActive()
            if (!response.isSuccessful) throw IOException("HLS 清单读取失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("HLS 清单响应为空")
            val length = body.contentLength()
            if (length > HlsPlaylistParser.MAX_MANIFEST_CHARS.toLong()) {
                throw IOException("HLS 清单超过大小限制")
            }
            val output = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size().toLong() + count > HlsPlaylistParser.MAX_MANIFEST_CHARS.toLong()) {
                        throw IOException("HLS 清单超过大小限制")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val text = output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
            if (!text.trimStart().startsWith("#EXTM3U", ignoreCase = true)) {
                throw IOException("该链接没有返回 HLS 播放清单")
            }
            return text
        }
    }

    private fun headersForTarget(
        url: String,
        captured: Map<String, String>,
        credentialOrigin: String,
    ): Map<String, String> {
        val targetOrigin = originOf(url)
        val sameOrigin = targetOrigin == credentialOrigin
        val headers = LinkedHashMap<String, String>()
        captured.forEach { (name, value) ->
            val normalized = name.trim().lowercase(java.util.Locale.ROOT)
            val allowed = normalized in FORWARDABLE_HEADERS
            val credential = normalized in setOf("cookie", "authorization", "origin")
            val safeValue = value.isNotBlank() &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
            if (allowed && safeValue && (!credential || sameOrigin)) {
                headers[name.trim()] = value
            }
        }
        // Cookies are retrieved for each concrete manifest/segment URL rather than copied across
        // CDN hosts from the first discovered stream.
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Cookie"] = it }
        return headers
    }

    private fun originOf(value: String): String = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme.orEmpty().lowercase(java.util.Locale.ROOT)
        val host = uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        "$scheme://$host:$port"
    }.getOrDefault("")

    private fun buildRequest(url: String, headers: Map<String, String>): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) ->
            val normalized = name.trim().lowercase()
            if (normalized in FORWARDABLE_HEADERS &&
                value.isNotBlank() &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
            ) {
                builder.header(name, value)
            }
        }
        return builder
    }

    private fun segmentFile(folder: File, label: String, url: String): File {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(folder, "${safeLabel}-${sha256(url).take(24)}.seg")
    }

    private fun pruneOldSegmentCaches(root: File, protected: File? = null) {
        val now = System.currentTimeMillis()
        val directories = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        directories.filter { directory ->
            directory.absolutePath != protected?.absolutePath &&
                (directory.lastModified() <= 0L || now < directory.lastModified() ||
                    now - directory.lastModified() > SEGMENT_TTL_MS)
        }.forEach { it.deleteRecursively() }

        val currentDirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        var total = 0L
        currentDirs.forEach { dir ->
            dir.walkTopDown().forEach { file -> if (file.isFile) total += file.length() }
        }
        val budget = minOf(SEGMENT_CACHE_BUDGET_BYTES, AsrAudioCachePolicy.CACHE_BUDGET_BYTES / 2)
        for (directory in currentDirs.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            if (directory.absolutePath == protected?.absolutePath) continue
            val size = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (directory.deleteRecursively()) total = (total - size).coerceAtLeast(0L)
        }
    }

    private fun validateHttpUrl(url: String) {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri == null ||
            (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) throw IOException("HLS 音轨地址不是有效的 HTTP/HTTPS 链接")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val SEGMENT_ROOT = "asr_hls_segments"
        private const val DEFAULT_MAX_SEGMENT_BYTES = 64L * MIB
        private const val SEGMENT_RANGE_CHUNK_BYTES = 2L * MIB
        private const val SEGMENT_TTL_MS = 24L * 60L * 60L * 1_000L
        private const val SEGMENT_CACHE_BUDGET_BYTES = 384L * MIB
        private const val MAX_MANIFEST_DEPTH = 5
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val MIN_FREE_STORAGE_BYTES = 32L * MIB
        private const val MIB = 1024L * 1024L
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
        private val FORWARDABLE_HEADERS = setOf(
            "user-agent", "referer", "origin", "cookie", "authorization", "accept-language"
        )
    }
}
