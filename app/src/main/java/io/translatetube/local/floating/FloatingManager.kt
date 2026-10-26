package io.translatetube.local.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt

/**
 * 全局悬浮窗：把任意 View（播放器画面）摘到 TYPE_APPLICATION_OVERLAY 层。
 * 支持拖动 + 右下角把手任意缩放。
 */
class FloatingManager(private val ctx: Context) {

    private var wm: WindowManager? = null
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(ctx)

    fun show(v: View, wPx: Int, hPx: Int): Boolean {
        if (view != null) return true
        val displayMetrics = ctx.resources.displayMetrics
        val maxWidth = displayMetrics.widthPixels.coerceAtLeast(1)
        val maxHeight = displayMetrics.heightPixels.coerceAtLeast(1)
        val windowWidth = FloatingWindowPolicy.boundedDimension(wPx, 280, maxWidth)
        val windowHeight = FloatingWindowPolicy.boundedDimension(hPx, 200, maxHeight)
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

    /** Move the global overlay by a pointer delta reported by its Compose control layer. */
    fun moveBy(dx: Float, dy: Float) {
        val manager = wm ?: return
        val currentView = view ?: return
        val currentParams = params ?: return
        val metrics = ctx.resources.displayMetrics
        val maxWidth = metrics.widthPixels.coerceAtLeast(1)
        val maxHeight = metrics.heightPixels.coerceAtLeast(1)
        currentParams.x = FloatingWindowPolicy.boundedOffset(
            currentParams.x + dx.roundToInt(), currentParams.width, maxWidth,
        )
        currentParams.y = FloatingWindowPolicy.boundedOffset(
            currentParams.y + dy.roundToInt(), currentParams.height, maxHeight,
        )
        try { manager.updateViewLayout(currentView, currentParams) } catch (_: Exception) {}
    }

    /** Resize from the lower-right handle, keeping the overlay inside the display. */
    fun resizeBy(dx: Float, dy: Float) {
        val manager = wm ?: return
        val currentView = view ?: return
        val currentParams = params ?: return
        val metrics = ctx.resources.displayMetrics
        val maxWidth = metrics.widthPixels.coerceAtLeast(1)
        val maxHeight = metrics.heightPixels.coerceAtLeast(1)
        currentParams.width = FloatingWindowPolicy.boundedDimension(
            currentParams.width + dx.roundToInt(), 280, maxWidth,
        )
        currentParams.height = FloatingWindowPolicy.boundedDimension(
            currentParams.height + dy.roundToInt(), 200, maxHeight,
        )
        currentParams.x = FloatingWindowPolicy.boundedOffset(
            currentParams.x, currentParams.width, maxWidth,
        )
        currentParams.y = FloatingWindowPolicy.boundedOffset(
            currentParams.y, currentParams.height, maxHeight,
        )
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
