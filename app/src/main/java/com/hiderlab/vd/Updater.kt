package com.hiderlab.vd

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer GitHub release. Release tags are `v<major>.<minor>.<patch>`, the same as the app's
 * versionName, so "newer" means a higher version than ours.
 * It only reads the public releases API; nothing is installed from here.
 */
object Updater {
    private const val API = "https://api.github.com/repos/AlreadyNoob/pocketscreen/releases/latest"
    private const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000

    data class Release(val version: String, val page: String, val notes: String)

    sealed class Result {
        data class Newer(val release: Release) : Result()
        object UpToDate : Result()
        data class Failed(val why: String) : Result()
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** Automatic check on start. On by default. */
    fun autoEnabled(c: Context): Boolean = prefs(c).getBoolean("auto", true)
    fun setAutoEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean("auto", on).apply() }

    /** Blocking network call: run it off the main thread. */
    fun check(c: Context): Result = try {
        val con = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PocketScreen")
        }
        try {
            if (con.responseCode != 200) Result.Failed("GitHub answered ${con.responseCode}")
            else {
                val j = JSONObject(con.inputStream.bufferedReader().readText())
                val tag = j.getString("tag_name").removePrefix("v")
                val theirs = parse(tag)
                if (theirs == null) Result.Failed("Unrecognised version $tag")
                else if (compare(theirs, parse(currentVersion(c)) ?: listOf(0, 0, 0)) > 0)
                    Result.Newer(Release(tag, j.optString("html_url"), j.optString("body")))
                else Result.UpToDate
            }
        } finally { con.disconnect() }
    } catch (t: Throwable) {
        Result.Failed(t.javaClass.simpleName)
    }

    private fun currentVersion(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()

    /** "1.2.3" -> [1, 2, 3]; null if it isn't that shape. */
    private fun parse(v: String): List<Int>? {
        val p = v.removePrefix("v").split(".").map { it.toIntOrNull() ?: return null }
        return if (p.size == 3) p else null
    }

    private fun compare(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until 3) if (a[i] != b[i]) return a[i].compareTo(b[i])
        return 0
    }

    /**
     * The automatic check: at most every 12 hours, and a given release is only announced once.
     * Calls [onNewer] (on a background thread) when there is something new to show.
     */
    fun autoCheck(c: Context, onNewer: (Release) -> Unit) {
        if (!autoEnabled(c)) return
        val p = prefs(c)
        val now = System.currentTimeMillis()
        if (now - p.getLong("last", 0L) < AUTO_INTERVAL_MS) return
        Thread {
            val r = check(c)
            if (r !is Result.Failed) p.edit().putLong("last", now).apply()
            if (r is Result.Newer && p.getString("seen", "") != r.release.version) {
                p.edit().putString("seen", r.release.version).apply()
                onNewer(r.release)
            }
        }.start()
    }
}
