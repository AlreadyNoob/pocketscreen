package com.hiderlab.vd

import android.content.Context

/** User preferences that are plain on/off switches. */
object AppSettings {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** When on, the keyboard only appears from the keyboard icon in the viewer. Off by default. */
    fun suppressKeyboard(c: Context): Boolean = p(c).getBoolean("suppress_kb", false)
    fun setSuppressKeyboard(c: Context, on: Boolean) { p(c).edit().putBoolean("suppress_kb", on).apply() }

    /** When on (default), the back gesture in the viewer is sent to the app. Off: it is swallowed. */
    fun backGesture(c: Context): Boolean = p(c).getBoolean("back_gesture", true)
    fun setBackGesture(c: Context, on: Boolean) { p(c).edit().putBoolean("back_gesture", on).apply() }

    /** Stop everything when the battery is at or below this percent and not charging. 0 = off. */
    fun autoStopBattery(c: Context): Int = p(c).getInt("auto_battery", 0)
    fun setAutoStopBattery(c: Context, v: Int) { p(c).edit().putInt("auto_battery", v).apply() }

    /** Stop an app after it has run this many minutes on a hidden display. 0 = off. */
    fun autoStopMinutes(c: Context): Int = p(c).getInt("auto_minutes", 0)
    fun setAutoStopMinutes(c: Context, v: Int) { p(c).edit().putInt("auto_minutes", v).apply() }

    /** Show memory and CPU on each running app. On by default; turning it off makes refreshing faster. */
    fun showStats(c: Context): Boolean = p(c).getBoolean("show_stats", true)
    fun setShowStats(c: Context, on: Boolean) { p(c).edit().putBoolean("show_stats", on).apply() }
}
