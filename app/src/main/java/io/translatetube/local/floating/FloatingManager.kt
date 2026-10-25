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
    fun show(v: View, wPx: Int, hPx: Int, onWindowTap: () -> Unit = {}): Boolean {
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
        var resizing = false; var movedWindow = false; var startW = 0; var startH = 0
        val touchSlop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        // Invisible resize affordance in the bottom-right corner; keep it small so it
        // does not overlap the normal seekbar or make the whole corner resize by accident.
        val resizeTouchSize = (24f * displayMetrics.density).roundToInt().coerceAtLeast(24)
        v.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    origX = p.x; origY = p.y
                    startW = p.width; startH = p.height
                    resizing = ev.x >= v.width - resizeTouchSize &&
                        ev.y >= v.height - resizeTouchSize
                    dragging = !resizing
                    movedWindow = false
                    // Child controls own their clicks. Unhandled touches on the video surface
                    // remain available for dragging or toggling the control layer.
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (resizing) {
                        if (kotlin.math.abs(dx) >= touchSlop || kotlin.math.abs(dy) >= touchSlop) {
                            movedWindow = true
                            val nw = FloatingWindowPolicy.boundedDimension(
                                (startW + dx).roundToInt(), 280, maxWidth,
                            )
                            val nh = FloatingWindowPolicy.boundedDimension(
                                (startH + dy).roundToInt(), 200, maxHeight,
                            )
                            p.width = nw; p.height = nh
                            p.x = FloatingWindowPolicy.boundedOffset(p.x, p.width, maxWidth)
                            p.y = FloatingWindowPolicy.boundedOffset(p.y, p.height, maxHeight)
                            try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
                        }
                    } else if (dragging && (movedWindow ||
                            kotlin.math.abs(dx) >= touchSlop ||
                            kotlin.math.abs(dy) >= touchSlop)
                    ) {
                        movedWindow = true
                        p.x = FloatingWindowPolicy.boundedOffset(
                            origX + dx.roundToInt(), p.width, maxWidth,
                        )
                        p.y = FloatingWindowPolicy.boundedOffset(
                            origY + dy.roundToInt(), p.height, maxHeight,
                        )
                        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!movedWindow && !resizing) onWindowTap()
                    dragging = false
                    resizing = false
                    movedWindow = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    resizing = false
                    movedWindow = false
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
