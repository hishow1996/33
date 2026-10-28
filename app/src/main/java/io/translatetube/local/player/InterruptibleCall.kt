package io.translatetube.local.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.selects.select

internal sealed class InterruptibleResult<out T> {
    data class Done<T>(val value: T) : InterruptibleResult<T>()
    object Interrupted : InterruptibleResult<Nothing>()
}

/**
 * 运行 [block]，但 [interrupt] 一完成就不再等它，立即返回 [InterruptibleResult.Interrupted]。
 *
 * 被放弃的调用会被取消，但不等它真正结束：网络请求会随取消断开；本地模型的原生推理
 * 不能中途取消，让它在后台自己跑完，调用方不会被它拖住。
 * 用途：翻译某一批字幕的请求可能要几十秒，用户这时点"启用外部翻译"，不应该等这批翻完才能操作。
 */
internal suspend fun <T> runUntilInterrupted(
    interrupt: CompletableDeferred<Unit>,
    block: suspend () -> T,
): InterruptibleResult<T> {
    if (interrupt.isCompleted) return InterruptibleResult.Interrupted
    // 保留调度器等上下文，但不挂在调用方的 Job 下：被放弃时不能让调用方的作用域等它结束。
    val context = currentCoroutineContext().minusKey(Job)
    val work = CoroutineScope(SupervisorJob()).async(context) { block() }
    try {
        return select<InterruptibleResult<T>> {
            work.onAwait { InterruptibleResult.Done(it) }
            interrupt.onAwait { InterruptibleResult.Interrupted }
        }
    } finally {
        if (!work.isCompleted) work.cancel()
    }
}
