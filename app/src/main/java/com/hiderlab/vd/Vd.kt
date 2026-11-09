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
    data class Disp(val id: Int, val w: Int, val h: Int) {
        val landscape: Boolean get() = w > h
        val label: String get() = "#$id ${if (landscape) "landscape" else "portrait"} ${w}x$h"
    }

    @Volatile var service: IVdService? = null
    @Volatile var displays: List<Disp> = emptyList()
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
            displays = emptyList()
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

    fun display(id: Int): Disp? = displays.firstOrNull { it.id == id }

    /** Reads the display list from the service (it may already have displays from an earlier session). */
    fun refreshInfo() {
        val s = service ?: return
        try {
            val parts = s.info().split("#", limit = 2)
            displays = parts[0].split(";").mapNotNull {
                val p = it.split(",")
                if (p.size == 3) {
                    val id = p[0].toIntOrNull(); val w = p[1].toIntOrNull(); val h = p[2].toIntOrNull()
                    if (id != null && w != null && h != null) Disp(id, w, h) else null
                } else null
            }
            inputError = parts.getOrElse(1) { "" }
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

    /** Creates a display and returns its id, or -1 on failure. */
    fun createDisplay(ctx: Context, landscape: Boolean): Int {
        val s = service ?: run { log("Not connected"); return -1 }
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
        if (landscape) { val t = w; w = h; h = t }
        val r = try { s.createDisplay(w, h, dpi) } catch (t: Throwable) { "ERR: $t" }
        log("createDisplay: $r")
        var id = -1
        if (r.startsWith("OK:")) {
            id = r.removePrefix("OK:").substringBefore(' ').toIntOrNull() ?: -1
            refreshInfo()
            try {
                ctx.applicationContext.startForegroundService(Intent(ctx.applicationContext, HostService::class.java))
            } catch (t: Throwable) { log("Notification service not started: $t") }
        }
        changed()
        return id
    }

    /** Finds a display of the wanted orientation, or creates one. Returns its id, or -1. */
    fun ensureDisplay(ctx: Context, landscape: Boolean): Int =
        displays.firstOrNull { it.landscape == landscape }?.id ?: createDisplay(ctx, landscape)

    fun launch(ctx: Context, pkg: String, display: Int): String {
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return "No launcher activity for $pkg"
        // If it is already open on the main screen, try to move that task instead of starting a copy.
        val onMain = tasks(0).firstOrNull { it.second == pkg }
        if (onMain != null) {
            val r = exec("am display move-stack ${onMain.first} $display")
            if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) return "Moved $pkg to display $display. $r"
            log("move-stack failed, starting fresh: $r")
        }
        return exec("am start --display $display -n $comp")
    }

    fun toMain(ctx: Context, taskId: Int, pkg: String): String {
        val r = exec("am display move-stack $taskId 0")
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return "move-stack result: $r"
        if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) {
            return "Moved to main. $r\n" + exec("am start --display 0 -n $comp")
        }
        return "move-stack failed ($r)\n" + exec("am start --display 0 -n $comp")
    }

    private fun stopHostIfIdle(ctx: Context) {
        if (displays.isEmpty()) {
            try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, HostService::class.java)) } catch (_: Throwable) {}
        }
    }

    /** Force-stops the apps on one display, then removes it. */
    fun destroyDisplay(ctx: Context, id: Int) {
        tasks(id).map { it.second }.distinct().forEach { exec("am force-stop $it") }
        try { service?.releaseDisplay(id) } catch (_: Throwable) {}
        refreshInfo()
        stopHostIfIdle(ctx)
        changed()
    }

    fun stopAll(ctx: Context) {
        displays.map { it.id }.forEach { destroyDisplay(ctx, it) }
        displays = emptyList()
        stopHostIfIdle(ctx)
        changed()
    }
}
