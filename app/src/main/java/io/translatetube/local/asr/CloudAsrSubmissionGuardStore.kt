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

    fun fileFor(directory: File, provider: String, identity: String, audioDigest: String): File {
        require(provider in supportedProviders) { "云端转写服务类型不支持请求保护" }
        require(identity.isNotBlank()) { "云端转写请求身份不能为空" }
        require(audioDigest.matches(Regex("^[0-9a-f]{64}$"))) { "云端转写音频指纹格式无效" }
        val digest = sha256("$provider\n$identity\n$audioDigest")
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

/** A provider may have accepted/billed a request, but the app has no reusable response. */
internal class CloudAsrSubmissionUncertainException(
    providerName: String,
    cause: Throwable? = null,
) : IOException(
    "云端转写状态不确定：$providerName 的上次请求可能已被服务商接收，但应用没有拿到可恢复的完整结果。" +
        "为避免自动重复提交，已暂停处理。点击“确认重新提交”后可能再次产生费用。",
    cause,
)
