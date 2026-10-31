package io.translatetube.local.asr

import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Durable fence around non-idempotent synchronous cloud transcription submissions. */
internal object CloudAsrSubmissionGuardStore {
    private const val MAGIC = 0x43415347 // "CASG"
    private const val VERSION = 1
    private val supportedProviders = setOf("elevenlabs", "cartesia")

    /** Same source/provider remains fenced across language, key and upload-encoding changes. */
    fun fileFor(directory: File, provider: String, sourceIdentity: String): File {
        require(provider in supportedProviders) { "云端转写服务类型不支持请求保护" }
        require(sourceIdentity.isNotBlank()) { "云端转写来源身份不能为空" }
        val digest = sha256("$provider\n$sourceIdentity")
        return File(directory, "cloud-asr-pending-$provider-$digest.state")
    }

    fun hasPendingSubmission(file: File): Boolean = file.exists()

    fun markInFlight(file: File, nowMs: Long = System.currentTimeMillis()) {
        require(nowMs > 0L) { "云端转写请求时间无效" }
        val parent = file.absoluteFile.parentFile ?: throw IOException("云端转写请求保护目录无效")
        if (!parent.isDirectory && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("无法创建云端转写请求保护目录")
        }
        val staged = File.createTempFile("cloud-asr-pending-", ".part", parent)
        var installed = false
        try {
            FileOutputStream(staged).use { raw ->
                DataOutputStream(raw).use { output ->
                    output.writeInt(MAGIC)
                    output.writeInt(VERSION)
                    output.writeLong(nowMs)
                    output.flush()
                    raw.fd.sync()
                }
            }
            AtomicFileInstaller.install(staged, file)
            installed = true
        } finally {
            if (!installed) runCatching { staged.delete() }
        }
    }

    fun clear(file: File) {
        if (file.exists() && !file.delete()) throw IOException("无法清除云端转写请求保护标记")
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
