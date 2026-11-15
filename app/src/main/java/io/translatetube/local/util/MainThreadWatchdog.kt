package io.translatetube.local.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * 主线程卡顿监视器（诊断用）。
 *
 * 守护线程每秒向主线程投递一个空任务；若主线程超过 [STALL_THRESHOLD_MS] 没执行它，
 * 说明主线程被卡住（系统弹「无响应」之前的征兆）。此时先把主线程和相关线程的调用栈采集下来，
 * 再写进应用日志，这样不需要 adb 也能知道主线程卡在哪一行、在等谁。
 *
 * 注意：必须先采栈、后写日志。日志写入要拿全局锁，若主线程正好卡在这把锁上，
 * 写日志本身会等锁，但此时栈已经采到手，不会丢。
 */
object MainThreadWatchdog {
    private const val TICK_MS = 1_000L
    private const val STALL_THRESHOLD_MS = 4_000L
    private const val MAX_FRAMES = 30
    private const val MAX_OTHER_THREADS = 6

    @Volatile private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        val mainLooper = Looper.getMainLooper()
        val mainThread = mainLooper.thread
        val handler = Handler(mainLooper)
        Thread({
            var lastReportedStallStart = 0L
            while (true) {
                val answeredAt = java.util.concurrent.atomic.AtomicLong(0L)
                val sentAt = SystemClock.elapsedRealtime()
                val probe = Runnable { answeredAt.set(SystemClock.elapsedRealtime()) }
                handler.post(probe)
                var reported = false
                while (answeredAt.get() == 0L) {
                    try {
                        Thread.sleep(250L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    val waited = SystemClock.elapsedRealtime() - sentAt
                    if (!reported && waited >= STALL_THRESHOLD_MS && sentAt != lastReportedStallStart) {
                        reported = true
                        lastReportedStallStart = sentAt
                        // 先采栈再写日志（见类注释）。
                        val mainStack = formatStack(mainThread.stackTrace)
                        val mainState = mainThread.state.name
                        val others = collectOtherThreads(mainThread)
                        runCatching {
                            AppLogStore.warning(
                                appContext,
                                "MainThreadWatchdog",
                                "主线程已卡住 ${waited / 1000} 秒未响应",
                                diagnosticContext = mapOf(
                                    "主线程状态" to mainState,
                                    "已卡住毫秒" to waited.toString(),
                                    "主线程调用栈" to mainStack,
                                    "其他相关线程" to others,
                                ),
                            )
                        }
                    }
                }
                if (reported) {
                    val total = SystemClock.elapsedRealtime() - sentAt
                    runCatching {
                        AppLogStore.info(
                            appContext,
                            "MainThreadWatchdog",
                            "主线程已恢复响应",
                            diagnosticContext = mapOf("本次卡住总毫秒" to total.toString()),
                        )
                    }
                }
                try {
                    Thread.sleep(TICK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }, "MainThreadWatchdog").apply { isDaemon = true }.start()
    }

    private fun formatStack(frames: Array<StackTraceElement>): String =
        frames.take(MAX_FRAMES).joinToString("\n") { "  at $it" }

    /** 只挑与本应用有关、且处于 BLOCKED/WAITING 或正在运行的线程，帮助判断主线程在等谁。 */
    private fun collectOtherThreads(mainThread: Thread): String = runCatching {
        Thread.getAllStackTraces().entries
            .asSequence()
            .filter { (thread, frames) ->
                thread !== mainThread && thread.name != "MainThreadWatchdog" &&
                    frames.any { it.className.startsWith("io.translatetube.local") }
            }
            .take(MAX_OTHER_THREADS)
            .joinToString("\n\n") { (thread, frames) ->
                "[${thread.name} / ${thread.state.name}]\n" + formatStack(frames)
            }
            .ifBlank { "无" }
    }.getOrDefault("采集失败")
}
