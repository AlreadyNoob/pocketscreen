package com.hiderlab.vd

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.os.Process

/** Launcher app list. Querying packages and loading labels happens on a background thread. */
object Apps {
    /** [landscape] is the orientation the app's launcher activity declares: true, false, or null if unspecified. */
    data class App(val label: String, val pkg: String, val landscape: Boolean?) {
        val key: String = label.lowercase()
    }

    @Volatile var list: List<App> = emptyList()
        private set
    @Volatile var ready = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val waiters = ArrayList<() -> Unit>()   // touched on the main thread only
    private var loading = false                      // main thread only
    private var dirty = false                        // main thread only

    private fun orientation(o: Int): Boolean? = when (o) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false
        else -> null
    }

    /**
     * Starts loading the list if it isn't loaded yet (or when [force] is set, after apps were
     * installed or removed). [then] runs on the main thread as soon as a list is available.
     */
    fun load(ctx: Context, force: Boolean = false, then: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        main.post {
            if (then != null) { if (ready) then() else waiters.add(then) }
            if (ready && !force) return@post
            if (loading) { if (force) dirty = true; return@post }
            loading = true
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                val result = try {
                    val pm = app.packageManager
                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    pm.queryIntentActivities(intent, 0)
                        .filter { it.activityInfo.packageName != app.packageName }
                        .distinctBy { it.activityInfo.packageName }
                        .map {
                            App(
                                it.loadLabel(pm).toString(),
                                it.activityInfo.packageName,
                                orientation(it.activityInfo.screenOrientation)
                            )
                        }
                        .sortedBy { it.key }
                } catch (_: Throwable) { null }
                main.post {
                    if (result != null) { list = result; ready = true }
                    loading = false
                    val w = ArrayList(waiters)
                    waiters.clear()
                    w.forEach { it() }
                    if (dirty) { dirty = false; load(app, force = true) }
                }
            }, "hider-apps").start()
        }
    }

    /** Label for a package, from the loaded list when possible. */
    fun label(ctx: Context, pkg: String): String =
        list.firstOrNull { it.pkg == pkg }?.label ?: try {
            val pm = ctx.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) { pkg }
}
