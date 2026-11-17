package com.hiderlab.vd

import android.content.Context

/**
 * User preferences that are plain on/off switches. The per-app ones are stored under [AppRef.key], so a
 * clone has its own settings and the original keeps the keys it always had.
 */
object AppSettings {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** When on, the keyboard only appears from the keyboard icon in the viewer. Off by default. */
    fun suppressKeyboard(c: Context): Boolean = p(c).getBoolean("suppress_kb", false)
    fun setSuppressKeyboard(c: Context, on: Boolean) { p(c).edit().putBoolean("suppress_kb", on).apply() }

    /** When on (default), the back gesture in the viewer is sent to the app. Off: it is swallowed. */
    fun backGesture(c: Context): Boolean = p(c).getBoolean("back_gesture", true)
    fun setBackGesture(c: Context, on: Boolean) { p(c).edit().putBoolean("back_gesture", on).apply() }

    /** Per-app overrides for the two viewer options above. -1 = use the Settings value, 0 = off, 1 = on. */
    fun appBackGesture(c: Context, app: AppRef): Int = p(c).getInt("back_${app.key}", -1)
    fun setAppBackGesture(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("back_${app.key}", v).apply() }
    fun appSuppressKeyboard(c: Context, app: AppRef): Int = p(c).getInt("kb_${app.key}", -1)
    fun setAppSuppressKeyboard(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("kb_${app.key}", v).apply() }
    fun effectiveBackGesture(c: Context, app: AppRef): Boolean =
        appBackGesture(c, app).let { if (it < 0) backGesture(c) else it == 1 }
    fun effectiveSuppressKeyboard(c: Context, app: AppRef): Boolean =
        appSuppressKeyboard(c, app).let { if (it < 0) suppressKeyboard(c) else it == 1 }

    /** Launch apps with "exclude from Recents" so they don't show there while on a hidden display. On by default. */
    fun hideFromRecents(c: Context): Boolean = p(c).getBoolean("hide_recents", true)
    fun setHideFromRecents(c: Context, on: Boolean) { p(c).edit().putBoolean("hide_recents", on).apply() }

    /** Stop everything when the battery is at or below this percent and not charging. 0 = off. */
    fun autoStopBattery(c: Context): Int = p(c).getInt("auto_battery", 0)
    fun setAutoStopBattery(c: Context, v: Int) { p(c).edit().putInt("auto_battery", v).apply() }

    /** Stop an app after it has run this many minutes on a hidden display. 0 = off. */
    fun autoStopMinutes(c: Context): Int = p(c).getInt("auto_minutes", 0)
    fun setAutoStopMinutes(c: Context, v: Int) { p(c).edit().putInt("auto_minutes", v).apply() }

    /** Stop everything when the battery temperature reaches this many degrees C. 0 = off. Charging or not. */
    fun autoStopTemp(c: Context): Int = p(c).getInt("auto_temp", 0)
    fun setAutoStopTemp(c: Context, v: Int) { p(c).edit().putInt("auto_temp", v).apply() }

    /**
     * Stop an app at a clock time. The value is minute-of-day + 1 (1 = 00:00, 1440 = 23:59), 0 = off.
     * It means the next time the clock reaches it after the app is launched, once.
     */
    fun autoStopAt(c: Context): Int = p(c).getInt("auto_at", 0)
    fun setAutoStopAt(c: Context, v: Int) { p(c).edit().putInt("auto_at", v).apply() }

    /** Show memory and CPU on each running app. On by default; turning it off makes refreshing faster. */
    fun showStats(c: Context): Boolean = p(c).getBoolean("show_stats", true)
    fun setShowStats(c: Context, on: Boolean) { p(c).edit().putBoolean("show_stats", on).apply() }

    /** Per-app auto-stop. -1 means "use the default from Settings", 0 means off. */
    fun appBattery(c: Context, app: AppRef): Int = p(c).getInt("auto_bat_${app.key}", -1)
    fun setAppBattery(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("auto_bat_${app.key}", v).apply() }
    fun appTemp(c: Context, app: AppRef): Int = p(c).getInt("auto_temp_${app.key}", -1)
    fun setAppTemp(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("auto_temp_${app.key}", v).apply() }
    fun appMinutes(c: Context, app: AppRef): Int = p(c).getInt("auto_min_${app.key}", -1)
    fun setAppMinutes(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("auto_min_${app.key}", v).apply() }

    fun appAt(c: Context, app: AppRef): Int = p(c).getInt("auto_at_${app.key}", -1)
    fun setAppAt(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("auto_at_${app.key}", v).apply() }
    fun effectiveAt(c: Context, app: AppRef): Int = appAt(c, app).let { if (it < 0) autoStopAt(c) else it }

    fun effectiveBattery(c: Context, app: AppRef): Int = appBattery(c, app).let { if (it < 0) autoStopBattery(c) else it }
    fun effectiveTemp(c: Context, app: AppRef): Int = appTemp(c, app).let { if (it < 0) autoStopTemp(c) else it }
    fun effectiveMinutes(c: Context, app: AppRef): Int = appMinutes(c, app).let { if (it < 0) autoStopMinutes(c) else it }

    /** Refresh rate of new hidden displays, in Hz. 0 = the phone's own rate. */
    const val MAX_HZ = 144
    fun refreshRate(c: Context): Int = p(c).getInt("refresh_hz", 0)
    fun setRefreshRate(c: Context, v: Int) { p(c).edit().putInt("refresh_hz", v).apply() }
    /** Per-app rate. -1 = use the Settings value, 0 = the phone's own rate. */
    fun appRefreshRate(c: Context, app: AppRef): Int = p(c).getInt("hz_${app.key}", -1)
    fun setAppRefreshRate(c: Context, app: AppRef, v: Int) { p(c).edit().putInt("hz_${app.key}", v).apply() }
    fun effectiveRefreshRate(c: Context, app: AppRef): Int =
        appRefreshRate(c, app).let { if (it < 0) refreshRate(c) else it }.coerceIn(0, MAX_HZ)

    /** Per app: pause the app while the screen is off and resume it when the screen comes on. Off by default. */
    fun freezeWhenOff(c: Context, app: AppRef): Boolean = p(c).getBoolean("freeze_off_${app.key}", false)
    fun setFreezeWhenOff(c: Context, app: AppRef, on: Boolean) { p(c).edit().putBoolean("freeze_off_${app.key}", on).apply() }
}
