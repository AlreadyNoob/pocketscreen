package com.hiderlab.vd

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Freeze when the screen is off". Works without root: while the screen is off, an empty black
 * screen ([ParkActivity]) is started on the app's hidden display, on top of the app. Android then stops
 * the app underneath exactly as if you had switched to another app (games pause, rendering and their
 * sound stop, the process drops to the background and can be frozen by the system). When the screen
 * comes back on, the black screen is closed and the app is resumed where it was.
 *
 * It does not pause an app that keeps working in the background by itself (a music player, a download).
 * After each attempt the app's state is read back, and the result is saved in [status] for the settings.
 */
object Freezer {
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("freeze", Context.MODE_PRIVATE)

    /** What happened the last time, in words (with the time), or empty. Shown under the setting. */
    fun status(c: Context, pkg: String): String = prefs(c).getString("status_$pkg", "") ?: ""
    private fun setStatus(c: Context, pkg: String, s: String) {
        val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        prefs(c).edit().putString("status_$pkg", "$s ($t)").apply()
        Debug.write("freeze $pkg: $s")
    }

    private const val NO_HISTORY_FLAGS = 0x10000000 or 0x00800000 // NEW_TASK | EXCLUDE_FROM_RECENTS

    /** The state Android reports for the app's top activity: RESUMED, PAUSED, STOPPED... or null. */
    private fun activityState(pkg: String): String? {
        val out = Vd.exec("dumpsys activity activities")
        val lines = out.lines()
        var found: String? = null
        for ((i, l) in lines.withIndex()) {
            if (!l.contains("ActivityRecord{") || !l.contains(" $pkg/")) continue
            for (j in i + 1..minOf(i + 40, lines.size - 1)) {
                val m = Regex("\\bstate=(\\w+)").find(lines[j]) ?: continue
                found = m.groupValues[1]; break
            }
            if (found != null) break
        }
        return found
    }

    /** Called when the screen goes off: pauses every app that has the option on. */
    @Synchronized
    fun onScreenOff(c: Context, apps: List<String>) {
        Debug.write("freeze: screen off, running apps = $apps, with the option on = ${apps.filter { AppSettings.freezeWhenOff(c, it) }}")
        val byDisplay = apps.groupBy { Vd.displayOf(it) }
        for ((display, pkgs) in byDisplay) {
            val want = pkgs.filter { AppSettings.freezeWhenOff(c, it) }
            if (want.isEmpty()) continue
            if (display == null) {
                want.forEach { setStatus(c, it, "not paused: its display was not found") }
                continue
            }
            if (want.size != pkgs.size) {
                want.forEach { setStatus(c, it, "not paused: another app shares its display") }
                continue
            }
            val r = Vd.exec("am start --display $display -f $NO_HISTORY_FLAGS -n ${c.packageName}/.ParkActivity")
            Debug.write("park display $display: ${r.trim().take(200)}")
            // Give Android a moment to stop what is underneath.
            Thread.sleep(1500)
            for (pkg in want) {
                val st = activityState(pkg)
                when {
                    st == null -> setStatus(c, pkg, "paused, could not confirm")
                    st.equals("RESUMED", true) -> {
                        setStatus(c, pkg, "did not pause: it stayed in the foreground")
                        ParkActivity.finishAll()
                    }
                    else -> setStatus(c, pkg, "paused ($st)")
                }
            }
        }
    }

    /** Called when the screen is back on (and whenever the host service starts or stops). */
    @Synchronized
    fun onScreenOn(c: Context) {
        if (!ParkActivity.isParked()) return
        ParkActivity.finishAll()
        Debug.write("freeze: resumed")
    }

    /** Called when an app's option is switched off or the app is stopped. */
    fun forget(c: Context, pkg: String) {
        // Nothing is held per app: closing the black screen is what resumes. Only clear the note.
        prefs(c).edit().remove("status_$pkg").apply()
    }
}
