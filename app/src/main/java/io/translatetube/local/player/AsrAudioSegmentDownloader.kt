package io.translatetube.local.player

import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Properties
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Sequential HTTP byte-range downloader for ASR source audio.
 *
 * It never keeps a whole response in RAM. Each requested range is streamed to a .part file and
 * flushed before the next range. Interrupted range downloads can resume from the exact file size;
 * the final cache file is published only after length and response validation succeeds.
 */
internal class AsrAudioSegmentDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val maxBytes: Long = AsrAudioCachePolicy.MAX_AUDIO_BYTES,
    private val chunkBytes: Long = AsrAudioSegmentPolicy.DEFAULT_CHUNK_BYTES,
) {
    private data class Metadata(
        val sourceKey: String,
        val urlHash: String,
        val etag: String?,
        val lastModified: String?,
        val totalBytes: Long,
        val rangeSupported: Boolean?,
    )

    fun download(
        url: String,
        destination: File,
        sourceKey: String,
        headers: Map<String, String> = emptyMap(),
        checkActive: () -> Unit = {},
        onProgress: (receivedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File {
        validateUrl(url)
        require(sourceKey.isNotBlank()) { "音频缓存缺少来源标识" }
        require(maxBytes > 0L && chunkBytes > 0L) { "音频分段参数无效" }

        val parent = destination.parentFile ?: throw IOException("音频缓存没有父目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建音频缓存目录")
        val part = File(parent, destination.name + ".part")
        val metaFile = File(parent, destination.name + ".part.meta")
        val currentUrlHash = sha256(url)
        var metadata = readMetadata(metaFile)

        if (destination.exists() && !destination.delete()) {
            throw IOException("无法替换旧的音频缓存文件")
        }
        val existingPartSize = part.takeIf { it.isFile }?.length() ?: 0L
        if (existingPartSize > 0L && !AsrAudioSegmentPolicy.canResumePartial(
                partialBytes = existingPartSize,
                maxBytes = maxBytes,
                requestedSourceKey = sourceKey,
                currentUrlHash = currentUrlHash,
                savedSourceKey = metadata?.sourceKey,
                savedUrlHash = metadata?.urlHash,
                etag = metadata?.etag,
                lastModified = metadata?.lastModified,
                rangeSupported = metadata?.rangeSupported,
            )
        ) {
            part.delete()
            metaFile.delete()
            metadata = null
        }
        if (existingPartSize > maxBytes) {
            part.delete()
            metaFile.delete()
            metadata = null
        }

        // Recover a crash that happened after the final range was synced but before rename.
        // Only trust the completed byte count for the exact same URL/source; a refreshed signed
        // URL must still be checked by the server using If-Range before these bytes are published.
        if (existingPartSize > 0L &&
            metadata?.rangeSupported == true &&
            metadata?.totalBytes == existingPartSize &&
            metadata?.urlHash == currentUrlHash &&
            metadata?.sourceKey == sourceKey
        ) {
            return publish(part, destination, metaFile, contentType = null)
        }

        var retryOffset = -1L
        var retriesAtOffset = 0
        try {
            while (true) {
                checkActive()
                val offset = if (part.isFile) part.length() else 0L
                if (offset != retryOffset) {
                    retryOffset = offset
                    retriesAtOffset = 0
                }
                if (offset > maxBytes) throw IOException("音频文件超过 ${maxBytes / (1024L * 1024L)} MB 限制")
                if (metadata?.totalBytes?.let { it > 0L && offset > it } == true) {
                    discard(part, metaFile)
                    metadata = null
                    throw IOException("断点音频长度超过服务器声明的总长度，已清理缓存")
                }

                val end = AsrAudioSegmentPolicy.rangeEnd(offset, chunkBytes, metadata?.totalBytes)
                val requestBuilder = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$offset-$end")
                // Only forward request headers explicitly supplied by the resolver for this media
                // URL. Never allow callers to override transport-critical byte-range headers.
                headers.forEach { (name, value) ->
                    if (isForwardableHeader(name, value)) requestBuilder.header(name, value)
                }
                if (offset > 0L) {
                    val validator = metadata?.etag?.takeIf { it.isNotBlank() }
                        ?: metadata?.lastModified?.takeIf { it.isNotBlank() }
                    if (!validator.isNullOrBlank()) requestBuilder.header("If-Range", validator)
                }

                SafeMediaHttp.execute(client, requestBuilder.build()).use { response ->
                    checkActive()
                    when (response.code) {
                        200 -> {
                            // The server ignored Range or If-Range did not match. This response
                            // contains a fresh full representation; never append it to old bytes.
                            // Drop old partial bytes before validating the new response so a bad
                            // URL cannot leave a misleading resumable cache behind.
                            if (offset > 0L) {
                                part.delete()
                                metaFile.delete()
                                metadata = null
                            }
                            val body = response.body ?: throw IOException("音频响应内容为空")
                            val total = body.contentLength()
                            validateDeclaredResponse(response, total)
                            if (total > 0L) ensureDiskSpace(parent, total)
                            metadata = metadataFor(
                                sourceKey, currentUrlHash, response, total, rangeSupported = false
                            )
                            saveMetadata(metaFile, metadata!!)
                            val received = writeBody(
                                body.byteStream(), part, append = false, expectedBytes = total,
                                checkActive = checkActive,
                                onProgress = { count -> onProgress(count, total) },
                            )
                            if (!AsrAudioCachePolicy.isCompleteDownload(total, received)) {
                                throw EOFException("音频响应不完整：预期 ${total} 字节，实际收到 ${received} 字节")
                            }
                            return publish(part, destination, metaFile, response.header("Content-Type"))
                        }

                        206 -> {
                            val body = response.body ?: throw IOException("音频分段响应内容为空")
                            validateDeclaredResponse(response, body.contentLength())
                            val range = AsrAudioSegmentPolicy.parseContentRange(response.header("Content-Range"))
                                ?: throw IOException("服务器返回了无效的 Content-Range")
                            if (range.start == null || range.endInclusive == null || range.start != offset) {
                                throw IOException("音频分段位置不匹配：缓存偏移 $offset，服务器返回 ${range.start}")
                            }
                            val countInRange = range.endInclusive - range.start + 1L
                            if (countInRange <= 0L || countInRange > chunkBytes ||
                                (body.contentLength() >= 0L && body.contentLength() != countInRange)
                            ) {
                                throw IOException("音频分段长度与 Content-Range 不一致")
                            }
                            if (range.totalBytes != null && range.totalBytes !in 1L..maxBytes) {
                                discard(part, metaFile)
                                throw IOException("音频文件超过 ${maxBytes / (1024L * 1024L)} MB 限制")
                            }
                            if (range.totalBytes != null) {
                                ensureDiskSpace(parent, (range.totalBytes - offset).coerceAtLeast(0L))
                            }
                            val responseEtag = response.header("ETag")?.takeIf { it.isNotBlank() }
                            val responseModified = response.header("Last-Modified")?.takeIf { it.isNotBlank() }
                            val etagMismatch = offset > 0L &&
                                !metadata?.etag.isNullOrBlank() &&
                                !responseEtag.isNullOrBlank() &&
                                metadata?.etag != responseEtag
                            val modifiedMismatch = offset > 0L &&
                                metadata?.etag.isNullOrBlank() &&
                                !metadata?.lastModified.isNullOrBlank() &&
                                !responseModified.isNullOrBlank() &&
                                metadata?.lastModified != responseModified
                            if (etagMismatch || modifiedMismatch) {
                                discard(part, metaFile)
                                metadata = null
                                throw IOException("音频源已变化，已清理旧断点缓存；请重试")
                            }

                            metadata = Metadata(
                                sourceKey = sourceKey,
                                urlHash = currentUrlHash,
                                etag = responseEtag ?: metadata?.etag,
                                lastModified = responseModified ?: metadata?.lastModified,
                                totalBytes = range.totalBytes ?: metadata?.totalBytes ?: -1L,
                                rangeSupported = true,
                            )
                            saveMetadata(metaFile, metadata!!)
                            val received = writeRange(
                                body.byteStream(), part, append = offset > 0L,
                                expectedBytes = countInRange,
                                checkActive = checkActive,
                                onProgress = { count ->
                                    val total = range.totalBytes ?: metadata?.totalBytes ?: -1L
                                    onProgress(offset + count, total)
                                },
                            )
                            if (received != countInRange) {
                                throw EOFException("音频分段中断：本段预期 $countInRange 字节，实际收到 $received 字节")
                            }
                            val newSize = part.length()
                            val total = metadata?.totalBytes ?: -1L
                            if (total > 0L && newSize == total) {
                                return publish(part, destination, metaFile, response.header("Content-Type"))
                            }
                            if (total > 0L && newSize > total) {
                                discard(part, metaFile)
                                throw IOException("音频缓存超出服务器声明的总长度")
                            }
                            if (total <= 0L && countInRange < chunkBytes) {
                                // A short final response with an unknown total will be confirmed
                                // by the server's next 416 response, not guessed as complete.
                            }
                        }

                        416 -> {
                            val range = AsrAudioSegmentPolicy.parseContentRange(response.header("Content-Range"))
                            val total = range?.totalBytes
                            val current = part.takeIf { it.isFile }?.length() ?: 0L
                            if (total != null && total == current && current > 0L) {
                                return publish(part, destination, metaFile, null)
                            }
                            discard(part, metaFile)
                            metadata = null
                            throw IOException("服务器拒绝当前音频分段，已清理不一致的断点缓存")
                        }

                        else -> throw IOException("音频读取失败：HTTP ${response.code}")
                    }
                } catch (e: IOException) {
                    // Retry a failed range a few times without discarding confirmed partial bytes.
                    // If the connection died after writing part of a segment, the next request
                    // starts at the actual file length and therefore does not duplicate bytes.
                    if (readMetadata(metaFile)?.rangeSupported == true &&
                        retriesAtOffset < MAX_RANGE_RETRIES
                    ) {
                        retriesAtOffset++
                        checkActive()
                        try {
                            Thread.sleep(RETRY_DELAY_MS * retriesAtOffset)
                        } catch (interrupted: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("音频下载已中断", interrupted)
                        }
                        continue
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            // Preserve partial bytes only when this server confirmed byte-range support. A plain
            // 200 response cannot be resumed safely and otherwise leaves a dead cache on disk.
            val saved = readMetadata(metaFile)
            if (saved?.rangeSupported != true || !part.isFile || part.length() <= 0L) {
                part.delete()
                metaFile.delete()
            }
            throw e
        }
    }

    private fun writeRange(
        input: java.io.InputStream,
        part: File,
        append: Boolean,
        expectedBytes: Long,
        checkActive: () -> Unit,
        onProgress: (Long) -> Unit,
    ): Long = writeStream(input, part, append, expectedBytes, checkActive, onProgress)

    private fun writeBody(
        input: java.io.InputStream,
        part: File,
        append: Boolean,
        expectedBytes: Long,
        checkActive: () -> Unit,
        onProgress: (Long) -> Unit,
    ): Long = writeStream(input, part, append, expectedBytes, checkActive, onProgress)

    private fun writeStream(
        input: java.io.InputStream,
        part: File,
        append: Boolean,
        expectedBytes: Long,
        checkActive: () -> Unit,
        onProgress: (Long) -> Unit,
    ): Long {
        var written = 0L
        var nextDiskSpaceCheckAt = 0L
        val parent = part.parentFile ?: throw IOException("音频缓存没有父目录")
        val existingBytes = if (append && part.isFile) part.length() else 0L
        FileOutputStream(part, append).use { fileOut ->
            java.io.BufferedOutputStream(fileOut, IO_BUFFER_BYTES).use { out ->
                val buffer = ByteArray(IO_BUFFER_BYTES)
                while (expectedBytes < 0L || written < expectedBytes) {
                    checkActive()
                    val requested = if (expectedBytes < 0L) buffer.size else
                        minOf(buffer.size.toLong(), expectedBytes - written).toInt()
                    if (requested <= 0) break
                    val count = input.read(buffer, 0, requested)
                    if (count < 0) break
                    if (count == 0) continue
                    if (existingBytes + written + count.toLong() > maxBytes) {
                        throw IOException("音频文件超过 ${maxBytes / (1024L * 1024L)} MB 限制")
                    }
                    if (existingBytes + written >= nextDiskSpaceCheckAt) {
                        ensureDiskSpace(parent, count.toLong())
                        nextDiskSpaceCheckAt = existingBytes + written + DISK_SPACE_CHECK_INTERVAL_BYTES
                    }
                    out.write(buffer, 0, count)
                    written += count
                    onProgress(written)
                }
                out.flush()
                fileOut.fd.sync()
            }
        }
        return written
    }

    private fun publish(part: File, destination: File, metaFile: File, contentType: String?): File {
        if (!part.isFile || part.length() !in 1L..maxBytes) {
            discard(part, metaFile)
            throw IOException("音频文件为空或超出大小限制")
        }
        if (AsrAudioSegmentPolicy.isClearlyNotAudio(contentType, readPrefix(part))) {
            discard(part, metaFile)
            throw IOException("获取到的不是音频数据（可能是网页错误页、播放列表或登录页面）")
        }
        if (destination.exists() && !destination.delete()) throw IOException("无法替换旧的音频缓存文件")
        if (!part.renameTo(destination)) throw IOException("音频完整，但无法原子保存缓存文件")
        metaFile.delete()
        destination.setLastModified(System.currentTimeMillis())
        return destination
    }

    private fun validateDeclaredResponse(response: Response, contentLength: Long) {
        val type = response.header("Content-Type")
        if (AsrAudioSegmentPolicy.isClearlyNotAudio(type, byteArrayOf())) {
            throw IOException("服务器返回的不是可解码音频：${type ?: "未知格式"}")
        }
        if (contentLength > maxBytes) {
            throw IOException("音频文件超过 ${maxBytes / (1024L * 1024L)} MB 限制")
        }
    }

    private fun ensureDiskSpace(parent: File, bytesToWrite: Long) {
        val usable = runCatching { parent.usableSpace }.getOrDefault(-1L)
        if (usable <= 0L) return
        if (usable < MIN_FREE_STORAGE_BYTES || bytesToWrite > usable - MIN_FREE_STORAGE_BYTES) {
            throw IOException(
                "可用存储空间不足，音频缓存需要约 ${bytesToWrite / (1024L * 1024L)} MB，" +
                    "建议至少保留 32 MB 可用空间后重试"
            )
        }
    }

    private fun readPrefix(file: File): ByteArray = runCatching {
        FileInputStream(file).use { input -> ByteArray(512).also { buffer ->
            val count = input.read(buffer)
            if (count < 0) ByteArray(0) else buffer.copyOf(count)
        } }
    }.getOrDefault(ByteArray(0))

    private fun metadataFor(
        sourceKey: String,
        urlHash: String,
        response: Response,
        totalBytes: Long,
        rangeSupported: Boolean?,
    ) = Metadata(
        sourceKey = sourceKey,
        urlHash = urlHash,
        etag = response.header("ETag")?.takeIf { it.isNotBlank() },
        lastModified = response.header("Last-Modified")?.takeIf { it.isNotBlank() },
        totalBytes = totalBytes,
        rangeSupported = rangeSupported,
    )

    private fun readMetadata(file: File): Metadata? = runCatching {
        if (!file.isFile || file.length() > MAX_METADATA_BYTES) return@runCatching null
        val props = Properties()
        FileInputStream(file).use(props::load)
        Metadata(
            sourceKey = props.getProperty("sourceKey") ?: return@runCatching null,
            urlHash = props.getProperty("urlHash") ?: return@runCatching null,
            etag = props.getProperty("etag")?.ifBlank { null },
            lastModified = props.getProperty("lastModified")?.ifBlank { null },
            totalBytes = props.getProperty("totalBytes")?.toLongOrNull() ?: -1L,
            rangeSupported = props.getProperty("rangeSupported")?.toBooleanStrictOrNull(),
        )
    }.getOrNull()

    private fun saveMetadata(file: File, meta: Metadata) {
        val temp = File(file.parentFile, file.name + ".tmp")
        val props = Properties().apply {
            setProperty("sourceKey", meta.sourceKey)
            setProperty("urlHash", meta.urlHash)
            meta.etag?.let { setProperty("etag", it) }
            meta.lastModified?.let { setProperty("lastModified", it) }
            setProperty("totalBytes", meta.totalBytes.toString())
            meta.rangeSupported?.let { setProperty("rangeSupported", it.toString()) }
        }
        FileOutputStream(temp).use { out ->
            props.store(out, "ASR audio range cache")
            out.fd.sync()
        }
        if (file.exists() && !file.delete()) throw IOException("无法更新音频断点信息")
        if (!temp.renameTo(file)) {
            temp.delete()
            throw IOException("无法保存音频断点信息")
        }
    }

    private fun discard(part: File, metaFile: File) {
        part.delete()
        metaFile.delete()
        File(metaFile.parentFile, metaFile.name + ".tmp").delete()
    }

    private fun isForwardableHeader(name: String, value: String): Boolean {
        val normalizedName = name.trim().lowercase()
        if (normalizedName !in FORWARDABLE_HEADER_NAMES) return false
        if (value.isBlank() || value.any { it == '\\r' || it == '\\n' || it == '\\u0000' }) return false
        return true
    }

    private fun validateUrl(url: String) {
        val parsed = runCatching { URI(url) }.getOrNull()
        require(parsed != null &&
            (parsed.scheme.equals("http", true) || parsed.scheme.equals("https", true)) &&
            !parsed.host.isNullOrBlank() && parsed.userInfo == null
        ) { "音频流地址无效" }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val MAX_METADATA_BYTES = 16L * 1024L
        private const val MAX_RANGE_RETRIES = 3
        private const val RETRY_DELAY_MS = 250L
        private const val MIN_FREE_STORAGE_BYTES = 32L * 1024L * 1024L
        private const val DISK_SPACE_CHECK_INTERVAL_BYTES = 1L * 1024L * 1024L
        private val FORWARDABLE_HEADER_NAMES = setOf(
            "user-agent", "referer", "origin", "cookie", "authorization", "accept-language"
        )
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36"
    }
}
