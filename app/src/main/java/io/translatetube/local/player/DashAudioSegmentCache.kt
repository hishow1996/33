package io.translatetube.local.player

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import android.webkit.CookieManager
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Disk-backed materializer for clear static DASH audio representations.
 *
 * Each initialization/media segment is cached independently, downloaded with bounded memory and
 * resumable HTTP ranges, and assembled only after all pieces pass size/age checks. Completed
 * segment files survive cancellation and can be reused by retry. DRM, live/dynamic MPDs, byte-range
 * SegmentBase, multi-Period streams and unknown manifest layouts are declined by the parser.
 */
internal class DashAudioSegmentCache(
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
        manifestUrl: String,
        destination: File,
        sourceKey: String,
        headers: Map<String, String> = emptyMap(),
        checkActive: () -> Unit = {},
        onProgress: (completedSegments: Int, totalSegments: Int) -> Unit = { _, _ -> },
    ): File {
        validateHttpUrl(manifestUrl)
        require(sourceKey.isNotBlank()) { "DASH 音轨缺少来源标识" }
        if (maxOutputBytes <= 0L || maxSegmentBytes <= 0L) {
            throw IllegalArgumentException("DASH 缓存上限无效")
        }
        val parent = destination.parentFile ?: throw IOException("DASH 输出文件没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建 DASH 缓存目录")
        val root = File(cacheDir, SEGMENT_ROOT)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建 DASH 分段缓存目录")
        val sourceFolder = File(root, sha256(sourceKey).take(24))
        if (!sourceFolder.isDirectory && !sourceFolder.mkdirs()) {
            throw IOException("无法创建当前视频的 DASH 分段目录")
        }
        pruneOldSegmentCaches(root, protected = sourceFolder)
        val credentialOrigin = originOf(manifestUrl)

        val manifestText = fetchManifest(
            manifestUrl,
            headersForTarget(manifestUrl, headers, credentialOrigin),
            checkActive,
        )
        val manifest = try {
            DashAudioManifestParser.parse(manifestUrl, manifestText)
        } catch (e: UnsupportedOperationException) {
            throw IOException(e.message ?: "该 DASH 音轨暂不支持", e)
        } catch (e: IllegalArgumentException) {
            throw IOException(e.message ?: "DASH 音轨清单无效", e)
        }
        val downloader = AsrAudioSegmentDownloader(
            client = client,
            maxBytes = maxSegmentBytes,
            chunkBytes = SEGMENT_RANGE_CHUNK_BYTES,
        )

        if (!manifest.directAudioUrl.isNullOrBlank()) {
            // A direct audio representation may be larger than a single segment; use the full
            // source-audio limit rather than the per-segment limit in this case.
            return AsrAudioSegmentDownloader(
                client = client,
                maxBytes = maxOutputBytes,
                chunkBytes = SEGMENT_RANGE_CHUNK_BYTES,
            ).download(
                url = manifest.directAudioUrl,
                destination = destination,
                sourceKey = "$sourceKey:direct-audio",
                headers = headersForTarget(manifest.directAudioUrl, headers, credentialOrigin),
                checkActive = checkActive,
            ).also { pruneOldSegmentCaches(root) }
        }
        if (manifest.segments.size > DashAudioManifestParser.MAX_SEGMENTS) {
            throw IOException("DASH 分段数量超过限制")
        }
        val orderedFiles = ArrayList<File>(manifest.segments.size + 1)
        manifest.initializationUrl?.let { url ->
            checkActive()
            val initFile = segmentFile(sourceFolder, "init", url)
            ensureCachedSegment(
                downloader, url, initFile, "$sourceKey:init",
                headersForTarget(url, headers, credentialOrigin), checkActive,
            )
            orderedFiles += initFile
        }
        manifest.segments.forEach { segment ->
            checkActive()
            val file = segmentFile(sourceFolder, "seg-${segment.index}", segment.url)
            ensureCachedSegment(
                downloader, segment.url, file, "$sourceKey:segment:${segment.index}",
                headersForTarget(segment.url, headers, credentialOrigin), checkActive,
            )
            orderedFiles += file
            onProgress(segment.index + 1, manifest.segments.size)
        }

        var expectedBytes = 0L
        orderedFiles.forEach { file ->
            if (!isReusableSegment(file, System.currentTimeMillis())) {
                throw IOException("DASH 分段缓存缺失或无效：${file.name}")
            }
            if (file.length() > maxOutputBytes - expectedBytes) {
                throw IOException("DASH 音轨超过 ${maxOutputBytes / MIB} MB 限制；已下载分段会保留，可清理空间后重试")
            }
            expectedBytes += file.length()
        }
        val available = runCatching { parent.usableSpace }.getOrDefault(-1L)
        if (available > 0L && available < expectedBytes + MIN_FREE_STORAGE_BYTES) {
            throw IOException(
                "可用存储空间不足以组装 DASH 音轨（需要约 ${expectedBytes / MIB} MB），" +
                    "已下载分段会保留，清理空间后重试"
            )
        }

        val temporary = File(parent, destination.name + ".dash.tmp")
        if (temporary.exists() && !temporary.delete()) throw IOException("无法清理旧的 DASH 组装临时文件")
        var written = 0L
        try {
            BufferedOutputStream(FileOutputStream(temporary), IO_BUFFER_BYTES).use { output ->
                orderedFiles.forEach { file ->
                    checkActive()
                    BufferedInputStream(FileInputStream(file), IO_BUFFER_BYTES).use { input ->
                        val buffer = ByteArray(IO_BUFFER_BYTES)
                        while (true) {
                            checkActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (count.toLong() > maxOutputBytes - written) {
                                throw IOException("DASH 音轨超过 ${maxOutputBytes / MIB} MB 限制")
                            }
                            output.write(buffer, 0, count)
                            written += count
                        }
                    }
                }
                output.flush()
            }
            if (!temporary.isFile || written <= 0L || temporary.length() != written) {
                throw IOException("DASH 音轨组装结果为空或长度不一致")
            }
            checkActive()
            if (destination.exists() && !destination.delete()) throw IOException("无法替换旧的 DASH 音轨缓存")
            if (!temporary.renameTo(destination)) throw IOException("DASH 分段已下载，但无法发布组装后的音轨")
            destination.setLastModified(System.currentTimeMillis())
            sourceFolder.deleteRecursively()
            return destination
        } catch (e: Exception) {
            temporary.delete()
            // Keep completed segment files so network failures and task cancellation do not restart
            // a long transfer from byte zero.
            throw e
        } finally {
            pruneOldSegmentCaches(root, protected = sourceFolder)
        }
    }

    private fun ensureCachedSegment(
        downloader: AsrAudioSegmentDownloader,
        url: String,
        destination: File,
        sourceKey: String,
        headers: Map<String, String>,
        checkActive: () -> Unit,
    ) {
        if (isReusableSegment(destination, System.currentTimeMillis())) {
            destination.setLastModified(System.currentTimeMillis())
            return
        }
        destination.delete()
        downloader.download(
            url = url,
            destination = destination,
            sourceKey = sourceKey,
            headers = headers,
            checkActive = checkActive,
        )
        if (!isReusableSegment(destination, System.currentTimeMillis())) {
            destination.delete()
            throw IOException("DASH 分段下载后校验失败")
        }
    }

    private fun isReusableSegment(file: File, now: Long): Boolean {
        if (!file.isFile || file.length() !in 1L..maxSegmentBytes) return false
        val modified = file.lastModified()
        return modified > 0L && now >= modified && now - modified <= SEGMENT_TTL_MS
    }

    private fun fetchManifest(
        url: String,
        headers: Map<String, String>,
        checkActive: () -> Unit,
    ): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/dash+xml, application/xml, text/xml, */*")
        applySafeHeaders(builder, headers)
        SafeMediaHttp.execute(client, builder.build()).use { response ->
            checkActive()
            if (!response.isSuccessful) throw IOException("DASH 清单读取失败：HTTP ${response.code}")
            val body = response.body ?: throw IOException("DASH 清单响应为空")
            if (body.contentLength() > DashAudioManifestParser.MAX_MANIFEST_CHARS.toLong()) {
                throw IOException("DASH 清单超过大小限制")
            }
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (output.size().toLong() + count > DashAudioManifestParser.MAX_MANIFEST_CHARS.toLong()) {
                        throw IOException("DASH 清单超过大小限制")
                    }
                    output.write(buffer, 0, count)
                }
            }
            val text = output.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
            if (!text.contains("<MPD", ignoreCase = true)) {
                throw IOException("该链接没有返回 DASH MPD 清单")
            }
            return text
        }
    }

    private fun headersForTarget(
        url: String,
        captured: Map<String, String>,
        credentialOrigin: String,
    ): Map<String, String> {
        val sameOrigin = originOf(url) == credentialOrigin
        val result = LinkedHashMap<String, String>()
        captured.forEach { (name, value) ->
            val normalized = name.trim().lowercase(Locale.ROOT)
            val allowed = normalized in FORWARDABLE_HEADERS
            val credential = normalized in setOf("cookie", "authorization", "origin")
            val safe = value.isNotBlank() &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
            if (allowed && safe && (!credential || sameOrigin)) {
                result[name.trim()] = value
            }
        }
        // Re-read cookies for this exact CDN/manifest URL instead of carrying another origin's
        // Cookie header to a different host.
        runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { result["Cookie"] = it }
        return result
    }

    private fun originOf(value: String): String = runCatching {
        val uri = URI(value)
        val scheme = uri.scheme.orEmpty().lowercase(Locale.ROOT)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        "$scheme://$host:$port"
    }.getOrDefault("")

    private fun applySafeHeaders(builder: Request.Builder, headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (name.trim().lowercase(Locale.ROOT) in FORWARDABLE_HEADERS &&
                value.isNotBlank() &&
                value.none { it == '\r' || it == '\n' || it == '\u0000' }
            ) {
                builder.header(name.trim(), value)
            }
        }
    }

    private fun segmentFile(folder: File, label: String, url: String): File {
        val safeLabel = label.replace(Regex("[^A-Za-z0-9_-]"), "_")
        return File(folder, "${safeLabel}-${sha256(url).take(24)}.seg")
    }

    private fun pruneOldSegmentCaches(root: File, protected: File? = null) {
        val now = System.currentTimeMillis()
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        dirs.filter { dir ->
            dir.absolutePath != protected?.absolutePath &&
                (dir.lastModified() <= 0L || now < dir.lastModified() ||
                    now - dir.lastModified() > SEGMENT_TTL_MS)
        }.forEach { it.deleteRecursively() }
        val current = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        var total = 0L
        current.forEach { dir -> dir.walkTopDown().forEach { if (it.isFile) total += it.length() } }
        val budget = minOf(SEGMENT_CACHE_BUDGET_BYTES, AsrAudioCachePolicy.CACHE_BUDGET_BYTES / 2)
        for (dir in current.sortedBy { it.lastModified() }) {
            if (total <= budget) break
            if (dir.absolutePath == protected?.absolutePath) continue
            val size = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) total = (total - size).coerceAtLeast(0L)
        }
    }

    private fun validateHttpUrl(value: String) {
        val uri = runCatching { URI(value) }.getOrNull()
        if (uri == null ||
            (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) ||
            uri.host.isNullOrBlank() || uri.userInfo != null
        ) throw IOException("DASH 音轨地址不是有效的 HTTP/HTTPS 链接")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val SEGMENT_ROOT = "asr_dash_segments"
        private const val DEFAULT_MAX_SEGMENT_BYTES = 64L * MIB
        private const val SEGMENT_RANGE_CHUNK_BYTES = 2L * MIB
        private const val SEGMENT_TTL_MS = 24L * 60L * 60L * 1_000L
        private const val SEGMENT_CACHE_BUDGET_BYTES = 384L * MIB
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
