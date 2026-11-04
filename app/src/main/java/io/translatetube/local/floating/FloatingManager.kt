package io.translatetube.local.floating

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
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
    private var videoAspectRatio: Float = 16f / 9f

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(v: View, wPx: Int, hPx: Int, aspectRatio: Float = wPx.toFloat() / hPx.coerceAtLeast(1)): Boolean {
        if (view != null) return true
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return false
        val (maxWidth, maxHeight) = screenSizePx(manager)
        videoAspectRatio = FloatingWindowPolicy.normalizeAspectRatio(aspectRatio, wPx, hPx)
        val (windowWidth, windowHeight) = FloatingWindowPolicy.aspectLockedSize(
            requestedWidth = wPx,
            requestedHeight = hPx,
            aspectRatio = videoAspectRatio,
            minWidth = MIN_WIDTH_PX,
            minHeight = MIN_HEIGHT_PX,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        // 不使用 FLAG_LAYOUT_NO_LIMITS：该标志允许悬浮窗越过屏幕边界，
        // 手动限位和系统布局限位需要同时生效，避免拖动后窗口被移出可见屏幕。
        val p = WindowManager.LayoutParams(
            windowWidth, windowHeight, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = FloatingWindowPolicy.boundedOffset(40, windowWidth, maxWidth)
            y = FloatingWindowPolicy.boundedOffset(200, windowHeight, maxHeight)
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

    /**
     * 返回当前显示屏的真实像素尺寸，作为悬浮窗缩放与拖动共用的边界。
     * displayMetrics 可能受应用窗口配置影响，不能单独作为全屏悬浮窗的物理边界。
     */
    @Suppress("DEPRECATION")
    private fun screenSizePx(manager: WindowManager): Pair<Int, Int> {
        val fallback = ctx.resources.displayMetrics
        val realSize = Point()
        runCatching { manager.defaultDisplay.getRealSize(realSize) }
        val width = realSize.x.takeIf { it > 0 } ?: fallback.widthPixels
        val height = realSize.y.takeIf { it > 0 } ?: fallback.heightPixels
        return width.coerceAtLeast(1) to height.coerceAtLeast(1)
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
        val (maxWidth, maxHeight) = screenSizePx(manager)
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
        val (maxWidth, maxHeight) = screenSizePx(manager)
        val (newWidth, newHeight) = FloatingWindowPolicy.aspectLockedResizedSize(
            anchorWidth = anchorWidth,
            anchorHeight = anchorHeight,
            deltaX = pointerRawX - anchorRawX,
            deltaY = pointerRawY - anchorRawY,
            aspectRatio = videoAspectRatio,
            minWidth = MIN_WIDTH_PX,
            minHeight = MIN_HEIGHT_PX,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
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
