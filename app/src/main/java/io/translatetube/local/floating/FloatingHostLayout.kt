package io.translatetube.local.floating

import android.content.Context
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 悬浮窗的根容器：只负责把每个触摸事件的"屏幕绝对坐标"报给 [FloatingManager]，不拦截、不消费。
 *
 * 拖动/缩放必须用屏幕绝对坐标：Compose 手势拿到的坐标是相对悬浮窗自身的，窗口一动坐标系也跟着变，
 * 而系统把窗口新位置落实到触摸坐标上有延迟，拿它来算位移就会叠加、来回跳。
 */
class FloatingHostLayout(context: Context) : FrameLayout(context) {
    var onRawTouch: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        onRawTouch?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
