package com.hishow.terminal33

import android.content.Context

/** Small persistent store for terminal preferences. */
class TerminalSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("terminal_session", Context.MODE_PRIVATE)
    var fontSize: Float
        get() = prefs.getFloat("font_size", 13f)
        set(value) = prefs.edit().putFloat("font_size", value.coerceIn(10f, 22f)).apply()
    var scrollFollow: Boolean
        get() = prefs.getBoolean("scroll_follow", true)
        set(value) = prefs.edit().putBoolean("scroll_follow", value).apply()
}
