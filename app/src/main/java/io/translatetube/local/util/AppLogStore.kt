package io.translatetube.local.util

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Local-only diagnostic history. The log is bounded and never uploaded automatically. */
data class AppLogRecord(
    val timestampMs: Long,
    val level: String,
    val tag: String,
    val message: String,
    val details: String,
    val thread: String,
    /** Stable, privacy-safe key tying a failure to a confirmed recovery event. */
    val issueKey: String = "",
    /** On recovery events, references the original issue key. */
    val resolvesIssueKey: String = "",
    /** Derived on read; the historical error row itself is never overwritten. */
    val recoveredAtMs: Long? = null,
    /** Stable identifier for this individual event; blank only for legacy records. */
    val eventId: String = "",
    /** Process-session identifier used to correlate events across app modules. */
    val sessionId: String = "",
    /** Monotonic event order within the current process session. */
    val sequence: Long = 0L,
    /** Normalized feature/module label for AI triage. */
    val category: String = "",
    /** Operation/request correlation identifier when available. */
    val operationId: String = "",
    /** Root exception type extracted from the persisted exception chain. */
    val errorClass: String = "",
    /** HTTP status extracted from structured context or the error summary; zero means unknown. */
    val httpStatusCode: Int = 0,
    val processId: Int = 0,
    val threadId: Long = 0L,
    /** Monotonic clock value, only comparable within one process. */
    val elapsedRealtimeMs: Long = 0L,
    val schemaVersion: Int = 1,
    /** Parsed root-cause message, kept separately so AI does not need to parse the stack text. */
    val rootCauseMessage: String = "",
    /** First stack frame inside this app; useful as the starting point for source inspection. */
    val firstAppStackFrame: String = "",
    /** Original structured key/value evidence supplied by the failing operation. */
    val diagnosticContext: Map<String, String> = emptyMap(),
    /** Structured cause/suppressed tree and per-exception stack frames. */
    val exceptionSnapshot: AppLogExceptionSnapshot? = null,
)

data class AppLogStackFrame(
    val className: String,
    val methodName: String,
    val fileName: String?,
    val lineNumber: Int,
)

data class AppLogExceptionSnapshot(
    val type: String,
    val message: String,
    val stackFrames: List<AppLogStackFrame>,
    val cause: AppLogExceptionSnapshot? = null,
    val suppressed: List<AppLogExceptionSnapshot> = emptyList(),
    val truncated: Boolean = false,
)

data class AppLogWriterHealth(
    val failedWriteCount: Long,
    val lastFailureTimestampMs: Long,
    val lastFailureDescription: String,
    val lastSuccessTimestampMs: Long,
    val lastOperationSucceeded: Boolean,
) {
    /** Historical failures remain visible, but a later successful write restores current health. */
    val isHealthy: Boolean get() = lastOperationSucceeded
}

object AppLogStore {
    /** App version and source revision shown in diagnostic reports. */
    fun appVersionLabel(): String {
        val version = runCatching { io.translatetube.local.BuildConfig.VERSION_NAME }.getOrDefault("未知").ifBlank { "未知" }
        val sha = runCatching { io.translatetube.local.BuildConfig.GIT_SHA }.getOrDefault("unknown").trim().ifBlank { "unknown" }
        return "$version ($sha)"
    }
    private const val LOG_DIR = "app_logs"
    private const val LOG_NAME = "runtime.jsonl"
    private const val MAX_FILE_BYTES = 2L * 1024L * 1024L
    private const val FILE_COUNT = 3
    private const val RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
    internal const val MAX_DETAIL_CHARS = 24_000
    private const val MAX_READ_RECORDS = 800
    private const val MAX_EXIT_TRACE_BYTES = 48 * 1024
    private const val SESSION_PREFS = "app_log_process_session"
    private const val KEY_SESSION_START_MS = "session_start_ms"
    private const val KEY_SESSION_PID = "session_pid"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_LAST_EXIT_TIMESTAMP = "last_system_exit_timestamp"
    private const val KEY_LAST_UNKNOWN_SESSION_START = "last_unknown_session_start"
    private val lock = Any()
    private val recentEventTrail = AppLogRecentEventTrail(
        maxTrackedScopes = 12,
        maxEventsPerScope = 24,
        unscopedThreadWindowMs = 120_000L,
    )
    private val failedWriteCount = java.util.concurrent.atomic.AtomicLong(0L)
    private val eventSequence = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var currentSessionId: String = ""
    @Volatile private var lastWriteFailureTimestampMs: Long = 0L
    @Volatile private var lastWriteFailureDescription: String = ""
    @Volatile private var lastWriteSuccessTimestampMs: Long = 0L
    @Volatile private var lastLogOperationSucceeded: Boolean = true

    /** Runtime-only health fallback: remains available when the log file itself cannot be written. */
    fun writerHealth(): AppLogWriterHealth = AppLogWriterHealth(
        failedWriteCount = failedWriteCount.get(),
        lastFailureTimestampMs = lastWriteFailureTimestampMs,
        lastFailureDescription = lastWriteFailureDescription,
        lastSuccessTimestampMs = lastWriteSuccessTimestampMs,
        lastOperationSucceeded = lastLogOperationSucceeded,
    )

    private fun noteWriteFailure(operation: String, failure: Throwable? = null) {
        val count = failedWriteCount.incrementAndGet()
        lastWriteFailureTimestampMs = System.currentTimeMillis()
        val reason = failure?.let {
            it.javaClass.simpleName + it.message?.takeIf(String::isNotBlank)?.let { message -> ": $message" }.orEmpty()
        }.orEmpty()
        val reasonSuffix = if (reason.isBlank()) "" else " — $reason"
        lastWriteFailureDescription = redact(operation + reasonSuffix).take(240)
        lastLogOperationSucceeded = false
        // Do not feed this back into AppLogStore: Logcat is the fallback when file logging itself fails.
        runCatching {
            android.util.Log.e(
                "AppLogStore",
                "Local diagnostic write failed ($count failures): $lastWriteFailureDescription",
            )
        }
    }

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
        issueKey: String? = null,
    ) = append(
        context, "WARN", tag, messageWithRootCause(message, failure),
        failureDetails(failure), diagnosticContext,
        issueKey = issueKey ?: stableIssueKey(tag, message),
        failure = failure,
    )

    fun error(
        context: Context,
        tag: String,
        message: String,
        failure: Throwable? = null,
        diagnosticContext: Map<String, String> = emptyMap(),
        issueKey: String? = null,
    ) = append(
        context, "ERROR", tag, messageWithRootCause(message, failure),
        failureDetails(failure), diagnosticContext,
        issueKey = issueKey ?: stableIssueKey(tag, message),
        failure = failure,
    )

    fun recordUncaught(
        context: Context,
        status: String,
        threadName: String,
        failure: Throwable,
        timestampMs: Long = System.currentTimeMillis(),
        diagnosticContext: Map<String, String> = emptyMap(),
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
            ) + diagnosticContext,
            failure = failure,
        )
    }

    /**
     * Start a new process session and inspect the previous session's system exit report when
     * Android provides one. Android 9 and older do not expose ApplicationExitInfo, so absence of
     * a fatal Java exception is recorded only as "unknown", never mislabeled as a confirmed crash.
     */
    fun beginProcessSession(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val previousStart = prefs.getLong(KEY_SESSION_START_MS, 0L)
        val previousPid = prefs.getInt(KEY_SESSION_PID, 0)
        val previousSessionId = prefs.getString(KEY_SESSION_ID, "").orEmpty()
        // Attribute a system exit report to the process session that just ended.
        currentSessionId = previousSessionId.ifBlank { "legacy-unknown" }
        var capturedExit = false

        if (previousStart > 0L && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capturedExit = runCatching {
                recordHistoricalProcessExit(app, prefs, previousStart)
            }.getOrElse { failure ->
                warning(app, "ProcessExitInfo", "读取 Android 历史进程退出记录失败", failure)
                false
            }
        }

        if (previousStart > 0L && !capturedExit) {
            val lastUnknown = prefs.getLong(KEY_LAST_UNKNOWN_SESSION_START, 0L)
            if (lastUnknown != previousStart) {
                info(
                    app,
                    "ProcessLifecycle",
                    "上次进程会话没有可用的系统退出报告；退出原因未知，不能单凭此记录认定为闪退",
                    diagnosticContext = mapOf(
                        "上次进程启动时间" to formatTime(previousStart),
                        "上次进程 PID" to previousPid.toString(),
                        "本次进程 PID" to Process.myPid().toString(),
                        "系统版本是否支持进程退出报告" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R).toString(),
                        "解释" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            "系统没有返回与上次会话匹配的新退出记录，或记录暂不可用"
                        } else {
                            "Android 9 及更低版本不提供 ApplicationExitInfo；可能是正常回收、用户强停、系统低内存、ANR 或原生层退出，无法仅由应用内标记区分"
                        },
                    ),
                )
                prefs.edit().putLong(KEY_LAST_UNKNOWN_SESSION_START, previousStart).apply()
            }
        }

        // Synchronous commit is intentional: if the process dies immediately after startup, the
        // next launch should still have a reliable reference timestamp for system exit reports.
        val newSessionId = UUID.randomUUID().toString()
        currentSessionId = newSessionId
        eventSequence.set(0L)
        prefs.edit()
            .putLong(KEY_SESSION_START_MS, now)
            .putInt(KEY_SESSION_PID, Process.myPid())
            .putString(KEY_SESSION_ID, newSessionId)
            .commit()

        info(
            app,
            "ProcessSession",
            "应用进程会话启动",
            diagnosticContext = mapOf(
                "进程 PID" to Process.myPid().toString(),
                "进程名称" to app.applicationInfo.processName.orEmpty().ifBlank { app.packageName },
                "Android 版本" to Build.VERSION.RELEASE.orEmpty(),
                "SDK 版本" to Build.VERSION.SDK_INT.toString(),
                "会话启动时间" to formatTime(now),
                "上次会话 ID" to previousSessionId.ifBlank { "旧版日志未记录" },
                "本次会话 ID" to newSessionId,
            ),
        )
    }

    private fun recordHistoricalProcessExit(
        context: Context,
        prefs: android.content.SharedPreferences,
        previousSessionStartMs: Long,
    ): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val lastRecordedTimestamp = prefs.getLong(KEY_LAST_EXIT_TIMESTAMP, 0L)
        val exitInfo = activityManager.getHistoricalProcessExitReasons(context.packageName, 0, 12)
            .orEmpty()
            .asSequence()
            .filter { it.timestamp >= previousSessionStartMs }
            .filter { it.timestamp > lastRecordedTimestamp }
            .maxByOrNull { it.timestamp }
            ?: return false

        val reasonName = exitReasonName(exitInfo.reason)
        val level = when (exitInfo.reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "FATAL"
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "ERROR"
            else -> "INFO"
        }
        val advice = exitReasonAdvice(exitInfo.reason)
        val trace = readExitTrace(exitInfo)
        val processStateSummary = runCatching {
            exitInfo.processStateSummary?.toString(Charsets.UTF_8).orEmpty()
        }.getOrDefault("")
        val details = buildString {
            append("Android 系统进程退出诊断：\n")
            append("- 系统退出原因：").append(reasonName).append('\n')
            append("- 应用退出前阶段摘要：")
                .append(processStateSummary.ifBlank { "系统没有保存可用的阶段摘要" }).append('\n')
            append("- 系统状态码：").append(exitInfo.status).append('\n')
            append("- 系统描述：").append(exitInfo.description.orEmpty().ifBlank { "系统未提供描述" }).append('\n')
            append("- 进程名称：").append(exitInfo.processName.orEmpty()).append('\n')
            append("- 进程重要性：").append(exitInfo.importance).append('\n')
            append("- 退出时 PSS：").append(exitInfo.pss).append(" KB\n")
            append("- 退出时 RSS：").append(exitInfo.rss).append(" KB\n")
            append("- 系统记录时间：").append(formatTime(exitInfo.timestamp)).append('\n')
            append("- 诊断解释：").append(advice).append('\n')
            if (trace.isNotBlank()) {
                append("\n系统提供的退出跟踪（截取前 ").append(MAX_EXIT_TRACE_BYTES / 1024).append(" KB）：\n")
                append(trace)
            } else {
                append("- 系统未提供可读取的 ANR/原生退出跟踪。\n")
            }
        }
        appendAt(
            context = context,
            timestampMs = exitInfo.timestamp,
            level = level,
            tag = "ProcessExitInfo",
            message = "上次进程退出：$reasonName；$advice",
            details = details,
            threadName = exitInfo.processName.orEmpty().ifBlank { "系统报告的已退出进程" },
            diagnosticContext = mapOf(
                "系统退出原因" to reasonName,
                "系统退出状态码" to exitInfo.status.toString(),
                "退出进程名称" to exitInfo.processName.orEmpty(),
                "退出时进程重要性" to exitInfo.importance.toString(),
                "退出时 PSS KB" to exitInfo.pss.toString(),
                "退出时 RSS KB" to exitInfo.rss.toString(),
                "退出时间" to formatTime(exitInfo.timestamp),
                "可能原因及建议" to advice,
                "应用退出前阶段摘要" to processStateSummary.ifBlank { "不可用" },
                "是否包含系统跟踪" to trace.isNotBlank().toString(),
            ),
        )
        prefs.edit().putLong(KEY_LAST_EXIT_TIMESTAMP, exitInfo.timestamp).commit()
        return true
    }

    /**
     * Preserve a compact, non-sensitive snapshot in ActivityManager process-state metadata.
     * Android may attach it to ApplicationExitInfo even if queued file logs were not flushed.
     * The platform limit is 128 bytes; this payload is deliberately ASCII-only and <= 120 bytes.
     */
    fun updateProcessStateSummary(context: Context, summary: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val safe = summary
                .map { if (it.code in 32..126) it else '_' }
                .joinToString("")
                .take(120)
            if (safe.isBlank()) return
            val manager = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return
            manager.setProcessStateSummary(safe.toByteArray(Charsets.US_ASCII))
        }
    }
    private fun readExitTrace(exitInfo: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        return runCatching {
            val input = exitInfo.traceInputStream ?: return@runCatching ""
            input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                var remaining = MAX_EXIT_TRACE_BYTES
                while (remaining > 0) {
                    val read = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
                output.toByteArray().toString(Charsets.UTF_8)
                    .replace("\u0000", "")
                    .take(12_000)
            }
        }.getOrDefault("")
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "应用自行结束（REASON_EXIT_SELF）"
        ApplicationExitInfo.REASON_SIGNALED -> "进程收到系统信号（REASON_SIGNALED）"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "系统因内存压力结束进程（REASON_LOW_MEMORY）"
        ApplicationExitInfo.REASON_CRASH -> "Java/Kotlin 未捕获异常导致进程崩溃（REASON_CRASH）"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "原生层崩溃（JNI/C/C++/音视频或 ASR 本地库，REASON_CRASH_NATIVE）"
        ApplicationExitInfo.REASON_ANR -> "应用无响应（ANR）"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "应用初始化失败"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限或系统策略变更导致进程结束"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "进程资源使用超出系统限制"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "系统记录为用户请求结束进程"
        ApplicationExitInfo.REASON_USER_STOPPED -> "用户从系统界面停止应用"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖的系统进程或服务已退出"
        ApplicationExitInfo.REASON_OTHER -> "系统记录的其他退出原因"
        else -> "未识别的系统退出原因（代码 $reason）"
    }

    private fun exitReasonAdvice(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH ->
            "系统确认发生 Java 崩溃。优先查看同一时间附近的 UncaughtException 日志和完整根因链；若缺少对应堆栈，可能崩溃记录写入前进程已终止。"
        ApplicationExitInfo.REASON_CRASH_NATIVE ->
            "系统判定为原生层崩溃，Java 异常捕获器可能收不到原因。查看附带的系统跟踪、JNI/native 库调用链、CPU ABI 与崩溃前的 ASR/音视频操作。"
        ApplicationExitInfo.REASON_ANR ->
            "系统判定应用无响应。检查退出跟踪中主线程在等待什么；重点排查主线程同步 I/O、长时间计算、锁等待和音视频/模型初始化是否阻塞界面。"
        ApplicationExitInfo.REASON_LOW_MEMORY ->
            "系统因内存压力结束进程。对照 PSS/RSS、Java 堆和最近操作，检查是否同时加载转写与配音模型、缓存完整音频/字幕或保留过多播放器资源。"
        ApplicationExitInfo.REASON_SIGNALED ->
            "进程由信号结束。检查系统状态码、退出跟踪与当时操作；可能涉及系统强制终止、原生崩溃信号或外部结束，不能仅靠信号类别断言具体代码缺陷。"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE ->
            "系统判定进程资源使用过高。检查退出前模型加载、音频缓存、后台解码和任务并发情况。"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE ->
            "系统记录为进程初始化失败。优先查看启动阶段日志、Application 初始化、原生库加载和设备兼容性错误。"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED ->
            "依赖的系统进程或服务退出。检查相邻时间的播放器、系统 TTS 服务、WebView 或媒体服务日志，并确认相关系统服务可用。"
        ApplicationExitInfo.REASON_USER_REQUESTED,
        ApplicationExitInfo.REASON_USER_STOPPED,
        ApplicationExitInfo.REASON_EXIT_SELF ->
            "系统把本次退出归类为用户请求或应用自行结束，通常不应单凭此原因判断为程序缺陷；对照操作轨迹确认是否主动退出或重启。"
        else ->
            "系统没有给出足够具体的根因。先对照退出时间附近的操作轨迹、同一会话的错误日志和设备状态，再结合复现步骤定位。"
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

        // Deduplicate only byte-for-byte equivalent events while retaining repeated failures at
        // different times or with different diagnostics.
        val unique = parsed.distinctBy { record ->
            if (record.eventId.isNotBlank()) {
                "event:" + record.eventId
            } else {
                listOf(
                    record.timestampMs, record.level, record.tag, record.message,
                    record.details, record.thread, record.issueKey, record.resolvesIssueKey,
                ).joinToString("\u001f")
            }
        }
        val recoveryTimesByIssue = unique.asSequence()
            .filter { it.resolvesIssueKey.isNotBlank() }
            .groupBy { it.resolvesIssueKey }
            .mapValues { (_, events) -> events.map { it.timestampMs }.distinct().sorted() }

        // Recovery state is derived from a later explicit success event, not inferred from time passing.
        unique.map { record ->
            val recoveredAt = if (record.issueKey.isNotBlank() && isIssueLevel(record.level)) {
                recoveryTimesByIssue[record.issueKey]?.firstOrNull { it > record.timestampMs }
            } else null
            record.copy(recoveredAtMs = recoveredAt)
        }.sortedWith(
            compareByDescending<AppLogRecord> { it.timestampMs }
                .thenByDescending { it.sequence },
        ).take(limit.coerceIn(1, MAX_READ_RECORDS))
    }

    /** Stable, one-way key: raw URLs/session IDs never appear in the log as the identity. */
    fun stableIssueKey(category: String, identity: String): String {
        val safeCategory = category.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
            .take(40).ifBlank { "issue" }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$category\u0000$identity".toByteArray(Charsets.UTF_8))
            .take(10)
            .joinToString("") { byte -> "%02x".format(Locale.US, byte.toInt() and 0xff) }
        return "$safeCategory:$digest"
    }

    private fun isIssueLevel(level: String): Boolean =
        level == "WARN" || level == "ERROR" || level == "FATAL"

    /** Record a recovery only when the caller has evidence that the same operation succeeded. */
    fun resolveIssue(
        context: Context,
        issueKey: String,
        message: String,
        diagnosticContext: Map<String, String> = emptyMap(),
    ): Boolean = synchronized(lock) {
        val key = issueKey.trim()
        if (key.isBlank()) return@synchronized false
        val now = System.currentTimeMillis()
        val unresolved = readRecent(context, MAX_READ_RECORDS).any { record ->
            record.issueKey == key &&
                isIssueLevel(record.level) &&
                record.timestampMs < now &&
                record.recoveredAtMs == null
        }
        if (!unresolved) return@synchronized false
        appendAt(
            context = context,
            timestampMs = now,
            level = "INFO",
            tag = "LogRecovery",
            message = "故障已恢复：$message",
            details = "关联故障编号：$key\n恢复依据：调用方已确认对应操作成功完成。",
            threadName = Thread.currentThread().name,
            diagnosticContext = diagnosticContext,
            resolvesIssueKey = key,
        )
        true
    }

    /** UUID extracted from this log event, if it belongs to a translation operation. */
    fun translationDiagnosticId(record: AppLogRecord): String? =
        TranslationDiagnosticPackPolicy.idFromRecord(record)

    /**
     * Export only records sharing one translation diagnostic ID. This intentionally excludes
     * unrelated browsing/playback logs, API secrets and complete request/response bodies.
     */
    fun exportTranslationDiagnosticPack(context: Context, diagnosticId: String): String {
        val normalizedId = TranslationDiagnosticPackPolicy.normalizeId(diagnosticId)
            ?: throw IllegalArgumentException("翻译诊断 ID 格式无效")
        val records = TranslationDiagnosticPackPolicy.relatedRecords(
            records = readRecent(context, MAX_READ_RECORDS),
            diagnosticId = normalizedId,
        )
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return buildString {
            append("大象翻译 AI 故障诊断包\n")
            append("翻译诊断 ID：").append(normalizedId).append('\n')
            append("导出时间：").append(date).append('\n')
            append("应用版本：").append(appVersionLabel()).append('\n')
            append("设备：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
            append("Android：").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("关联事件数：").append(records.size).append('\n')
            append("范围：仅包含相同翻译诊断 ID 的本地事件；不含完整 API 请求体或密钥。\n")
            append("分享前请检查字幕短片段是否含有个人信息。\n")
            append("=".repeat(64)).append('\n')
            if (records.isEmpty()) {
                append("没有找到该诊断 ID 对应的本地日志。可能已被日志保留策略清理，请刷新日志后重试。\n")
            }
            records.forEachIndexed { index, record ->
                append("#").append(index + 1).append(' ')
                    .append(formatTime(record.timestampMs))
                    .append(" [").append(record.level).append("] ")
                    .append(redact(AppLogTitleResolver.title(record.tag, record.message)))
                    .append(" [模块：").append(redact(record.tag)).append("]\n")
                append("事件 ID：").append(redact(record.eventId.ifBlank { "旧版日志未记录" })).append('\n')
                append("进程会话：").append(redact(record.sessionId.ifBlank { "旧版日志未记录" })).append('\n')
                append("事件序号：").append(record.sequence.takeIf { it > 0 } ?: "未知").append('\n')
                append("操作关联 ID：").append(redact(record.operationId.ifBlank { "未记录" })).append('\n')
                append("异常类型：").append(redact(record.errorClass.ifBlank { "未记录" })).append('\n')
                append("根因消息：").append(redact(record.rootCauseMessage).ifBlank { "未提取" }).append('\n')
                append("首个应用代码栈帧：").append(redact(record.firstAppStackFrame).ifBlank { "未记录" }).append('\n')
                append("HTTP 状态码：").append(record.httpStatusCode.takeIf { it > 0 } ?: "未知").append('\n')
                record.exceptionSnapshot?.let { snapshot ->
                    append("结构化异常树 JSON：")
                        .append(exceptionSnapshotForExport(snapshot).toString())
                        .append('\n')
                }
                if (record.diagnosticContext.isNotEmpty()) {
                    append("结构化诊断字段：\n")
                    record.diagnosticContext.toSortedMap().forEach { (key, value) ->
                        append("- ").append(redact(key)).append("：").append(redact(value)).append('\n')
                    }
                }
                if (record.thread.isNotBlank()) append("线程：").append(redact(record.thread)).append('\n')
                if (record.issueKey.isNotBlank()) append("故障编号：").append(record.issueKey).append('\n')
                if (record.details.isNotBlank()) append(redact(record.details.trimEnd())).append('\n')
                append("-".repeat(64)).append('\n')
            }
            append("AI 分析要求：先按时间顺序还原任务，再区分网络/HTTP、响应格式解析、字幕行对齐、译文质量校验和应用自身异常；优先分析结构化异常树中的 cause 与 suppressed，并检查 truncated 标记；指出具体证据、最可能责任模块、待核对文件/方法及验证方案。不得把推测写成已证实根因。\n")
            append("调用位置说明：日志调用位置/调用链用于定位输出这条日志的源码分支，不等于异常真正抛出位置；有 Throwable 时以异常树及其堆栈为主；没有 Throwable 时应检查该分支的错误码、返回值和控制流，并明确标记堆栈证据缺失。\n")
        }
    }

    fun exportText(context: Context): String {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return AppLogAiReport.render(
            records = readRecent(context, MAX_READ_RECORDS),
            exportedAt = exportedAt,
            appVersion = appVersionLabel(),
            device = Build.MANUFACTURER + " " + Build.MODEL,
            androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            writerHealth = writerHealth(),
        )
    }

    /** Compact clipboard version; file export continues to use the complete exportText report. */
    fun exportCompactText(context: Context): String {
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return AppLogAiReport.renderCompact(
            records = readRecent(context, MAX_READ_RECORDS),
            exportedAt = exportedAt,
            appVersion = appVersionLabel(),
            device = Build.MANUFACTURER + " " + Build.MODEL,
            androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            writerHealth = writerHealth(),
        )
    }

    /**
     * Resolve exactly one selected failure and retain only its provably correlated context.
     * The selected event remains the sole primary incident in both export formats.
     */
    private fun resolveIncidentExportRecords(
        context: Context,
        target: AppLogRecord,
    ): Pair<AppLogRecord, List<AppLogRecord>> {
        val all = readRecent(context, MAX_READ_RECORDS).sortedWith(
            compareBy<AppLogRecord> { it.timestampMs }.thenBy { it.sequence },
        )
        val selected = all.firstOrNull { record ->
            if (target.eventId.isNotBlank()) {
                record.eventId == target.eventId
            } else {
                record.timestampMs == target.timestampMs &&
                    record.tag == target.tag &&
                    record.message == target.message
            }
        } ?: target

        fun isSelected(record: AppLogRecord): Boolean =
            if (selected.eventId.isNotBlank()) record.eventId == selected.eventId
            else record.timestampMs == selected.timestampMs &&
                record.tag == selected.tag && record.message == selected.message

        val related = all.filter { record ->
            if (isSelected(record)) {
                true
            } else if (
                selected.issueKey.isNotBlank() &&
                record.resolvesIssueKey == selected.issueKey
            ) {
                // A later, explicit success event is relevant even if it belongs to a newer session.
                true
            } else if (
                selected.sessionId.isBlank() ||
                record.sessionId != selected.sessionId
            ) {
                false
            } else if (selected.operationId.isNotBlank()) {
                record.operationId == selected.operationId
            } else {
                val deltaMs = kotlin.math.abs(record.timestampMs - selected.timestampMs)
                deltaMs <= 120_000L &&
                    (selected.sequence == 0L || record.sequence == 0L ||
                        kotlin.math.abs(record.sequence - selected.sequence) <= 40L)
            }
        }
        val bounded = TranslationDiagnosticPackPolicy.mergeIncidentRecords(
            allRecords = all,
            incidentContextRecords = related,
            selected = selected,
            maxContextRecords = 80,
        )
        return selected to bounded
    }

    /**
     * Export one selected error as a full diagnostic file. Unlike the clipboard repair pack,
     * this path preserves the complete stored details and exception stack without the 4,000
     * character chat limit.
     */
    fun exportIncidentFullText(context: Context, target: AppLogRecord): String {
        val (selected, relatedRecords) = resolveIncidentExportRecords(context, target)
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return AppLogAiReport.renderIncidentFull(
            records = relatedRecords,
            target = selected,
            exportedAt = exportedAt,
            appVersion = appVersionLabel(),
            device = Build.MANUFACTURER + " " + Build.MODEL,
            androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            writerHealth = writerHealth(),
        )
    }

    /** Create a small, self-contained AI repair package for clipboard sharing. */
    fun exportIncidentText(context: Context, target: AppLogRecord): String {
        val (selected, bounded) = resolveIncidentExportRecords(context, target)
        val exportedAt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        return AppLogAiReport.renderIncident(
            records = bounded,
            target = selected,
            exportedAt = exportedAt,
            appVersion = appVersionLabel(),
            device = Build.MANUFACTURER + " " + Build.MODEL,
            androidVersion = Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")",
            writerHealth = writerHealth(),
        )
    }

    /** Apply the same credential scrubber to legacy and current records before sharing. */
    fun redactForExport(value: String): String = redact(value)

    private fun activeSessionId(context: Context): String {
        currentSessionId.takeIf { it.isNotBlank() }?.let { return it }
        return synchronized(lock) {
            currentSessionId.ifBlank {
                val prefs = context.applicationContext.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
                val stored = prefs.getString(KEY_SESSION_ID, "").orEmpty()
                val resolved = stored.ifBlank { UUID.randomUUID().toString() }
                currentSessionId = resolved
                if (stored.isBlank()) prefs.edit().putString(KEY_SESSION_ID, resolved).apply()
                resolved
            }
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
        // Do not let pre-clear operations leak into the next error's "recent activity" context.
        recentEventTrail.clear()
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
        issueKey: String? = null,
        resolvesIssueKey: String = "",
        failure: Throwable? = null,
    ) = appendAt(
        context = context,
        timestampMs = System.currentTimeMillis(),
        level = level,
        tag = tag,
        message = message,
        details = details,
        threadName = Thread.currentThread().name,
        diagnosticContext = diagnosticContext,
        issueKey = issueKey,
        resolvesIssueKey = resolvesIssueKey,
        failure = failure,
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
        issueKey: String? = null,
        resolvesIssueKey: String = "",
        failure: Throwable? = null,
    ) {
        synchronized(lock) {
            try {
                val directory = logDirectory(context)
                if (!directory.exists() && !directory.mkdirs()) {
                    noteWriteFailure("无法创建本地日志目录：" + directory.name)
                    return
                }
                pruneOldFiles(directory)
                val file = File(directory, LOG_NAME)
                val currentThread = Thread.currentThread()
                val eventId = UUID.randomUUID().toString()
                val sequence = eventSequence.incrementAndGet()
                val sessionId = activeSessionId(context)
                val elapsedRealtimeMs = SystemClock.elapsedRealtime()
                val safeMessage = redact(message).take(1_000)
                val safeTag = redact(tag).take(80)
                val category = runCatching {
                    AppLogTitleResolver.featureLabel(safeTag, safeMessage)
                }.getOrDefault("未分类日志")
                val effectiveIssueKey = issueKey?.takeIf { it.isNotBlank() }
                    ?: if (isIssueLevel(level)) stableIssueKey(tag, message) else ""
                val effectiveDiagnosticContext = AppLogOperationContextHolder.current.get()
                    ?.let { operation -> applyOperationContext(diagnosticContext, operation) }
                    ?: diagnosticContext
                val callSiteFields = if (tag == "ProcessExitInfo") {
                    mapOf(
                        "日志调用位置采集状态" to "历史进程退出记录；系统未提供原进程的应用调用栈",
                        "日志调用位置" to "不可用",
                        "日志调用链（最多8帧）" to "ApplicationExitInfo 不包含原进程 Java/Kotlin 调用链",
                    )
                } else {
                    AppLogCallSitePolicy.diagnosticFields(currentThread.stackTrace)
                }
                val exceptionCaptureFields = mapOf(
                    "异常对象采集状态" to when {
                        failure != null -> "已提供 Throwable；将尝试保存 cause、suppressed 和异常自身堆栈"
                        isIssueLevel(level) -> "调用方未提供 Throwable；本记录没有异常对象堆栈，仅有日志调用位置和事件上下文"
                        else -> "普通事件未提供 Throwable；不代表发生异常"
                    },
                )
                val baseDiagnosticContext = effectiveDiagnosticContext + callSiteFields + exceptionCaptureFields
                val operationIdForTrail = extractOperationId(baseDiagnosticContext, details)
                val shouldIncludeTrail = isIssueLevel(level)
                val trailSnapshot = if (shouldIncludeTrail) {
                    recentEventTrail.snapshot(operationIdForTrail, currentThread.id, timestampMs)
                } else {
                    AppLogRecentEventTrailSnapshot("不适用", "")
                }
                val contextWithTrailScope = if (shouldIncludeTrail) {
                    baseDiagnosticContext + ("近期操作轨迹关联方式" to trailSnapshot.scopeLabel)
                } else {
                    baseDiagnosticContext
                }
                val diagnosticPriorityKeys = setOf(
                    "操作关联 ID",
                    "视频来源",
                    "视频会话指纹",
                    "处理代数",
                    "本轮处理已耗时 ms",
                    "当前处理阶段",
                    "当前 UI 阶段",
                    "当前处理步骤",
                    "失败前阶段",
                    "处理流程",
                    "转写引擎",
                    "翻译引擎",
                    "配音引擎",
                    "当前字幕行数",
                    "字幕总行数",
                    "已获得有效译文行数",
                    "已准备配音行数",
                    "已生成可播放配音行数",
                    "当前视频字幕索引",
                    "当前实际配音字幕索引",
                    "近期操作轨迹关联方式",
                    "日志调用位置采集状态",
                    "日志调用位置",
                    "日志调用类",
                    "日志调用方法",
                    "日志调用文件",
                    "日志调用行号",
                    "日志调用链（最多8帧）",
                    "异常对象采集状态",
                    "诊断事件类型",
                    "当前处理子步骤",
                    "失败子阶段",
                    "转写服务商 ID",
                    "云端模型 ID",
                    "本地 ASR 模型 ID",
                    "PCM 音频文件字节数",
                    "PCM 音频时长 ms",
                    "PCM 采样率 Hz",
                    "VAD 最终分段总数",
                    "本地 ASR 已完成分段数",
                    "云端请求阶段",
                    "最近转写进度",
                    "HTTP 状态码",
                    "当前批次",
                    "本次请求耗时 ms",
                    "故障阶段",
                    "字幕行号",
                    "当前视频位置 ms",
                    "待合成队列总长度",
                    "PCM 内存缓存条目数",
                    "配音引擎 ID",
                    "当前批次进度",
                    "当前翻译批次",
                    "当前正在识别分段起始序号",
                    "当前正在识别分段结束序号",
                    "当前正在识别分段组数量",
                    "当前分段组音频起始 ms",
                    "当前分段组音频结束 ms",
                    "当前分段组 PCM 样本数",
                    "失败分段组起始序号",
                    "失败分段组结束序号",
                    "失败分段组音频起始 ms",
                    "失败分段组音频结束 ms",
                    "失败分段组数量",
                    "当前正在合成字幕索引",
                    "失败子阶段",
                    "本次翻译批次大小",
                    "本次请求耗时 ms",
                    "请求阶段",
                    "错误分类",
                    "响应文本字符数",
                    "输出音频字节数",
                    "PCM 样本数",
                    "音频时长 ms",
                    "音频采样率 Hz",
                    "本句合成处理耗时 ms",
                    "合成调用次数（含重试）",
                    "转写已完成分段",
                    "转写总分段",
                    "本地 ASR 总分段数",
                    "原始音频文件字节数",
                    "云端上传文件字节数",
                    "云端 WAV 文件字节数",
                )
                // Keep the exact same bounded field set in the visible details and AI export.
                val contextBudget = AppLogDiagnosticContextPolicy.bound(
                    input = contextWithTrailScope,
                    priorityKeys = diagnosticPriorityKeys,
                    sanitize = { value -> redact(value) },
                )
                val safeContext = contextBudget.fields
                val operationId = extractOperationId(safeContext, details)
                val eventMetadata = linkedMapOf(
                    "发生时间" to formatTime(timestampMs),
                    "日志级别" to redact(level).take(12),
                    "日志模块" to safeTag,
                    "事件摘要" to safeMessage,
                    "事件 ID" to eventId,
                    "进程会话 ID" to sessionId,
                    "事件序号" to sequence.toString(),
                    "操作关联 ID" to operationId.ifBlank { "未记录" },
                    "进程 PID" to Process.myPid().toString(),
                    "线程 ID" to currentThread.id.toString(),
                    "线程名称" to threadName,
                    "进程内单调时间 ms" to elapsedRealtimeMs.toString(),
                    "异常对象采集状态" to exceptionCaptureFields.getValue("异常对象采集状态"),
                )
                val safeDetails = truncatePreservingEdges(
                    redact(
                        enrichDetails(
                            context,
                            level,
                            safeTag,
                            details,
                            threadName,
                            safeContext,
                            trailSnapshot.summary,
                            trailSnapshot.scopeLabel,
                            eventMetadata,
                        ),
                    ),
                    MAX_DETAIL_CHARS,
                )
                val errorClass = extractExceptionClass(safeMessage, safeDetails)
                val rootCauseMessage = extractRootCauseMessage(safeDetails)
                val firstAppStackFrame = extractFirstAppStackFrame(safeDetails)
                val httpStatusCode = extractHttpStatusCode(safeMessage, safeDetails, safeContext)
                val structuredException = failure?.let { thrown ->
                    runCatching { snapshotExceptionTree(thrown) }.getOrNull()
                }
                val row = JSONObject()
                    .put("schemaVersion", 5)
                    .put("eventId", eventId)
                    .put("logCallSiteStatus", redact(safeContext["日志调用位置采集状态"].orEmpty()))
                    .put("logCallSite", redact(safeContext["日志调用位置"].orEmpty()))
                    .put("logCallChain", redact(safeContext["日志调用链（最多8帧）"].orEmpty()))
                    .put("exceptionCaptureStatus", redact(safeContext["异常对象采集状态"].orEmpty()))
                    .put("sessionId", sessionId)
                    .put("sequence", sequence)
                    .put("elapsedRealtimeMs", elapsedRealtimeMs)
                    .put("processId", Process.myPid())
                    .put("threadId", currentThread.id)
                    .put("timestampMs", timestampMs)
                    .put("level", redact(level).take(12))
                    .put("category", redact(category).take(80))
                    .put("tag", safeTag)
                    .put("message", safeMessage)
                    .put("operationId", redact(operationId).take(120))
                    .put("errorClass", redact(errorClass).take(240))
                    .put("rootCauseMessage", redact(rootCauseMessage).take(1_200))
                    .put("firstAppStackFrame", redact(firstAppStackFrame).take(600))
                    .put("httpStatusCode", httpStatusCode)
                    .put(
                        "diagnosticContext",
                        JSONObject().apply {
                            safeContext.toSortedMap().forEach { (key, value) -> put(key, value) }
                        },
                    )
                    .put("exceptionSnapshot", structuredException?.let(::exceptionSnapshotToJson) ?: JSONObject.NULL)
                    .put("details", safeDetails)
                    .put("thread", redact(threadName).take(120))
                    .put("issueKey", effectiveIssueKey.take(100))
                    .put("resolvesIssueKey", resolvesIssueKey.take(100))
                    .toString()
                if (file.exists() && file.length() + row.toByteArray(Charsets.UTF_8).size + 1 > MAX_FILE_BYTES) {
                    rotate(directory)
                }
                File(directory, LOG_NAME).appendText(row + "\n", Charsets.UTF_8)
                lastWriteSuccessTimestampMs = System.currentTimeMillis()
                lastLogOperationSucceeded = true
                val crumb = "${formatTime(timestampMs)} [${redact(level)}] event=${eventId.take(8)} seq=$sequence category=$category $safeTag · $safeMessage"
                    .replace(Regex("\\s+"), " ")
                    .take(720)
                recentEventTrail.add(operationId, currentThread.id, timestampMs, crumb)
            } catch (failure: Throwable) {
                // Diagnostics must never cause or amplify an application crash. Report write
                // failures through Logcat and an in-memory health counter instead of recursing.
                noteWriteFailure("写入本地诊断日志失败", failure)
            }
        }
    }

    private fun enrichDetails(
        context: Context,
        level: String,
        tag: String,
        details: String,
        threadName: String,
        diagnosticContext: Map<String, String>,
        recentTrail: String,
        recentTrailScope: String,
        eventMetadata: Map<String, String>,
    ): String = buildString {
        if (diagnosticContext.isNotEmpty()) {
            append("故障上下文（用于定位发生时的状态）：\n")
            diagnosticContext.toSortedMap().forEach { (key, value) ->
                append("- ").append(key).append("：").append(value).append('\n')
            }
        }
        append("日志事件元数据：\n")
        eventMetadata.forEach { (key, value) ->
            append("- ").append(key).append("：").append(value).append('\n')
        }
        if (level == "WARN" || level == "ERROR" || level == "FATAL") {
            append("近期操作轨迹关联方式：").append(recentTrailScope).append('\n')
            if (recentTrail.isNotBlank()) {
                append("出错前最近操作/异常轨迹（最多 24 条，按先后顺序）：\n")
                append(recentTrail).append('\n')
            } else {
                append("同一关联范围内没有可用的前序事件；不使用其他任务或线程的事件补齐。\n")
            }
        }
        if (level == "WARN" || level == "ERROR" || level == "FATAL") {
            val runtime = Runtime.getRuntime()
            append("运行环境：\n")
            append("- 应用版本：")
                .append(appVersionLabel())
                .append(" (versionCode ")
                .append(runCatching { io.translatetube.local.BuildConfig.VERSION_CODE }.getOrDefault(-1))
                .append(")\n")
            append("- 应用包名：").append(context.packageName).append('\n')
            append("- targetSdk：")
                .append(runCatching { context.applicationInfo.targetSdkVersion }.getOrDefault(-1))
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
            runCatching {
                val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memory = ActivityManager.MemoryInfo()
                manager?.getMemoryInfo(memory)
                if (manager != null) {
                    append("- 系统可用内存：").append(memory.availMem / (1024L * 1024L))
                        .append(" MB / 总内存约 ").append(memory.totalMem / (1024L * 1024L)).append(" MB\n")
                    append("- 系统低内存状态：").append(memory.lowMemory).append('\n')
                }
            }
            this.appendDiagnosticEnvironment(context, tag, details)
        }
        if (details.isNotBlank()) {
            append("异常详情与调用堆栈：\n")
            append(details)
            if (!details.endsWith('\n')) append('\n')
        }
    }

    /**
     * Add only task-relevant diagnostics. Values describe device/runtime state; URL paths,
     * cookies, account identifiers and request bodies are intentionally excluded.
     */
    private fun StringBuilder.appendDiagnosticEnvironment(
        context: Context,
        tag: String,
        details: String,
    ) {
        val scope = (tag + " " + details).lowercase(Locale.ROOT)
        fun hasAny(vararg needles: String): Boolean = needles.any { scope.contains(it.lowercase(Locale.ROOT)) }
        val networkRelated = hasAny(
            "http", "https", "socket", "network", "timeout", "translat", "transcrib",
            "web", "pikpak", "yt-dlp", "download", "下载", "翻译", "转写", "请求", "网络", "模型",
        )
        val webRelated = hasAny("webview", "webplayer", "websource", "webpage", "pikpak", "nuxt", "网页", "白屏", "脚本")
        val audioRelated = hasAny(
            "audio", "audiotrack", "tts", "dubbing", "playback", "pcm", "wav",
            "配音", "音频", "音轨", "声音", "播放", "朗读",
        )
        val storageRelated = hasAny(
            "model", "download", "install", "cache", "pcm", "wav", "transcrib",
            "模型", "下载", "安装", "缓存", "转写", "音频",
        )
        if (!networkRelated && !webRelated && !audioRelated && !storageRelated) return

        append("专项诊断快照：\n")
        if (networkRelated) {
            runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val info = manager?.activeNetworkInfo
                append("- 系统网络状态：").append(
                    when {
                        manager == null -> "连接管理器不可用"
                        info == null -> "系统未提供活动网络信息"
                        info.isConnected -> "已连接"
                        info.isAvailable -> "网络可用但未连接"
                        else -> "未连接或不可用"
                    },
                ).append('\n')
                if (info != null) {
                    append("- 网络类型：").append(info.typeName.orEmpty().ifBlank { "未知" })
                        .append(" / ").append(info.subtypeName.orEmpty().ifBlank { "未知" }).append('\n')
                    append("- 是否漫游：").append(info.isRoaming).append('\n')
                }
            }.onFailure {
                append("- 系统网络状态：读取失败（").append(it.javaClass.simpleName).append("）\n")
            }
        }
        if (webRelated) {
            append("- WebView 提供方：")
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                runCatching {
                    val provider = android.webkit.WebView.getCurrentWebViewPackage()
                    append(provider?.packageName.orEmpty().ifBlank { "系统未返回" })
                        .append("；版本：")
                        .append(provider?.versionName.orEmpty().ifBlank { "系统未返回" })
                        .append('\n')
                }.onFailure {
                    append("读取失败（").append(it.javaClass.simpleName).append("）\n")
                }
            } else {
                append("当前 Android API 不提供此接口\n")
            }
        }
        if (audioRelated) {
            runCatching {
                val manager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                if (manager == null) {
                    append("- 音频输出状态：AudioManager 不可用\n")
                } else {
                    append("- 音频模式：").append(manager.mode).append('\n')
                    append("- 媒体音量：").append(manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
                        .append('/').append(manager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).append('\n')
                    append("- 扬声器电话模式：").append(manager.isSpeakerphoneOn).append('\n')
                    if (android.os.Build.VERSION.SDK_INT >= 23) {
                        val outputTypes = manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                            .map { device ->
                                when (device.type) {
                                    android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
                                    android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "听筒"
                                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
                                    android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
                                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
                                    android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙通话音频"
                                    android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
                                    android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳机"
                                    android.media.AudioDeviceInfo.TYPE_HDMI -> "HDMI 音频"
                                    else -> "其他输出类型 " + device.type
                                }
                            }.distinct()
                        append("- 系统报告的音频输出设备：")
                            .append(outputTypes.joinToString("、").ifBlank { "无/系统未报告" }).append('\n')
                    }
                }
            }.onFailure {
                append("- 音频输出状态：读取失败（").append(it.javaClass.simpleName).append("）\n")
            }
        }
        if (storageRelated) {
            runCatching {
                val stat = android.os.StatFs(context.filesDir.absolutePath)
                append("- 应用文件目录可用空间：")
                    .append(stat.availableBytes / (1024L * 1024L)).append(" MB\n")
                append("- 应用文件目录总空间：")
                    .append(stat.totalBytes / (1024L * 1024L)).append(" MB\n")
                append("- Native 堆已分配：")
                    .append(android.os.Debug.getNativeHeapAllocatedSize() / (1024L * 1024L)).append(" MB\n")
            }.onFailure {
                append("- 存储/Native 堆快照：读取失败（").append(it.javaClass.simpleName).append("）\n")
            }
        }
    }

    private fun parseLine(line: String): AppLogRecord? = runCatching {
        val json = JSONObject(line)
        val level = json.optString("level", "INFO")
        val tag = json.optString("tag", "App")
        val message = json.optString("message", "")
        val details = json.optString("details", "")
        val diagnosticContext = json.optJSONObject("diagnosticContext")?.let { contextJson ->
            linkedMapOf<String, String>().apply {
                val keys = contextJson.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, contextJson.optString(key, ""))
                }
            }
        }.orEmpty()
        AppLogRecord(
            timestampMs = json.optLong("timestampMs", 0L),
            level = level,
            tag = tag,
            message = message,
            details = details,
            thread = json.optString("thread", ""),
            issueKey = json.optString("issueKey", "").ifBlank {
                if (isIssueLevel(level)) stableIssueKey(tag, message) else ""
            },
            resolvesIssueKey = json.optString("resolvesIssueKey", ""),
            eventId = json.optString("eventId", ""),
            sessionId = json.optString("sessionId", ""),
            sequence = json.optLong("sequence", 0L),
            category = json.optString("category", "").ifBlank {
                AppLogTitleResolver.featureLabel(tag, message)
            },
            operationId = json.optString("operationId", "").ifBlank {
                extractOperationId(emptyMap(), details)
            },
            errorClass = json.optString("errorClass", "").ifBlank {
                extractExceptionClass(message, details)
            },
            httpStatusCode = json.optInt("httpStatusCode", extractHttpStatusCode(message, details)),
            processId = json.optInt("processId", 0),
            threadId = json.optLong("threadId", 0L),
            elapsedRealtimeMs = json.optLong("elapsedRealtimeMs", 0L),
            schemaVersion = json.optInt("schemaVersion", 1),
            rootCauseMessage = json.optString("rootCauseMessage", "").ifBlank {
                extractRootCauseMessage(details)
            },
            firstAppStackFrame = json.optString("firstAppStackFrame", "").ifBlank {
                extractFirstAppStackFrame(details)
            },
            diagnosticContext = diagnosticContext,
            exceptionSnapshot = json.optJSONObject("exceptionSnapshot")?.let(::parseExceptionSnapshot),
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
            issueKey = stableIssueKey("PreviousCrash", exception),
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


    /**
     * Snapshot the Throwable object before the caller unwinds it. A text stack trace is still
     * retained for compatibility, while this bounded tree lets AI inspect causes, suppressed
     * exceptions and each exception's own frames without reparsing "Caused by" text.
     */
    internal fun snapshotExceptionTree(failure: Throwable): AppLogExceptionSnapshot {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Throwable, Boolean>(),
        )
        var capturedNodes = 0
        fun visit(current: Throwable, depth: Int): AppLogExceptionSnapshot? {
            // Bound the whole exception graph, not only each individual cause chain. A Throwable
            // can carry many nested Suppressed exceptions; without a global budget, a small root
            // object could expand into an enormous diagnostic report.
            if (depth >= 12 || capturedNodes >= 24 || !seen.add(current)) return null
            capturedNodes++
            val frames = current.stackTrace.take(32).map { frame ->
                AppLogStackFrame(
                    className = frame.className.take(400),
                    methodName = frame.methodName.take(240),
                    fileName = frame.fileName?.take(240),
                    lineNumber = frame.lineNumber,
                )
            }
            // Preserve the primary cause chain before spending the remaining budget on cleanup
            // failures. Suppressed exceptions are still retained separately when budget allows.
            val causeCandidate = current.cause
            val cause = causeCandidate?.let { visit(it, depth + 1) }
            val suppressedCandidates = current.suppressed.take(4)
            val suppressed = suppressedCandidates.mapNotNull { visit(it, depth + 1) }
            val truncated = current.stackTrace.size > frames.size ||
                current.suppressed.size > suppressedCandidates.size ||
                (causeCandidate != null && cause == null) ||
                suppressed.size < suppressedCandidates.size
            return AppLogExceptionSnapshot(
                type = current.javaClass.name.take(400),
                message = current.message.orEmpty().take(1_200),
                stackFrames = frames,
                cause = cause,
                suppressed = suppressed,
                truncated = truncated,
            )
        }
        return requireNotNull(visit(failure, 0))
    }

    private fun exceptionSnapshotToJson(
        snapshot: AppLogExceptionSnapshot,
        depth: Int = 0,
    ): JSONObject {
        val result = JSONObject()
            .put("type", redact(snapshot.type).take(400))
            .put("message", redact(snapshot.message).take(1_200))
            .put("truncated", snapshot.truncated)
            .put(
                "stackFrames",
                org.json.JSONArray().apply {
                    snapshot.stackFrames.take(48).forEach { frame ->
                        put(
                            JSONObject()
                                .put("className", redact(frame.className).take(400))
                                .put("methodName", redact(frame.methodName).take(240))
                                .put("fileName", redact(frame.fileName.orEmpty()).take(240))
                                .put("lineNumber", frame.lineNumber),
                        )
                    }
                },
            )
        if (depth < 11) {
            snapshot.cause?.let { result.put("cause", exceptionSnapshotToJson(it, depth + 1)) }
            result.put(
                "suppressed",
                org.json.JSONArray().apply {
                    snapshot.suppressed.take(4).forEach { put(exceptionSnapshotToJson(it, depth + 1)) }
                },
            )
        }
        return result
    }

    internal fun exceptionSnapshotForExport(snapshot: AppLogExceptionSnapshot): JSONObject =
        exceptionSnapshotToJson(snapshot)

    private fun parseExceptionSnapshot(
        json: JSONObject,
        depth: Int = 0,
        nodeBudget: IntArray = intArrayOf(24),
    ): AppLogExceptionSnapshot? {
        if (depth >= 12 || nodeBudget[0] <= 0) return null
        nodeBudget[0]--
        val frames = json.optJSONArray("stackFrames")?.let { array ->
            (0 until minOf(array.length(), 48)).mapNotNull { index ->
                val frame = array.optJSONObject(index) ?: return@mapNotNull null
                AppLogStackFrame(
                    className = frame.optString("className", ""),
                    methodName = frame.optString("methodName", ""),
                    fileName = frame.optString("fileName", "").ifBlank { null },
                    lineNumber = frame.optInt("lineNumber", -1),
                )
            }
        }.orEmpty()
        val cause = json.optJSONObject("cause")?.let { parseExceptionSnapshot(it, depth + 1, nodeBudget) }
        val suppressed = json.optJSONArray("suppressed")?.let { array ->
            (0 until minOf(array.length(), 4)).mapNotNull { index ->
                array.optJSONObject(index)?.let { parseExceptionSnapshot(it, depth + 1, nodeBudget) }
            }
        }.orEmpty()
        return AppLogExceptionSnapshot(
            type = json.optString("type", ""),
            message = json.optString("message", ""),
            stackFrames = frames,
            cause = cause,
            suppressed = suppressed,
            truncated = json.optBoolean("truncated", false),
        )
    }

    private fun extractRootCauseMessage(details: String): String =
        Regex("""(?m)^#\d+\s+[A-Za-z0-9_.$]+(?::\s*(.*))?$""")
            .findAll(details)
            .lastOrNull()
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.take(1_200)
            .orEmpty()

    private fun extractFirstAppStackFrame(details: String): String =
        Regex("""(?m)^\s*at\s+(io\.translatetube\.local\.[A-Za-z0-9_.$]+\([^)]*\))""")
            .find(details)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.take(600)
            .orEmpty()

    private fun extractExceptionClass(message: String, details: String): String {
        val source = details + "\n" + message
        val rootFrame = Regex("""(?m)^\s*#\d+\s+([A-Za-z0-9_.$]+)""")
            .findAll(source).lastOrNull()?.groupValues?.getOrNull(1)
        val namedException = Regex(
            """(?<![A-Za-z0-9_.$])([A-Za-z0-9_.$]*(?:Exception|Error|Throwable))(?![A-Za-z0-9_.$])""",
        ).find(source)?.groupValues?.getOrNull(1)
        return (rootFrame ?: namedException).orEmpty().take(240)
    }

    internal fun applyOperationContext(
        diagnosticContext: Map<String, String>,
        operation: AppLogOperationContext,
    ): Map<String, String> = diagnosticContext + operation.diagnosticFields()

    internal fun extractOperationId(
        diagnosticContext: Map<String, String>,
        details: String,
    ): String {
        val explicitOperationId = diagnosticContext["操作关联 ID"]?.takeIf { it.isNotBlank() }
        if (explicitOperationId != null) return explicitOperationId.take(120)
        val keyPattern = Regex(
            """(?i)(diagnostic|operation|correlation|trace|request|task|job|flow).*id|翻译诊断\s*id|操作\s*id|请求\s*id|任务\s*id|关联\s*id""",
        )
        val contextValue = diagnosticContext.entries.firstOrNull {
            keyPattern.containsMatchIn(it.key)
        }?.value?.takeIf { it.isNotBlank() }
        val detailValue = Regex(
            """(?i)(?:翻译诊断\s*ID|诊断\s*ID|operation[_ ]?id|correlation[_ ]?id|trace[_ ]?id|request[_ ]?id|task[_ ]?id|job[_ ]?id|flow[_ ]?id|请求\s*ID|任务\s*ID|关联\s*ID)\s*[:=：]\s*([A-Za-z0-9_.:-]{6,120})""",
        ).find(details)?.groupValues?.getOrNull(1)
        return (contextValue ?: detailValue).orEmpty().take(120)
    }

    private fun extractHttpStatusCode(
        message: String,
        details: String,
        diagnosticContext: Map<String, String> = emptyMap(),
    ): Int {
        val contextStatus = diagnosticContext.entries.firstOrNull { (key, _) ->
            key.contains("状态码") ||
                (key.contains("http", ignoreCase = true) &&
                    (key.contains("status", ignoreCase = true) || key.contains("code", ignoreCase = true))) ||
                (key.contains("status", ignoreCase = true) && key.contains("code", ignoreCase = true))
        }?.value?.let { value ->
            Regex("""(?<!\d)([1-5]\d{2})(?!\d)""")
                .find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }?.takeIf { it in 100..599 }

        if (contextStatus != null) return contextStatus

        val source = message + "\n" + details
        val match = Regex("""(?i)\bHTTP(?:/\d(?:\.\d)?|\s*状态码)?\s*[:：=]?\s*(\d{3})\b""")
            .find(source)
            ?: Regex("""(?i)状态码\s*[:：=]\s*(\d{3})\b""").find(source)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 100..599 } ?: 0
    }

    /** Retain both the beginning and the end so the root cause/final frames are not lost. */
    private fun truncatePreservingEdges(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        val limit = maxChars.coerceAtLeast(256)
        val marker = "\n…[中间内容过长，已截断；保留开头和末尾，供 AI 查看异常根因及最终调用帧]…\n"
        val remaining = (limit - marker.length).coerceAtLeast(2)
        val head = (remaining * 0.68).toInt()
        val tail = remaining - head
        return value.take(head) + marker + value.takeLast(tail)
    }

    /** Redact common credentials, authorization headers, URL user-info and signed query parameters. */
    private fun redact(input: String): String {
        var value = input
        val bearer = Regex("""(?i)\bBearer\s+[A-Za-z0-9._~+/=-]+""")
        value = bearer.replace(value, "Bearer [REDACTED]")
        val basic = Regex("""(?i)\bBasic\s+[A-Za-z0-9+/=]{8,}""")
        value = basic.replace(value, "Basic [REDACTED]")
        val jsonSecrets = Regex("""(?i)("(?:api[_-]?key|authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|password|client[_-]?secret)"\s*:\s*")[^"]*(")""")
        value = jsonSecrets.replace(value) { match ->
            match.groupValues[1] + "[REDACTED]" + match.groupValues[2]
        }
        val urlUserInfo = Regex("""(?i)(https?://)[^/\s@]+@""")
        value = urlUserInfo.replace(value) { match -> match.groupValues[1] + "[REDACTED]@" }
        val credentials = Regex("""(?i)(api[_-]?key|authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|password|client[_-]?secret)(\s*[=:]\s*)[^\s,;]+""")
        value = credentials.replace(value) { match ->
            match.groupValues[1] + match.groupValues[2] + "[REDACTED]"
        }
        val querySecrets = Regex("""(?i)([?&](?:[A-Za-z0-9_.-]*(?:api[_-]?key|key|token|sig(?:nature)?|cookie|auth|password|secret|expires|credential|session|hdnts|policy|jwt))=)[^\s&]+""")
        value = querySecrets.replace(value) { match -> match.groupValues[1] + "[REDACTED]" }
        return value
    }
}
