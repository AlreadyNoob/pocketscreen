package com.hiderlab.vd

import android.content.Context

/** User preferences that are plain on/off switches. */
object AppSettings {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** When on, the keyboard only appears from the keyboard icon in the viewer. Off by default. */
    fun suppressKeyboard(c: Context): Boolean = p(c).getBoolean("suppress_kb", false)
    fun setSuppressKeyboard(c: Context, on: Boolean) { p(c).edit().putBoolean("suppress_kb", on).apply() }
}
