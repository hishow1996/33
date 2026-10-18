package io.translatetube.local.util

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local-only diagnostic history. The log is bounded and never uploaded automatically. */
data class AppLogRecord(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
    val details: String,
    val thread: String,
)

object AppLogStore {
    private const val LOG_DIR = "app_logs"
    private const val LOG_NAME = "runtime.jsonl"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val FILE_COUNT = 3
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    private const val MAX_DETAIL_CHARS = 24_000
    private const val MAX_READ_RECORDS = 800
    private val lock = Any()
    private val recentEvents = java.util.ArrayDeque<String>()
    private const val MAX_RECENT_EVENTS = 16

    fun info(
        context: Context,
        tag: String,
        message: String,
        details: String = "",
        diagnosticContext: Map<String, String> = emptyMap(),
    ) = append(context, "INFO", tag, message, details, diagnosticContext)

    fun warning(
        context: Context,
        tag: String,
        message: String,
        failure: Throwable? = null,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) = append(
        context, "WARN", tag, messageWithRootCause(message, failure),
        failureDetails(failure), diagnosticContext,
    )

    fun error(
        context: Context,
        tag: String,
        message: String,
        failure: Throwable? = null,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) = append(
        context, "ERROR", tag, messageWithRootCause(message, failure),
        failureDetails(failure), diagnosticContext,
    )

    fun recordUncaught(
        context: Context,
        status: String,
        threadName: String,
        failure: Throwable,
        timestampMs: Long = System.currentTimeMillis(),
    ) {
        val level = if (status == "FATAL") "FATAL" else "ERROR"
        appendAt(
            context = context,
            timestampMs = timestampMs,
            level = level,
            tag = "UncaughtException",
            message = failure.javaClass.name + failure.message?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty(),
            details = buildString {
                append("崩溃类型：").append(status).append('\n')
                append(failureDetails(failure))
            },
            threadName = threadName,
            diagnosticContext = mapOf(
                "异常线程" to threadName,
                "处理方式" to if (status == "FATAL") "交由 Android 默认崩溃处理器，进程退出" else "后台异常被保护，进程继续运行",
                "异常类" to failure.javaClass.name,
            ),
        )
    }

    fun readRecent(context: Context, limit: Int = 500): List<AppLogRecord> = synchronized(lock) {
        val directory = logDirectory(context)
        pruneOldFiles(directory)
        val parsed = logFiles(directory).flatMap { file ->
            runCatching {
                file.useLines { lines ->
                    lines.mapNotNull(::parseLine).toList()
                }
            }.getOrElse { emptyList() }
        }.toMutableList()

        // Include an older crash marker created by previous app versions if it is not already
        // represented in the new structured history.
        readLegacyCrash(context)?.let { legacy ->
            val duplicate = parsed.any {
                it.tag == "UncaughtException" &&
                    (it.message.contains(legacy.message.take(80)) ||
                        it.details.contains(legacy.message.take(80)))
            }
            if (!duplicate) parsed.add(legacy)
        }

        parsed.sortedByDescending { it.timestampMs }.take(limit.coerceIn(1, MAX_READ_RECORDS))
    }

    fun exportText(context: Context): String {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val records = readRecent(context, MAX_READ_RECORDS).sortedBy { it.timestampMs }
        return buildString {
            append("大象翻译诊断日志\n")
            append("导出时间：").append(date).append('\n')
            append("应用版本：").append(runCatching { io.translatetube.local.BuildConfig.VERSION_NAME }.getOrDefault("未知")).append('\n')
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("日志仅从本地导出；导出前仍建议检查是否包含个人信息。\n")
            append("=".repeat(56)).append('\n')
            records.forEach { record ->
                append(formatTime(record.timestampMs)).append(" [").append(record.level).append("] ")
                    .append(record.tag).append(" · ").append(record.message).append('\n')
                if (record.thread.isNotBlank()) append("线程：").append(record.thread).append('\n')
                if (record.details.isNotBlank()) append(record.details.trimEnd()).append('\n')
                append("-".repeat(56)).append('\n')
            }
            if (records.isEmpty()) append("暂时没有运行日志。\n")
        }
    }

    fun clear(context: Context): Boolean = synchronized(lock) {
        var success = true
        val directory = logDirectory(context)
        logFiles(directory).forEach { file ->
            if (file.exists() && !file.delete()) success = false
        }
        val legacy = File(File(context.filesDir, "crash"), "last_exception.txt")
        if (legacy.exists() && !legacy.delete()) success = false
        success
    }

    fun formatTime(timestampMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestampMs))

    private fun append(
        context: Context,
        level: String,
        tag: String,
        message: String,
        details: String,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) = appendAt(
        context = context,
        timestampMs = System.currentTimeMillis(),
        level = level,
        tag = tag,
        message = message,
        details = details,
        threadName = Thread.currentThread().name,
        diagnosticContext = diagnosticContext,
    )

    private fun appendAt(
        context: Context,
        timestampMs: Long,
        level: String,
        tag: String,
        message: String,
        details: String,
        threadName: String,
        diagnosticContext: Map<String, String> = emptyMap(),
    ) {
        synchronized(lock) {
            try {
                val directory = logDirectory(context)
                if (!directory.exists() && !directory.mkdirs()) return
                pruneOldFiles(directory)
                val file = File(directory, LOG_NAME)
                val shouldIncludeTrail = level == "WARN" || level == "ERROR" || level == "FATAL"
                val trail = if (shouldIncludeTrail && recentEvents.isNotEmpty()) {
                    recentEvents.joinToString(separator = "\n")
                } else ""
                val safeDetails = redact(
                    enrichDetails(context, level, details, threadName, diagnosticContext, trail),
                ).take(MAX_DETAIL_CHARS)
                val row = JSONObject()
                    .put("timestampMs", timestampMs)
                    .put("level", redact(level).take(12))
                    .put("tag", redact(tag).take(80))
                    .put("message", redact(message).take(1_000))
                    .put("details", safeDetails)
                    .put("thread", redact(threadName).take(120))
                    .toString()
                if (file.exists() && file.length() + row.toByteArray(Charsets.UTF_8).size + 1 > MAX_FILE_BYTES) {
                    rotate(directory)
                }
                File(directory, LOG_NAME).appendText(row + "\n", Charsets.UTF_8)
                val crumb = "${formatTime(timestampMs)} [${redact(level)}] ${redact(tag)} · ${redact(message)}"
                    .replace(Regex("\\s+"), " ")
                    .take(600)
                recentEvents.addLast(crumb)
                while (recentEvents.size > MAX_RECENT_EVENTS) recentEvents.removeFirst()
            } catch (_: Throwable) {
                // Diagnostics must never cause or amplify an application crash.
            }
        }
    }

    private fun enrichDetails(
        context: Context,
        level: String,
        details: String,
        threadName: String,
        diagnosticContext: Map<String, String>,
        recentTrail: String,
    ): String = buildString {
        if (diagnosticContext.isNotEmpty()) {
            append("故障上下文（用于定位发生时的状态）：\n")
            diagnosticContext.toSortedMap().forEach { (key, value) ->
                append("- ").append(key).append("：").append(value).append('\n')
            }
        }
        if (recentTrail.isNotBlank() && (level == "WARN" || level == "ERROR" || level == "FATAL")) {
            append("出错前最近操作/异常轨迹（最多 16 条，按先后顺序）：\n")
            append(recentTrail).append('\n')
        }
        if (level == "WARN" || level == "ERROR" || level == "FATAL") {
            val runtime = Runtime.getRuntime()
            append("运行环境：\n")
            append("- 应用版本：")
                .append(runCatching { io.translatetube.local.BuildConfig.VERSION_NAME }.getOrDefault("未知"))
                .append('\n')
            append("- 设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("- Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("- CPU ABI：").append(Build.SUPPORTED_ABIS.firstOrNull().orEmpty()).append('\n')
            append("- 线程：").append(threadName).append('\n')
            append("- Java 堆内存：已用约 ")
                .append(((runtime.totalMemory() - runtime.freeMemory()).coerceAtLeast(0L) / (1024L * 1024L)))
                .append(" MB / 最大 ")
                .append((runtime.maxMemory() / (1024L * 1024L)))
                .append(" MB\n")
        }
        if (details.isNotBlank()) {
            append("异常详情与调用堆栈：\n")
            append(details)
            if (!details.endsWith('\n')) append('\n')
        }
    }

    private fun parseLine(line: String): AppLogRecord? = runCatching {
        val json = JSONObject(line)
        AppLogRecord(
            timestampMs = json.optLong("timestampMs", 0L),
            level = json.optString("level", "INFO"),
            tag = json.optString("tag", "App"),
            message = json.optString("message", ""),
            details = json.optString("details", ""),
            thread = json.optString("thread", ""),
        ).takeIf { it.timestampMs > 0L && (it.message.isNotBlank() || it.details.isNotBlank()) }
    }.getOrNull()

    private fun readLegacyCrash(context: Context): AppLogRecord? {
        val file = File(File(context.filesDir, "crash"), "last_exception.txt")
        if (!file.isFile) return null
        val content = runCatching { file.readText(Charsets.UTF_8).take(MAX_DETAIL_CHARS) }.getOrNull()
            ?: return null
        val lines = content.lineSequence().toList()
        val exception = lines.firstOrNull { it.startsWith("exception=") }?.substringAfter("exception=")
            ?: return null
        return AppLogRecord(
            timestampMs = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            level = if (lines.firstOrNull() == "FATAL" || lines.firstOrNull() == "SEEN") "FATAL" else "ERROR",
            tag = "PreviousCrash",
            message = exception,
            details = content,
            thread = lines.firstOrNull { it.startsWith("thread=") }?.substringAfter("thread=").orEmpty(),
        )
    }

    private fun logDirectory(context: Context) = File(context.filesDir, LOG_DIR)

    private fun logFiles(directory: File): List<File> =
        (0 until FILE_COUNT).map { index ->
            File(directory, if (index == 0) LOG_NAME else "runtime.$index.jsonl")
        }.filter { it.isFile }

    private fun rotate(directory: File) {
        File(directory, "runtime.${FILE_COUNT - 1}.jsonl").delete()
        for (index in FILE_COUNT - 2 downTo 1) {
            val from = File(directory, "runtime.$index.jsonl")
            if (from.exists()) from.renameTo(File(directory, "runtime.${index + 1}.jsonl"))
        }
        val current = File(directory, LOG_NAME)
        if (current.exists()) current.renameTo(File(directory, "runtime.1.jsonl"))
    }

    private fun pruneOldFiles(directory: File) {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        directory.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun messageWithRootCause(message: String, failure: Throwable?): String {
        if (failure == null) return message
        val root = rootCause(failure)
        val summary = buildString {
            append(failure.javaClass.simpleName)
            failure.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            if (root !== failure) {
                append("；根因=").append(root.javaClass.simpleName)
                root.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
            }
        }
        return (message + " | " + summary).take(1_000)
    }

    private fun rootCause(failure: Throwable): Throwable {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var current = failure
        while (seen.add(current)) {
            val next = current.cause ?: break
            if (next === current || next in seen) break
            current = next
        }
        return current
    }

    private fun failureDetails(failure: Throwable?): String {
        if (failure == null) return ""
        return buildString {
            append("异常根因链：\n")
            val seen = java.util.Collections.newSetFromMap(
                java.util.IdentityHashMap<Throwable, Boolean>(),
            )
            var current: Throwable? = failure
            var depth = 0
            while (current != null && seen.add(current) && depth < 12) {
                append("#").append(depth).append(' ')
                    .append(current.javaClass.name)
                current.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                append('\n')
                current.suppressed.take(4).forEach { suppressed ->
                    append("  附加异常：").append(suppressed.javaClass.name)
                    suppressed.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
                    append('\n')
                }
                current = current.cause
                depth++
            }
            if (current != null) append("根因链已截断或存在循环引用。\n")
            append("\n完整调用堆栈（包括 Caused by / Suppressed）：\n")
            append(StringWriter().also { writer ->
                PrintWriter(writer).use { failure.printStackTrace(it) }
            }.toString())
        }
    }

    /** Redact common credential fields and signed query parameters before writing or exporting. */
    private fun redact(input: String): String {
        var value = input
        val credentials = Regex("(?i)(api[_-]?key|authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|password|client[_-]?secret)(\\s*[=:]\\s*)[^\\s,;]+")
        value = credentials.replace(value) { match -> match.groupValues[1] + match.groupValues[2] + "[REDACTED]" }
        val querySecrets = Regex("(?i)([?&](?:key|token|sig|signature|cookie|auth|password|secret|expires)=)[^&\\s]+")
        value = querySecrets.replace(value) { match -> match.groupValues[1] + "[REDACTED]" }
        return value
    }
}
