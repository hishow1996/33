package io.translatetube.local.asr

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Persists a completed synchronous cloud-STT response before parsing it into captions.
 * The filename contains only a provider name and hashes, never URLs, keys or transcript text.
 */
internal object CloudAsrResponseCheckpointStore {
    private const val MAGIC = 0x43415352 // "CASR"
    private const val VERSION = 1
    const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L
    private const val MAX_FILE_BYTES = MAX_RESPONSE_BYTES + 512L
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L
    private const val PARTIAL_MAX_AGE_MS = 6L * 60L * 60L * 1_000L
    private val supportedProviders = setOf("elevenlabs", "cartesia")

    fun digestAudioFile(file: File, checkActive: () -> Unit = {}): String {
        require(file.isFile && file.length() > 0L) { "云端转写音频文件不存在或为空" }
        val expectedLength = file.length()
        val expectedModified = file.lastModified()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("audio-bytes:$expectedLength|".toByteArray(Charsets.UTF_8))
        val buffer = ByteArray(64 * 1024)
        FileInputStream(file).use { input ->
            while (true) {
                checkActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    val single = input.read()
                    if (single < 0) break
                    digest.update(single.toByte())
                } else {
                    digest.update(buffer, 0, count)
                }
            }
        }
        checkActive()
        require(file.isFile && file.length() == expectedLength &&
            (expectedModified <= 0L || file.lastModified() == expectedModified)
        ) { "云端转写音频在计算缓存指纹时发生变化" }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun fileFor(directory: File, provider: String, identity: String, audioDigest: String): File {
        require(provider in supportedProviders) { "云端转写服务类型不支持结果缓存" }
        require(identity.isNotBlank()) { "云端转写检查点身份不能为空" }
        require(audioDigest.matches(Regex("^[0-9a-f]{64}$"))) { "云端转写音频指纹格式无效" }
        return File(directory, "cloud-asr-$provider-${sha256(identity)}-$audioDigest.bin")
    }

    fun read(
        file: File,
        provider: String,
        identity: String,
        audioDigest: String,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        if (!file.isFile) return null
        if (file.length() !in 1L..MAX_FILE_BYTES || durationMs <= 0L) {
            file.delete()
            return null
        }
        val response = try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    throw IllegalStateException("云端转写检查点版本不匹配")
                }
                val savedProvider = input.readUTF()
                val savedIdentityDigest = input.readUTF()
                val savedAudioDigest = input.readUTF()
                val savedDurationMs = input.readLong()
                val savedAtMs = input.readLong()
                val payloadLength = input.readInt()
                if (savedProvider != provider ||
                    savedIdentityDigest != sha256(identity) ||
                    savedAudioDigest != audioDigest ||
                    savedDurationMs != durationMs ||
                    savedAtMs <= 0L || nowMs < savedAtMs ||
                    nowMs - savedAtMs > MAX_AGE_MS ||
                    payloadLength !in 1..MAX_RESPONSE_BYTES.toInt()
                ) throw IllegalStateException("云端转写检查点元数据不匹配")
                val bytes = ByteArray(payloadLength)
                input.readFully(bytes)
                if (input.read() != -1) throw IllegalStateException("云端转写检查点包含多余数据")
                String(bytes, Charsets.UTF_8).takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("云端转写检查点内容为空")
            }
        } catch (_: Exception) {
            file.delete()
            return null
        }
        if (nowMs >= file.lastModified()) file.setLastModified(nowMs)
        return response
    }

    /** Commit the complete response atomically; an interrupted write is never restored. */
    fun write(
        file: File,
        provider: String,
        identity: String,
        audioDigest: String,
        durationMs: Long,
        responseText: String,
        checkActive: () -> Unit = {},
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        require(provider in supportedProviders) { "云端转写服务类型不支持结果缓存" }
        require(identity.isNotBlank() && durationMs > 0L && nowMs > 0L) {
            "云端转写检查点元数据无效"
        }
        require(audioDigest.matches(Regex("^[0-9a-f]{64}$"))) { "云端转写音频指纹格式无效" }
        val payload = responseText.toByteArray(Charsets.UTF_8)
        if (payload.isEmpty() || payload.size.toLong() > MAX_RESPONSE_BYTES) return false
        val parent = file.absoluteFile.parentFile
            ?: throw IllegalStateException("云端转写检查点目录无效")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IllegalStateException("无法创建云端转写检查点目录")
        }
        val staged = File.createTempFile("cloud-asr-checkpoint-", ".part", parent)
        var installed = false
        try {
            FileOutputStream(staged).use { raw ->
                DataOutputStream(BufferedOutputStream(raw)).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeUTF(provider)
                    output.writeUTF(sha256(identity))
                    output.writeUTF(audioDigest)
                    output.writeLong(durationMs)
                    output.writeLong(nowMs)
                    output.writeInt(payload.size)
                    output.write(payload)
                    output.flush()
                    raw.fd.sync()
                }
            }
            checkActive()
            AtomicFileInstaller.install(staged, file)
            installed = true
            return true
        } finally {
            if (!installed) runCatching { staged.delete() }
        }
    }

    fun cleanupStale(directory: File, nowMs: Long = System.currentTimeMillis()): Int {
        val files = directory.listFiles() ?: return 0
        var deleted = 0
        for (file in files) {
            if (!file.isFile) continue
            val resultFile = file.name.startsWith("cloud-asr-") && file.name.endsWith(".bin")
            val stageFile = file.name.startsWith("cloud-asr-checkpoint-") && file.name.endsWith(".part")
            val backupFile = file.name.startsWith("cloud-asr-") && file.name.endsWith(".bin.backup")
            if (!resultFile && !stageFile && !backupFile) continue
            val modified = file.lastModified()
            val ageLimit = if (stageFile || backupFile) PARTIAL_MAX_AGE_MS else MAX_AGE_MS
            if (modified > 0L && nowMs >= modified && nowMs - modified > ageLimit && file.delete()) {
                deleted++
            }
        }
        return deleted
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
