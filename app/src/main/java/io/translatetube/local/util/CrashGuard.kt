package io.translatetube.local.util

import android.app.Application
import android.os.Looper
import android.os.Process
import android.util.Log
import android.widget.Toast
import io.translatetube.local.player.Phase
import io.translatetube.local.player.PlaybackService
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Records uncaught exceptions and keeps selected background worker failures from killing the app.
 * This is a last-resort guard, not a substitute for fixing the underlying failure.
 */
internal object CrashGuard {
    private const val TAG = "CrashGuard"
    private const val MAX_RECORD_CHARS = 24_000
    private const val WINDOW_MS = 60_000L
    private const val MAX_SURVIVED_IN_WINDOW = 8

    private val installed = AtomicBoolean(false)
    private val recentBackgroundFailures = ArrayDeque<Long>()
    private val failureWindowLock = Any()
    private val fileLock = Any()

    fun install(application: Application) {
        if (!installed.compareAndSet(false, true)) return

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            val now = System.currentTimeMillis()
            val swallow = shouldSurvive(thread, failure, now)
            writeException(application, if (swallow) "SURVIVED" else "FATAL", thread, failure, now)

            if (swallow) {
                Log.e(TAG, "Surviving background exception from thread '${thread.name}'", failure)
                notifyDubbingFailure(failure)
            } else {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, failure)
                } else {
                    // Android normally installs a default handler. Preserve fatal semantics if
                    // there was none to delegate to.
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }

        AppLogStore.beginProcessSession(application)
        AppLogStore.info(application, "Application", "全局异常捕获器已安装")
        showPreviousFatalIfPresent(application)
    }

    private fun shouldSurvive(thread: Thread, failure: Throwable, now: Long): Boolean {
        if (thread === Looper.getMainLooper().thread) return false
        if (failure is VirtualMachineError || failure is ThreadDeath) return false
        val name = thread.name
        if (!name.startsWith("DefaultDispatcher-worker") && !name.startsWith("tt-")) return false

        synchronized(failureWindowLock) {
            while (recentBackgroundFailures.isNotEmpty() &&
                now - recentBackgroundFailures.peekFirst() > WINDOW_MS
            ) {
                recentBackgroundFailures.removeFirst()
            }
            recentBackgroundFailures.addLast(now)
            // The ninth background failure in a rolling 60-second window is fatal and delegated.
            return recentBackgroundFailures.size <= MAX_SURVIVED_IN_WINDOW
        }
    }

    private fun writeException(
        application: Application,
        status: String,
        thread: Thread,
        failure: Throwable,
        timestampMs: Long,
    ) {
        val stack = StringWriter().also { writer ->
            PrintWriter(writer).use { failure.printStackTrace(it) }
        }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
            .format(Date(timestampMs))
        val message = failure.message?.replace(Regex("\\s+"), " ")?.take(300).orEmpty()
        val diagnosticContext = collectCrashContext(application, status, thread, failure)
        val record = buildString {
            append(status).append('\n')
            append("time=").append(timestamp).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("exception=").append(failure.javaClass.name)
            if (message.isNotBlank()) append(": ").append(message)
            append('\n')
            if (diagnosticContext.isNotEmpty()) {
                append("runtime-diagnostic-context:\n")
                diagnosticContext.toSortedMap().forEach { (key, value) ->
                    append("- ").append(key).append('=').append(value).append('\n')
                }
            }
            append("stack:\n")
            append(stack)
        }.take(MAX_RECORD_CHARS)

        synchronized(fileLock) {
            try {
                val directory = File(application.filesDir, "crash")
                if (!directory.exists() && !directory.mkdirs()) {
                    Log.w(TAG, "Unable to create crash log directory: ${directory.absolutePath}")
                } else {
                    File(directory, "last_exception.txt").writeText(record, Charsets.UTF_8)
                }
            } catch (writeFailure: Throwable) {
                Log.w(TAG, "Unable to write last exception record", writeFailure)
            }
        }
        // Keep the existing last-crash file and also append this failure to the unified
        // local log so earlier context and the full stack can be inspected in-app.
        runCatching {
            AppLogStore.recordUncaught(
                application,
                status,
                thread.name,
                failure,
                timestampMs,
                diagnosticContext,
            )
        }.onFailure { loggingFailure ->
            Log.w(TAG, "Unable to append uncaught exception to application log", loggingFailure)
        }
    }

    private fun collectCrashContext(
        application: Application,
        status: String,
        thread: Thread,
        failure: Throwable,
    ): Map<String, String> {
        val context = linkedMapOf(
            "崩溃记录状态" to status,
            "崩溃线程名称" to thread.name,
            "崩溃线程是否为主线程" to (thread === Looper.getMainLooper().thread).toString(),
            "异常对象类型" to failure.javaClass.name,
            "应用自己的调用栈中是否包含 io.translatetube.local 帧" to
                failure.stackTrace.any { it.className.startsWith("io.translatetube.local.") }.toString(),
            "首个应用代码栈帧" to (failure.stackTrace.firstOrNull {
                it.className.startsWith("io.translatetube.local.")
            }?.let {
                it.className + "." + it.methodName + "(" + (it.fileName ?: "未知文件") + ":" + it.lineNumber + ")"
            } ?: "Throwable 栈中未提供应用自身帧；请结合触发事件审计定位系统回调入口"),
        )
        // This is best-effort evidence only. Never let a diagnostic snapshot mask the original crash.
        runCatching { io.translatetube.local.player.PlaybackService.diagnosticSnapshot() }
            .onSuccess { context.putAll(it) }
            .onFailure {
                context["播放服务现场快照"] = "读取失败：${it.javaClass.name}"
            }

        val message = failure.message.orEmpty()
        val messageMentionsNotificationInflation =
            message.contains("Bad notification", ignoreCase = true) ||
                message.contains("Couldn't inflate contentViews", ignoreCase = true)
        if (failure.javaClass.name.contains("RemoteServiceException") || messageMentionsNotificationInflation) {
            val chain = buildList {
                val seen = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<Throwable, Boolean>(),
                )
                var current: Throwable? = failure
                var depth = 0
                while (current != null && seen.add(current) && depth < 12) {
                    add(current)
                    current.suppressed.take(4).forEach { add(it) }
                    current = current.cause
                    depth++
                }
            }
            val actualCauseHasConcurrentModification = chain.any {
                it.javaClass.name == "java.util.ConcurrentModificationException"
            }
            val messageHasConcurrentModification =
                message.contains("ConcurrentModificationException", ignoreCase = true)
            context["通知异常判定"] = if (messageMentionsNotificationInflation) {
                "系统在处理通知 contentViews 时报告失败"
            } else {
                "异常类型是 RemoteServiceException；请结合通知 ID 与审计轨迹确认"
            }
            Regex("""\btag=([^,)]+)""").find(message)?.groupValues?.getOrNull(1)?.let {
                context["系统异常消息中的通知 tag"] = it.trim()
            }
            Regex("""\bid=(-?\d+)""").find(message)?.groupValues?.getOrNull(1)?.let {
                context["系统异常消息中的通知 ID"] = it
            }
            context["异常对象链是否真实包含 ConcurrentModificationException"] =
                actualCauseHasConcurrentModification.toString()
            context["异常消息文本是否提及 ConcurrentModificationException"] =
                messageHasConcurrentModification.toString()
            context["通知异常证据边界"] =
                if (messageHasConcurrentModification && !actualCauseHasConcurrentModification) {
                    "ConcurrentModificationException 只出现在系统异常消息文本中，Throwable 对象没有提供该异常自己的栈；无法仅凭此条异常确定被并发修改的集合或修改方。请对照 NotificationDiagnostics 的 ID/样式/动作审计。"
                } else {
                    "请对照通知提交轨迹、通知 ID/样式/动作数量和完整 Throwable 栈进一步确认。"
                }
        }
        return context
    }

    private fun showPreviousFatalIfPresent(application: Application) {
        synchronized(fileLock) {
            val file = File(File(application.filesDir, "crash"), "last_exception.txt")
            val content = try {
                if (file.isFile) file.readText(Charsets.UTF_8) else return
            } catch (failure: Throwable) {
                Log.w(TAG, "Unable to read previous exception record", failure)
                return
            }
            if (content.lineSequence().firstOrNull() != "FATAL") return

            val summary = content.lineSequence()
                .firstOrNull { it.startsWith("exception=") }
                ?.substringAfter("exception=")
                ?.replace(Regex("\\s+"), " ")
                ?.take(100)
                ?.takeIf { it.isNotBlank() }
                ?: "原因未知"
            try {
                file.writeText("SEEN\n" + content.substringAfter('\n', ""), Charsets.UTF_8)
            } catch (failure: Throwable) {
                Log.w(TAG, "Unable to mark previous exception as seen", failure)
            }
            Toast.makeText(application, "上次异常退出：$summary", Toast.LENGTH_LONG).show()
        }
    }

    private fun notifyDubbingFailure(failure: Throwable) {
        try {
            PlaybackService.ui.update { state ->
                if (state.phase == Phase.SYNTHESIZING || state.phase == Phase.READY) {
                    state.copy(
                        dubbingHasErrors = true,
                        dubbingStatus = "后台配音任务异常，配音可能不完整，请重试",
                        noModelHint = "后台异常（${failure.javaClass.simpleName}），诊断记录已保存。",
                    )
                } else {
                    state
                }
            }
        } catch (notificationFailure: Throwable) {
            Log.w(TAG, "Unable to update dubbing error status", notificationFailure)
        }
    }
}
