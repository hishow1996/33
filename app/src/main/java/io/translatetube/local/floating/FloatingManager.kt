package io.translatetube.local.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 全局悬浮窗：把任意 View（播放器画面）摘到 TYPE_APPLICATION_OVERLAY 层。
 * 支持拖动 + 右下角把手任意缩放。
 */
class FloatingManager(private val ctx: Context) {

    private companion object {
        const val MIN_WIDTH_PX = 280
        const val MIN_HEIGHT_PX = 200
    }

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(v: View, wPx: Int, hPx: Int): Boolean {
        if (view != null) return true
        val displayMetrics = ctx.resources.displayMetrics
        val maxWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val maxHeight = displayMetrics.heightPixels.coerceAtLeast(1)
        val windowWidth = FloatingWindowPolicy.boundedDimension(wPx, MIN_WIDTH_PX, maxWidth)
        val windowHeight = FloatingWindowPolicy.boundedDimension(hPx, MIN_HEIGHT_PX, maxHeight)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            windowWidth, windowHeight, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingWindowPolicy.boundedOffset(40, windowWidth, maxWidth); y = FloatingWindowPolicy.boundedOffset(200, windowHeight, maxHeight)
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) {
            return false
        }
        return try {
            manager.addView(v, p)
            wm = manager
            view = v
            params = p
            true
        } catch (_: Exception) {
            runCatching { manager.removeViewImmediate(v) }
            wm = null
            view = null
            params = null
            false
        }
    }

    // ---- 拖动 / 缩放：全部基于手指的屏幕绝对坐标（见 FloatingHostLayout） ----
    private var pointerRawX = 0f
    private var pointerRawY = 0f
    private var anchorRawX = 0f
    private var anchorRawY = 0f
    private var anchorX = 0
    private var anchorY = 0
    private var anchorWidth = 0
    private var anchorHeight = 0

    /** 由悬浮窗根容器在每个触摸事件上调用，记录手指当前的屏幕绝对位置。 */
    fun onRawTouch(event: MotionEvent) {
        pointerRawX = event.rawX
        pointerRawY = event.rawY
    }

    /** 手势确认是拖动/缩放时调用：以当前手指位置和窗口状态为锚点，窗口不会因此突然跳一下。 */
    fun beginPointerDrag() {
        val currentParams = params ?: return
        anchorRawX = pointerRawX
        anchorRawY = pointerRawY
        anchorX = currentParams.x
        anchorY = currentParams.y
        anchorWidth = currentParams.width
        anchorHeight = currentParams.height
    }

    /** 窗口跟随手指移动：位置 = 锚点位置 + 手指在屏幕上的位移。 */
    fun moveToPointer() {
        val manager = wm ?: return
        val currentView = view ?: return
        val currentParams = params ?: return
        val metrics = ctx.resources.displayMetrics
        val maxWidth = metrics.widthPixels.coerceAtLeast(1)
        val maxHeight = metrics.heightPixels.coerceAtLeast(1)
        val newX = FloatingWindowPolicy.draggedOffset(
            anchorX, anchorRawX, pointerRawX, currentParams.width, maxWidth,
        )
        val newY = FloatingWindowPolicy.draggedOffset(
            anchorY, anchorRawY, pointerRawY, currentParams.height, maxHeight,
        )
        if (newX == currentParams.x && newY == currentParams.y) return
        currentParams.x = newX
        currentParams.y = newY
        try { manager.updateViewLayout(currentView, currentParams) } catch (_: Exception) {}
    }

    /** 从右下角把手缩放：尺寸 = 锚点尺寸 + 手指在屏幕上的位移，左上角位置保持不动。 */
    fun resizeToPointer() {
        val manager = wm ?: return
        val currentView = view ?: return
        val currentParams = params ?: return
        val metrics = ctx.resources.displayMetrics
        val maxWidth = metrics.widthPixels.coerceAtLeast(1)
        val maxHeight = metrics.heightPixels.coerceAtLeast(1)
        val newWidth = FloatingWindowPolicy.resizedDimension(
            anchorWidth, anchorRawX, pointerRawX, MIN_WIDTH_PX, maxWidth,
        )
        val newHeight = FloatingWindowPolicy.resizedDimension(
            anchorHeight, anchorRawY, pointerRawY, MIN_HEIGHT_PX, maxHeight,
        )
        val newX = FloatingWindowPolicy.boundedOffset(currentParams.x, newWidth, maxWidth)
        val newY = FloatingWindowPolicy.boundedOffset(currentParams.y, newHeight, maxHeight)
        if (newWidth == currentParams.width && newHeight == currentParams.height &&
            newX == currentParams.x && newY == currentParams.y
        ) return
        currentParams.width = newWidth
        currentParams.height = newHeight
        currentParams.x = newX
        currentParams.y = newY
        try { manager.updateViewLayout(currentView, currentParams) } catch (_: Exception) {}
    }

    fun hide() {
        try {
            wm?.removeViewImmediate(view)
        } catch (_: Exception) {}
        view = null; params = null; wm = null
    }

    fun isShowing(): Boolean = view != null
}
