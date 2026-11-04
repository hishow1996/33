package io.translatetube.local.tts

/**
 * Per-utterance callback state. All methods are called while SystemTts.lifecycleLock is held.
 * A terminal callback arriving during onStart is deferred until onStart returns, preserving
 * callback order without running application callbacks under the lifecycle lock.
 */
internal class TimelineTtsCallbackState {
    data class Decision(
        val accepted: Boolean,
        val completionReady: Boolean,
        val error: String?,
    )

    var started: Boolean = false
        private set
    private var startCallbackRunning: Boolean = false
    private var terminal: Boolean = false
    private var completionRequested: Boolean = false
    private var completionError: String? = null

    fun beginStart(): Boolean {
        if (terminal || started) return false
        started = true
        startCallbackRunning = true
        return true
    }

    fun requestFinish(error: String?): Decision {
        if (terminal) return Decision(accepted = false, completionReady = false, error = null)
        terminal = true
        completionRequested = true
        completionError = error?.takeIf { it.isNotBlank() }
            ?: if (!started) {
                "系统 TTS 完成回调先于开始回调，无法确认朗读链路是否启动"
            } else {
                null
            }
        return Decision(
            accepted = true,
            completionReady = !startCallbackRunning,
            error = completionError,
        )
    }

    fun finishStart(callbackFailure: Throwable?): Decision {
        startCallbackRunning = false
        if (callbackFailure != null) {
            terminal = true
            completionRequested = true
            completionError = "系统 TTS 开始回调执行异常：${callbackFailure.message ?: callbackFailure.javaClass.simpleName}"
        }
        return Decision(
            accepted = completionRequested,
            completionReady = completionRequested,
            error = completionError,
        )
    }

    /** Deliberate seek/pause/replacement cancellation must not be reported as a speech failure. */
    fun cancel() {
        terminal = true
        completionRequested = false
        completionError = null
    }
}
