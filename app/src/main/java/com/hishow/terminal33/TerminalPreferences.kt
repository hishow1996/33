package com.hishow.terminal33

import android.content.Context

class TerminalPreferences(context: Context) {
    private val p = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
    var fontSize: Float
        get() = p.getFloat("font_size", 13f)
        set(v) = p.edit().putFloat("font_size", v.coerceIn(10f, 22f)).apply()
    var autoScroll: Boolean
        get() = p.getBoolean("auto_scroll", true)
        set(v) = p.edit().putBoolean("auto_scroll", v).apply()
}
