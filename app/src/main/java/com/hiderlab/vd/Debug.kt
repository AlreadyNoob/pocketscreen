package com.hiderlab.vd

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug log. Never shown on screen. Written to a file only when the user turns it on in Settings. */
object Debug {
    private const val MAX_BYTES = 400_000L
    @Volatile private var ctx: Context? = null

    fun init(c: Context) { ctx = c.applicationContext }

    private fun prefs(c: Context) = c.getSharedPreferences("debug", Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = prefs(c).getBoolean("on", false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("on", on).apply() }

    fun file(c: Context): File = File(c.applicationContext.filesDir, "pocketscreen-debug.log")

    @Synchronized
    fun write(s: String) {
        val c = ctx ?: return
        try {
            if (!enabled(c)) return
            val f = file(c)
            if (f.exists() && f.length() > MAX_BYTES) f.delete()
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("[$t] $s\n\n")
        } catch (_: Throwable) {}
    }

    fun text(c: Context): String {
        val f = file(c)
        return if (f.exists()) f.readText() else ""
    }

    fun clear(c: Context) { file(c).delete() }
}
