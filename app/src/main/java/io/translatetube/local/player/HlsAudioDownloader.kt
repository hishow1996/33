package io.translatetube.local.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Downloads a finite, unencrypted HLS VOD rendition into one bounded cache file for
 * MediaExtractor/Whisper. This deliberately does not decrypt encrypted playlists or bypass DRM.
 */
internal object HlsAudioDownloader {
    private const val MAX_PLAYLIST_BYTES = 2L * 1024L * 1024L
    private const val MAX_SEGMENTS = 10_000
    private const val MAX_MASTER_DEPTH = 4
    private const val MAX_BYTES = 160L * 1024L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
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
                val attrs = nextVariant!!
                val bandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull()
                    ?: attrs["BANDWIDTH"]?.toLongOrNull()
                    ?: Long.MAX_VALUE
                variants += Variant(line, bandwidth, attrs["AUDIO"])
                nextVariant = null
            }
        }
        if (variants.isEmpty()) return null

        val chosenVariant = variants.minByOrNull { it.bandwidth } ?: return null
        val matchingAudio = audioRows.filter { row ->
            chosenVariant.audioGroup == null || row["GROUP-ID"] == chosenVariant.audioGroup
        }.minWithOrNull(
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
        val part = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        part.delete()

        try {
            withContext(Dispatchers.IO) {
                val rootUri = parseHttpUrl(playlistUrl)
                val rootHost = URI(rootUri).host
                var fetched = fetchText(rootUri, requestHeaders, rootHost, ownerJob)
                var masterDepth = 0

                while (true) {
                    ownerJob?.ensureActive()
                    val next = selectMediaPlaylistUrl(fetched.finalUrl, fetched.text) ?: break
                    masterDepth++
                    if (masterDepth > MAX_MASTER_DEPTH) {
                        throw IllegalStateException("HLS 主清单嵌套层数过多，已停止解析。")
                    }
                    fetched = fetchText(parseHttpUrl(next), requestHeaders, rootHost, ownerJob)
                }

                val playlist = parseMediaPlaylist(fetched.finalUrl, fetched.text)
                val sources = buildList {
                    playlist.initSegment?.let(::add)
                    addAll(playlist.segments)
                }
                if (sources.isEmpty()) throw IllegalStateException("HLS 清单没有可读取的音频片段。")

                var received = 0L
                FileOutputStream(part, false).buffered().use { output ->
                    sources.forEachIndexed { index, segmentUrl ->
                        ownerJob?.ensureActive()
                        onProgress("正在缓存网页音轨 ${index + 1}/${sources.size}")
                        received = appendResource(
                            url = segmentUrl,
                            rootHost = rootHost,
                            headers = requestHeaders,
                            output = output,
                            previousBytes = received,
                            ownerJob = ownerJob,
                        )
                    }
                    output.flush()
                }

                if (!AsrAudioCachePolicy.isCompleteDownload(-1L, part.length())) {
                    throw IllegalStateException("HLS 音频缓存为空或超过 160 MB 限制。")
                }
                destination.delete()
                if (!part.renameTo(destination)) {
                    part.copyTo(destination, overwrite = true)
                    part.delete()
                }
                onProgress("网页音轨缓存完成，准备解码")
            }
            return destination
        } catch (error: Throwable) {
            part.delete()
            if (error is kotlinx.coroutines.CancellationException) throw error
            throw error
        }
    }

    private fun parseMediaPlaylist(baseUrl: String, body: String): MediaPlaylist {
        val lines = body.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.none { it == "#EXTM3U" }) {
            throw IllegalStateException("服务器返回的内容不是有效 HLS 清单。")
        }

        if (lines.any { it.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) }) {
            throw IllegalStateException("该 HLS 使用 BYTERANGE 字节范围片段，当前版本暂不支持。")
        }
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY:", ignoreCase = true) }) {
            throw IllegalStateException("该 HLS 声明了加密会话密钥；为避免处理受保护音轨，暂不读取。")
        }
        val encryptedKey = lines.firstOrNull { it.startsWith("#EXT-X-KEY:", ignoreCase = true) }
        if (encryptedKey != null) {
            val method = parseAttributes(encryptedKey.substringAfter(':'))["METHOD"]
            if (!method.equals("NONE", ignoreCase = true)) {
                throw IllegalStateException("该 HLS 音轨使用加密密钥；当前仅处理未加密清单，不会尝试解密受保护内容。")
            }
        }

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
                    initSegment = resolveUrl(baseUrl, path)
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
        rootHost: String,
        ownerJob: Job?,
    ): FetchedText {
        client.newCall(requestBuilder(url, headers, rootHost).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("读取 HLS 清单失败（HTTP ${response.code}）。")
            }
            val body = response.body ?: throw IllegalStateException("HLS 清单响应为空。")
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

    private fun appendResource(
        url: String,
        rootHost: String,
        headers: Map<String, String>,
        output: java.io.OutputStream,
        previousBytes: Long,
        ownerJob: Job?,
    ): Long {
        var received = previousBytes
        client.newCall(requestBuilder(url, headers, rootHost).build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("下载 HLS 片段失败（HTTP ${response.code}）。")
            }
            val body = response.body ?: throw IllegalStateException("HLS 片段响应为空。")
            if (body.contentLength() > MAX_BYTES || received + body.contentLength().coerceAtLeast(0L) > MAX_BYTES) {
                throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
            }
            body.byteStream().use { input ->
                val bytes = ByteArray(64 * 1024)
                while (true) {
                    ownerJob?.ensureActive()
                    val count = input.read(bytes)
                    if (count < 0) break
                    received += count
                    if (received > MAX_BYTES) {
                        throw IllegalStateException("网页音轨超过 160 MB 缓存上限，已停止以保护手机存储。")
                    }
                    output.write(bytes, 0, count)
                }
            }
        }
        return received
    }

    private fun requestBuilder(url: String, headers: Map<String, String>, rootHost: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36")
        val targetHost = runCatching { URI(url).host }.getOrNull()
        headers.forEach { (name, value) ->
            if (value.isBlank()) return@forEach
            when {
                name.equals("Referer", ignoreCase = true) -> builder.header("Referer", value)
                name.equals("Cookie", ignoreCase = true) && targetHost.equals(rootHost, ignoreCase = true) ->
                    builder.header("Cookie", value)
            }
        }
        return builder
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
