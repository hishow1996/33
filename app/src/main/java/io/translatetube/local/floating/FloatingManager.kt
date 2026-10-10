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
    fun show(v: View, wPx: Int, hPx: Int) {
        if (view != null) return
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val p = WindowManager.LayoutParams(
            wPx, hPx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 200
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
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (resizing) {
                        val nw = (startW + (ev.rawX - downX)).coerceAtLeast(280f).roundToInt()
                        val nh = (startH + (ev.rawY - downY)).coerceAtLeast(200f).roundToInt()
                        p.width = nw; p.height = nh
                    } else if (dragging) {
                        p.x = origX + (ev.rawX - downX).roundToInt()
                        p.y = origY + (ev.rawY - downY).roundToInt()
                    }
                    try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        try {
            wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            wm?.addView(v, p)
            view = v; params = p
        } catch (_: Exception) {}
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
