package io.translatetube.local.data

import io.translatetube.local.util.HttpDownloadResponsePolicy
import io.translatetube.local.util.ProgressSafeInput
import io.translatetube.local.util.SlowDownloadSourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

internal data class ResumableModelDownloadResult(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val sha256: String,
)

/** Pure HTTP-range validation shared with unit tests. */
internal object ResumableModelDownloadPolicy {
    data class ContentRange(val start: Long, val endInclusive: Long, val totalBytes: Long?)

    fun parseContentRange(value: String?): ContentRange? {
        val match = value?.trim()?.let {
            Regex("""(?i)^bytes\s+(\d+)-(\d+)/(\d+|\*)$""").matchEntire(it)
        } ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
        if (start < 0L || end < start || (total != null && (total <= end || total <= 0L))) return null
        return ContentRange(start, end, total)
    }

    fun isValidRangeResponse(
        requestedOffset: Long,
        range: ContentRange?,
        previouslyKnownTotal: Long?,
    ): Boolean {
        if (requestedOffset < 0L || range == null || range.start != requestedOffset) return false
        if (previouslyKnownTotal != null && previouslyKnownTotal > 0L &&
            range.totalBytes != null && range.totalBytes != previouslyKnownTotal
        ) return false
        return true
    }

    fun parseUnsatisfiedRangeTotal(value: String?): Long? {
        val match = value?.trim()?.let {
            Regex("""(?i)^bytes\s+\*/(\d+)$""").matchEntire(it)
        } ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it > 0L }
    }
}

/**
 * Resumable, validator-aware downloads for large model archives.
 * Each source has its own partial file, so a mirror cannot append bytes from a different URL.
 * Transient errors and cancellations preserve the partial file and its metadata.
 */
internal object ResumableModelArchiveDownloader {
    private const val USER_AGENT = "Mozilla/5.0"

    private data class ResumeMetadata(
        val sourceHash: String,
        val etag: String?,
        val lastModified: String?,
        val totalBytes: Long,
    )

    private class RestartFromZeroException(message: String) : IOException(message)

    fun partFileFor(destination: File, url: String): File {
        val parent = destination.absoluteFile.parentFile
            ?: throw IOException("模型下载目标目录无效")
        return File(parent, destination.name + "." + sha256Text(url).take(16) + ".part")
    }

    fun orderSourcesByResumeProgress(destination: File, urls: List<String>): List<Pair<Int, String>> =
        urls.mapIndexed { index, url -> index to url }
            .sortedWith(
                compareByDescending<Pair<Int, String>> { source ->
                    partFileFor(destination, source.second).length()
                }.thenBy { it.first }
            )

    fun clearResumeMetadata(partFile: File) {
        runCatching { metadataFile(partFile).delete() }
    }

    fun cleanupPartialFiles(destination: File) {
        val parent = destination.absoluteFile.parentFile ?: return
        val prefix = destination.name + "."
        parent.listFiles().orEmpty()
            .filter { file ->
                file.name.startsWith(prefix) &&
                    (file.name.endsWith(".part") || file.name.endsWith(".part.meta") ||
                        file.name.endsWith(".part.meta.tmp"))
            }
            .forEach { runCatching { it.delete() } }
    }

    /** Reuse a completed archive after a process interruption between download and extraction. */
    fun isReusableArchive(
        archive: File,
        maxBytes: Long,
        expectedSha256: String?,
        checkActive: () -> Unit = {},
    ): Boolean {
        if (!archive.isFile || archive.length() !in 3L..maxBytes) return false
        if (expectedSha256 != null) {
            val actual = runCatching { sha256File(archive, checkActive) }.getOrNull() ?: return false
            return actual.equals(expectedSha256, ignoreCase = true)
        }
        return runCatching {
            checkActive()
            FileInputStream(archive).use { input ->
                input.read() == 'B'.code && input.read() == 'Z'.code && input.read() == 'h'.code
            }
        }.getOrDefault(false)
    }

    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    suspend fun download(
        client: OkHttpClient,
        url: String,
        partFile: File,
        maxBytes: Long,
        expectedSha256: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
        awaitResume: suspend () -> Unit = {},
        checkActive: () -> Unit = {},
        shouldFallbackToMirror: (receivedThisRequest: Long, elapsedMillis: Long) -> Boolean = { _, _ -> false },
    ): ResumableModelDownloadResult = withContext(Dispatchers.IO) {
        require(maxBytes > 0L) { "模型下载大小限制无效" }
        val parent = partFile.absoluteFile.parentFile ?: throw IOException("模型下载临时目录无效")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建模型下载临时目录")
        val metaFile = metadataFile(partFile)
        val sourceHash = sha256Text(url)
        var metadata = readMetadata(metaFile)
        if (metadata != null && metadata.sourceHash != sourceHash) {
            partFile.delete()
            metaFile.delete()
            metadata = null
        }
        if (partFile.exists() && partFile.length() > maxBytes) {
            partFile.delete()
            metaFile.delete()
            metadata = null
        }

        // The last byte may have been synced just before the process died.
        val existingBytes = partFile.length()
        if (existingBytes > 0L && metadata?.totalBytes == existingBytes) {
            val actualHash = runCatching { sha256File(partFile, checkActive) }.getOrNull()
            if (actualHash != null && (expectedSha256 == null ||
                    actualHash.equals(expectedSha256, ignoreCase = true))
            ) {
                onProgress(existingBytes, metadata.totalBytes)
                return@withContext ResumableModelDownloadResult(existingBytes, metadata.totalBytes, actualHash)
            }
            partFile.delete()
            metaFile.delete()
            metadata = null
        }

        var restartedFromZero = false
        while (true) {
            awaitResume()
            checkActive()
            val offset = partFile.length()
            if (offset > maxBytes) {
                partFile.delete()
                metaFile.delete()
                throw IOException("模型下载断点超过允许的文件大小")
            }
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
            if (offset > 0L) {
                requestBuilder.header("Range", "bytes=$offset-")
                val validator = metadata?.etag?.takeIf { it.isNotBlank() && !it.startsWith("W/", true) }
                    ?: metadata?.lastModified?.takeIf { it.isNotBlank() }
                if (validator != null) requestBuilder.header("If-Range", validator)
            }
            val request = requestBuilder.build()
            val ownerJob = currentCoroutineContext()[Job]
            val call = client.newCall(request)
            val cancellationHandle = ownerJob?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) { cause ->
                if (cause is java.util.concurrent.CancellationException) call.cancel()
            }
            try {
                call.execute().use { response ->
                    if (response.code == 416 && offset > 0L) {
                        val serverTotal = ResumableModelDownloadPolicy.parseUnsatisfiedRangeTotal(
                            response.header("Content-Range")
                        )
                        if (serverTotal == offset) {
                            val actualHash = sha256File(partFile, checkActive)
                            if (expectedSha256 != null && !actualHash.equals(expectedSha256, true)) {
                                partFile.delete()
                                metaFile.delete()
                                throw IOException("已下载的模型压缩包 SHA-256 校验失败，已清理损坏断点")
                            }
                            writeMetadata(
                                metaFile,
                                ResumeMetadata(
                                    sourceHash,
                                    response.header("ETag") ?: metadata?.etag,
                                    response.header("Last-Modified") ?: metadata?.lastModified,
                                    offset,
                                ),
                            )
                            return@withContext ResumableModelDownloadResult(offset, offset, actualHash)
                        }
                        throw RestartFromZeroException("服务器拒绝现有下载断点，准备从头校正")
                    }
                    if (response.code != 200 && response.code != 206) {
                        throw IOException("模型下载请求失败：HTTP " + response.code)
                    }
                    val body = response.body ?: throw IOException("模型下载响应为空")
                    val responseLength = body.contentLength()
                    if (responseLength == 0L || responseLength < -1L || responseLength > maxBytes) {
                        throw IOException("模型下载响应长度无效或超过大小限制")
                    }
                    val range = if (response.code == 206) {
                        ResumableModelDownloadPolicy.parseContentRange(response.header("Content-Range"))
                            ?: throw RestartFromZeroException("服务器返回了无效的 Content-Range")
                    } else {
                        null
                    }
                    if (range != null && !ResumableModelDownloadPolicy.isValidRangeResponse(
                            requestedOffset = offset,
                            range = range,
                            previouslyKnownTotal = metadata?.totalBytes,
                        )
                    ) {
                        throw RestartFromZeroException("服务器返回的下载断点与本地文件不一致")
                    }
                    val append = response.code == 206 && offset > 0L
                    val writeOffset = if (append) offset else 0L
                    val totalBytes: Long = when {
                        response.code == 206 -> range?.totalBytes
                            ?: if (responseLength > 0L) writeOffset + responseLength else -1L
                        responseLength > 0L -> responseLength
                        else -> -1L
                    }
                    if (!HttpDownloadResponsePolicy.isValidExpectedLength(totalBytes, maxBytes)) {
                        throw IOException("模型压缩包响应声明的总大小无效或超过限制")
                    }
                    if (range != null) {
                        val rangeLength = range.endInclusive - range.start + 1L
                        if (rangeLength <= 0L || rangeLength > maxBytes ||
                            (responseLength > 0L && responseLength != rangeLength)
                        ) {
                            throw RestartFromZeroException("服务器返回的下载分段长度与 Content-Range 不一致")
                        }
                    }
                    val currentEtag = response.header("ETag")
                    if (append && metadata?.etag != null && currentEtag != null &&
                        metadata?.etag != currentEtag
                    ) {
                        throw RestartFromZeroException("模型下载源的 ETag 已变化")
                    }
                    metadata = ResumeMetadata(
                        sourceHash = sourceHash,
                        etag = currentEtag ?: metadata?.etag,
                        lastModified = response.header("Last-Modified") ?: metadata?.lastModified,
                        totalBytes = totalBytes,
                    )
                    writeMetadata(metaFile, metadata!!)
                    val startedAtNanos = System.nanoTime()
                    var receivedThisRequest = 0L
                    var lastProgressBytes = writeOffset
                    var lastProgressAtNanos = startedAtNanos
                    onProgress(writeOffset, totalBytes.coerceAtLeast(0L))
                    body.byteStream().use { input ->
                        FileOutputStream(partFile, append).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                awaitResume()
                                checkActive()
                                ownerJob?.ensureActive()
                                val count = ProgressSafeInput.read(input, buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                val countLong = count.toLong()
                                if (receivedThisRequest > maxBytes - countLong ||
                                    writeOffset > maxBytes - receivedThisRequest - countLong
                                ) {
                                    throw IOException("模型压缩包超过大小限制")
                                }
                                receivedThisRequest += countLong
                                val elapsedMillis = java.util.concurrent.TimeUnit.NANOSECONDS
                                    .toMillis(System.nanoTime() - startedAtNanos).coerceAtLeast(1L)
                                if (shouldFallbackToMirror(receivedThisRequest, elapsedMillis)) {
                                    throw SlowDownloadSourceException(receivedThisRequest, elapsedMillis)
                                }
                                output.write(buffer, 0, count)
                                val cumulative = writeOffset + receivedThisRequest
                                val nowNanos = System.nanoTime()
                                if (cumulative - lastProgressBytes >= 512L * 1024L ||
                                    nowNanos - lastProgressAtNanos >= 250_000_000L ||
                                    (totalBytes > 0L && cumulative >= totalBytes)
                                ) {
                                    onProgress(cumulative, totalBytes.coerceAtLeast(0L))
                                    lastProgressBytes = cumulative
                                    lastProgressAtNanos = nowNanos
                                }
                            }
                            output.flush()
                            output.fd.sync()
                        }
                    }
                    val actualBytes = partFile.length()
                    if (!HttpDownloadResponsePolicy.isCompletePayload(totalBytes, actualBytes, maxBytes)) {
                        throw IOException(
                            "模型下载中断，已保留断点：预期 " + totalBytes + " 字节，当前累计 " + actualBytes + " 字节"
                        )
                    }
                    checkActive()
                    val actualHash = sha256File(partFile, checkActive)
                    if (expectedSha256 != null && !actualHash.equals(expectedSha256, ignoreCase = true)) {
                        partFile.delete()
                        metaFile.delete()
                        throw IOException("模型压缩包 SHA-256 校验失败，已清理损坏断点")
                    }
                    writeMetadata(
                        metaFile,
                        ResumeMetadata(
                            sourceHash,
                            currentEtag ?: metadata?.etag,
                            response.header("Last-Modified") ?: metadata?.lastModified,
                            actualBytes,
                        ),
                    )
                    onProgress(actualBytes, actualBytes)
                    return@withContext ResumableModelDownloadResult(actualBytes, actualBytes, actualHash)
                }
            } catch (restart: RestartFromZeroException) {
                if (restartedFromZero) throw IOException(restart.message + "；从头请求后仍失败", restart)
                partFile.delete()
                metaFile.delete()
                metadata = null
                restartedFromZero = true
            } catch (error: Exception) {
                if (ownerJob?.isCancelled == true &&
                    error !is kotlinx.coroutines.CancellationException
                ) {
                    throw kotlinx.coroutines.CancellationException("模型下载已取消").also { it.initCause(error) }
                }
                throw error
            } finally {
                cancellationHandle?.dispose()
            }
        }
    }

    fun sha256File(file: File, checkActive: () -> Unit = {}): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                checkActive()
                val count = ProgressSafeInput.read(input, buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        checkActive()
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun metadataFile(partFile: File): File = File(partFile.parentFile, partFile.name + ".meta")

    private fun readMetadata(file: File): ResumeMetadata? = runCatching {
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        ResumeMetadata(
            sourceHash = json.getString("sourceHash"),
            etag = json.optString("etag").takeIf { it.isNotBlank() && it != "null" },
            lastModified = json.optString("lastModified").takeIf { it.isNotBlank() && it != "null" },
            totalBytes = json.optLong("totalBytes", -1L),
        )
    }.getOrNull()

    private fun writeMetadata(file: File, metadata: ResumeMetadata) {
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp, false).use { output ->
            val bytes = JSONObject()
                .put("sourceHash", metadata.sourceHash)
                .put("etag", metadata.etag ?: "")
                .put("lastModified", metadata.lastModified ?: "")
                .put("totalBytes", metadata.totalBytes)
                .toString()
                .toByteArray(Charsets.UTF_8)
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun sha256Text(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
