package io.translatetube.local.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
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

    @SuppressLint("ClickableViewAccessibility")
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
        // 整体拖动
        var downX = 0f; var downY = 0f; var origX = 0; var origY = 0; var dragging = false
        var resizing = false; var startW = 0; var startH = 0
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    origX = p.x; origY = p.y
                    startW = p.width; startH = p.height
                    // 触点在右下 1/4 区域视为缩放
                    resizing = ev.x > v.width * 0.75f && ev.y > v.height * 0.75f
                    dragging = !resizing
                    // This overlay contains only the video surface. Consume DOWN so Android
                    // continues delivering MOVE/UP and drag/resize can actually run.
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizing) {
                        val nw = FloatingWindowPolicy.boundedDimension(
                            (startW + (ev.rawX - downX)).roundToInt(), 280, maxWidth,
                        )
                        val nh = FloatingWindowPolicy.boundedDimension(
                            (startH + (ev.rawY - downY)).roundToInt(), 200, maxHeight,
                        )
                        p.width = nw; p.height = nh
                        p.x = FloatingWindowPolicy.boundedOffset(p.x, p.width, maxWidth)
                        p.y = FloatingWindowPolicy.boundedOffset(p.y, p.height, maxHeight)
                    } else if (dragging) {
                        p.x = FloatingWindowPolicy.boundedOffset(
                            origX + (ev.rawX - downX).roundToInt(), p.width, maxWidth,
                        )
                        p.y = FloatingWindowPolicy.boundedOffset(
                            origY + (ev.rawY - downY).roundToInt(), p.height, maxHeight,
                        )
                    }
                    try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    resizing = false
                    true
                }
                else -> false
            }
        }
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (manager == null) {
            v.setOnTouchListener(null)
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
            v.setOnTouchListener(null)
            wm = null
            view = null
            params = null
            false
        }
    }

    fun hide() {
        try {
            view?.setOnTouchListener(null)
            wm?.removeViewImmediate(view)
        } catch (_: Exception) {}
        view = null; params = null; wm = null
    }

    fun isShowing(): Boolean = view != null
}
