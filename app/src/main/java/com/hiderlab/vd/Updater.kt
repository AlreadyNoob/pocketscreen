package com.hiderlab.vd

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer GitHub release. Release tags are `v<appVersion>.<build number>` and the build
 * number is also the app's versionCode, so "newer" simply means a higher number than ours.
 * It only reads the public releases API; nothing is installed from here.
 */
object Updater {
    private const val API = "https://api.github.com/repos/AlreadyNoob/pocketscreen/releases/latest"
    private const val AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000

    data class Release(val version: String, val build: Int, val page: String, val notes: String)

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
                val build = tag.substringAfterLast('.').toIntOrNull()
                if (build == null) Result.Failed("Unrecognised version $tag")
                else if (build > currentBuild(c))
                    Result.Newer(Release(tag, build, j.optString("html_url"), j.optString("body")))
                else Result.UpToDate
            }
        } finally { con.disconnect() }
    } catch (t: Throwable) {
        Result.Failed(t.javaClass.simpleName)
    }

    @Suppress("DEPRECATION")
    private fun currentBuild(c: Context): Int = c.packageManager.getPackageInfo(c.packageName, 0).versionCode

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
            if (r is Result.Newer && p.getInt("seen", 0) < r.release.build) {
                p.edit().putInt("seen", r.release.build).apply()
                onNewer(r.release)
            }
        }.start()
    }
}
