package io.translatetube.local.floating

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 悬浮窗里 ComposeView 专用的生命周期。
 *
 * 悬浮窗是 WindowManager 上独立的一层，不属于 Activity 的界面树。如果让它借用 Activity 的生命周期，
 * 应用一切到桌面（Activity 进入 STOPPED），Compose 就会暂停这棵界面树的刷新：
 * 功能按钮 3 秒后不会自动隐藏，点击悬浮窗也唤不出功能按钮，进度、播放状态也都不更新。
 * 悬浮窗显示期间它自己的生命周期始终保持 RESUMED，直到悬浮窗被移除。
 *
 * 必须在主线程创建和调用。
 */
class FloatingOverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    /** 悬浮窗加到屏幕上之前调用：生命周期推进到 RESUMED。 */
    fun resume() {
        if (registry.currentState != Lifecycle.State.DESTROYED) {
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    /** 悬浮窗已移除后调用，结束这一层的生命周期。 */
    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
