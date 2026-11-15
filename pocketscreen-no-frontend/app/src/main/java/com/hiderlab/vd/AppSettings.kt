package com.hiderlab.vd

import android.content.Context

/** Persisted preferences. */
object AppSettings {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Launch apps with "exclude from Recents" while on a hidden display. On by default. */
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

    /** Per-app auto-stop. -1 means "use the default", 0 means off. */
    fun appBattery(c: Context, pkg: String): Int = p(c).getInt("auto_bat_$pkg", -1)
    fun setAppBattery(c: Context, pkg: String, v: Int) { p(c).edit().putInt("auto_bat_$pkg", v).apply() }
    fun appTemp(c: Context, pkg: String): Int = p(c).getInt("auto_temp_$pkg", -1)
    fun setAppTemp(c: Context, pkg: String, v: Int) { p(c).edit().putInt("auto_temp_$pkg", v).apply() }
    fun appMinutes(c: Context, pkg: String): Int = p(c).getInt("auto_min_$pkg", -1)
    fun setAppMinutes(c: Context, pkg: String, v: Int) { p(c).edit().putInt("auto_min_$pkg", v).apply() }

    fun appAt(c: Context, pkg: String): Int = p(c).getInt("auto_at_$pkg", -1)
    fun setAppAt(c: Context, pkg: String, v: Int) { p(c).edit().putInt("auto_at_$pkg", v).apply() }
    fun effectiveAt(c: Context, pkg: String): Int = appAt(c, pkg).let { if (it < 0) autoStopAt(c) else it }

    fun effectiveBattery(c: Context, pkg: String): Int = appBattery(c, pkg).let { if (it < 0) autoStopBattery(c) else it }
    fun effectiveTemp(c: Context, pkg: String): Int = appTemp(c, pkg).let { if (it < 0) autoStopTemp(c) else it }
    fun effectiveMinutes(c: Context, pkg: String): Int = appMinutes(c, pkg).let { if (it < 0) autoStopMinutes(c) else it }

    /** Refresh rate of new hidden displays, in Hz. 0 = the phone's own rate. */
    const val MAX_HZ = 144
    fun refreshRate(c: Context): Int = p(c).getInt("refresh_hz", 0)
    fun setRefreshRate(c: Context, v: Int) { p(c).edit().putInt("refresh_hz", v).apply() }
    /** Per-app rate. -1 = use the default value, 0 = the phone's own rate. */
    fun appRefreshRate(c: Context, pkg: String): Int = p(c).getInt("hz_$pkg", -1)
    fun setAppRefreshRate(c: Context, pkg: String, v: Int) { p(c).edit().putInt("hz_$pkg", v).apply() }
    fun effectiveRefreshRate(c: Context, pkg: String): Int =
        appRefreshRate(c, pkg).let { if (it < 0) refreshRate(c) else it }.coerceIn(0, MAX_HZ)
}
