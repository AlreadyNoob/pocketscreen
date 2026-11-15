package com.hiderlab.vd

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * Launcher app list and icon cache. Everything slow (querying packages, loading labels, decoding
 * icons) happens on background threads, started when the app opens, so the picker has nothing
 * left to do on the UI thread when you tap "Launch app".
 */
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
    private var iconPx = 0

    private val icons = LruCache<String, Bitmap>(400)
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "hider-icons").also { it.isDaemon = true }
    }

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
            if (iconPx == 0) iconPx = Ui.dp(app, 44)
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
                    else if (result != null) prefetchIcons(app)
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

    /**
     * Returns the icon if it is already cached. Otherwise returns null and loads it in the
     * background; [onReady] then runs on the main thread. Check that the view still wants this
     * package before using the result, because list rows get recycled.
     */
    fun icon(ctx: Context, pkg: String, onReady: ((Bitmap) -> Unit)?): Bitmap? {
        icons.get(pkg)?.let { return it }
        val app = ctx.applicationContext
        pool.execute {
            val bmp = icons.get(pkg) ?: render(app, pkg)?.also { icons.put(pkg, it) }
            if (bmp != null && onReady != null) main.post { onReady(bmp) }
        }
        return null
    }

    private fun prefetchIcons(app: Context) {
        for (a in list) if (icons.get(a.pkg) == null) icon(app, a.pkg, null)
    }

    private fun render(app: Context, pkg: String): Bitmap? = try {
        val px = if (iconPx > 0) iconPx else Ui.dp(app, 44)
        val d = app.packageManager.getApplicationIcon(pkg)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, px, px)
        d.draw(Canvas(bmp))
        bmp
    } catch (_: Throwable) { null }
}
