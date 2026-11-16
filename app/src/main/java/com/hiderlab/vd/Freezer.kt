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

    /**
     * Asks Android what is in the foreground on the display now: "RESUMED" if the app is still the
     * resumed one, "PAUSED" if our black screen is, or null when the dump says neither. The lines
     * it decided from go to the debug log, so a different dump format on another phone can be seen.
     */
    private fun activityState(pkg: String, display: Int): String? {
        val lines = Vd.exec("dumpsys activity activities").lines()
        val resumed = lines.filter { it.contains("esumedActivity") }
        val mine = lines.filter { it.contains("ActivityRecord{") && it.contains(" $pkg/") }
        Debug.write("freeze dump (display $display):\n" + (resumed + mine).distinct().take(14).joinToString("\n") { it.trim().take(180) })
        val parkResumed = resumed.any { it.contains("ParkActivity") }
        val appResumed = resumed.any { it.contains(" $pkg/") }
        return when {
            appResumed && !parkResumed -> "RESUMED"
            parkResumed -> "PAUSED"
            else -> lines.indices.firstOrNull { lines[it].contains("ActivityRecord{") && lines[it].contains(" $pkg/") }?.let { i ->
                (i + 1..minOf(i + 40, lines.size - 1)).firstNotNullOfOrNull { j -> Regex("\\bstate=(\\w+)").find(lines[j])?.groupValues?.get(1) }
            }
        }
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
            Debug.write("park display $display: ${r.trim().take(900)}")
            // Give Android a moment to stop what is underneath.
            Thread.sleep(1500)
            val started = ParkActivity.isParked()
            val why = r.lineSequence().firstOrNull { it.contains("Exception") || it.contains("Error") }?.trim()?.take(160)
            for (pkg in want) {
                val st = if (started) activityState(pkg, display) else null
                when {
                    !started -> setStatus(c, pkg, "did not pause: the black screen could not start" + (why?.let { " ($it)" } ?: ""))
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
