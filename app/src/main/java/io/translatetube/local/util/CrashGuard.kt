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
        val record = buildString {
            append(status).append('\n')
            append("time=").append(timestamp).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("exception=").append(failure.javaClass.name)
            if (message.isNotBlank()) append(": ").append(message)
            append('\n').append("stack:\n")
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
            AppLogStore.recordUncaught(application, status, thread.name, failure, timestampMs)
        }.onFailure { loggingFailure ->
            Log.w(TAG, "Unable to append uncaught exception to application log", loggingFailure)
        }
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
