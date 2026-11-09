package com.hiderlab.vd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArrayList

/** App-side connection to the Shizuku service. Shared by the UI, viewer and notification. */
object Vd {
    @Volatile var service: IVdService? = null
    @Volatile var displayId = -1
    @Volatile var width = 0
    @Volatile var height = 0
    @Volatile var inputError = ""
    @Volatile private var binding = false

    val listeners = CopyOnWriteArrayList<Runnable>()
    @Volatile var logSink: ((String) -> Unit)? = null

    fun log(s: String) { logSink?.invoke(s) }
    fun changed() { listeners.forEach { try { it.run() } catch (_: Throwable) {} } }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IVdService.Stub.asInterface(binder)
            binding = false
            log("Service connected")
            Thread { refreshInfo(); changed() }.start()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            binding = false
            displayId = -1
            log("Service disconnected")
            changed()
        }
    }

    fun shizukuRunning(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    fun permitted(): Boolean = shizukuRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    fun connect(ctx: Context, force: Boolean = false) {
        if (force) binding = false
        if (service != null || binding || !permitted()) return
        binding = true
        val code = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, VdService::class.java.name))
            .daemon(true)
            .processNameSuffix("vd")
            .version(code)
        Shizuku.bindUserService(args, conn)
        log("Connecting...")
    }

    /** Reads display id/size from the service (it may already have a display from an earlier session). */
    fun refreshInfo() {
        val s = service ?: return
        try {
            val p = s.info().split(",", limit = 4)
            displayId = p.getOrNull(0)?.toIntOrNull() ?: -1
            width = p.getOrNull(1)?.toIntOrNull() ?: 0
            height = p.getOrNull(2)?.toIntOrNull() ?: 0
            inputError = p.getOrNull(3) ?: ""
        } catch (t: Throwable) { log("info failed: $t") }
    }

    fun exec(cmd: String): String = try { service?.exec(cmd) ?: "ERR: not connected" } catch (t: Throwable) { "ERR: $t" }

    fun tasks(display: Int): List<Pair<Int, String>> {
        val raw = try { service?.tasksOn(display) ?: "" } catch (t: Throwable) { "" }
        if (raw.startsWith("ERR")) { log(raw); return emptyList() }
        return raw.lines().mapNotNull {
            val p = it.split("|")
            if (p.size == 2) p[0].toIntOrNull()?.let { id -> id to p[1] } else null
        }
    }

    fun createDisplay(ctx: Context): String {
        val s = service ?: return "Not connected"
        if (displayId >= 0) return "Display #$displayId already exists"
        val match = ctx.getSharedPreferences("hider", Context.MODE_PRIVATE).getBoolean("match", false)
        var w: Int
        var h: Int
        var dpi = 320
        if (match) {
            val real = android.util.DisplayMetrics()
            val d0 = (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY)
            @Suppress("DEPRECATION") d0.getRealMetrics(real)
            w = minOf(real.widthPixels, real.heightPixels)
            h = maxOf(real.widthPixels, real.heightPixels)
            dpi = real.densityDpi
        } else {
            val dm = ctx.resources.displayMetrics
            val ratio = maxOf(dm.heightPixels, dm.widthPixels).toFloat() / minOf(dm.heightPixels, dm.widthPixels)
            w = 720
            h = (w * ratio).toInt() and 1.inv()
        }
        if (ctx.getSharedPreferences("hider", Context.MODE_PRIVATE).getBoolean("land", false)) {
            val t = w; w = h; h = t
        }
        val r = try { s.createDisplay(w, h, dpi) } catch (t: Throwable) { "ERR: $t" }
        if (r.startsWith("OK:")) {
            refreshInfo()
            try {
                ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, HostService::class.java))
            } catch (t: Throwable) { log("Notification service not started: $t") }
        }
        changed()
        return "createDisplay: $r"
    }

    fun ensureDisplay(ctx: Context): Boolean {
        if (displayId >= 0) return true
        log(createDisplay(ctx))
        return displayId >= 0
    }

    fun launch(ctx: Context, pkg: String): String {
        val s = service ?: return "Not connected"
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return "No launcher activity for $pkg"
        // If it is already open on the main screen, try to move that task instead of starting a copy.
        val onMain = tasks(0).firstOrNull { it.second == pkg }
        if (onMain != null) {
            val r = exec("am display move-stack ${onMain.first} $displayId")
            if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) return "Moved $pkg to display $displayId. $r"
            log("move-stack failed, starting fresh: $r")
        }
        return exec("am start --display $displayId -n $comp")
    }

    fun toMain(ctx: Context, taskId: Int, pkg: String): String {
        val r = exec("am display move-stack $taskId 0")
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return "move-stack result: $r"
        if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) {
            // bring it to the front on the main screen
            return "Moved to main. $r\n" + exec("am start --display 0 -n $comp")
        }
        return "move-stack failed ($r)\n" + exec("am start --display 0 -n $comp")
    }

    /** Force-stops every app on the hidden display, then removes the display. */
    fun stopAll(ctx: Context) {
        val d = displayId
        if (d >= 0) {
            tasks(d).map { it.second }.distinct().forEach { exec("am force-stop $it") }
            try { service?.releaseDisplay() } catch (_: Throwable) {}
        }
        displayId = -1
        width = 0; height = 0
        try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, HostService::class.java)) } catch (_: Throwable) {}
        changed()
    }
}
