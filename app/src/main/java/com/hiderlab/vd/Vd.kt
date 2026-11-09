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

    private const val LAUNCH_FLAGS = 0x10800000 // FLAG_ACTIVITY_NEW_TASK (0x10000000) | FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS (0x00800000)

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

    // ---- freeze / resume ----

    /** Main process of the app, or empty if it isn't running. */
    private fun pidsOf(pkg: String, all: Boolean): List<Int> {
        if (!all) return exec("pidof $pkg").trim().split(" ").mapNotNull { it.toIntOrNull() }.take(1)
        return exec("ps -A -o PID,NAME").lines().mapNotNull { l ->
            val p = l.trim().split(Regex("\\s+"), limit = 2)
            if (p.size == 2 && (p[1] == pkg || p[1].startsWith("$pkg:"))) p[0].toIntOrNull() else null
        }
    }

    private fun stateOf(pid: Int): Char? =
        exec("grep -m1 State: /proc/$pid/status").trim().removePrefix("State:").trim().firstOrNull()

    /** True when the app's main process is stopped (state T). */
    fun isFrozen(pkg: String): Boolean = pidsOf(pkg, false).firstOrNull()?.let { stateOf(it) == 'T' } ?: false

    /**
     * Pauses (SIGSTOP) or continues (SIGCONT) every process of the app. The app stays in memory
     * with its state intact; it just gets no CPU time, so it makes no sound and uses no battery.
     * Returns a log line saying what happened.
     */
    fun setFrozen(pkg: String, frozen: Boolean): String {
        val pids = pidsOf(pkg, true)
        if (pids.isEmpty()) return "$pkg isn't running."
        val out = exec("kill -${if (frozen) "STOP" else "CONT"} ${pids.joinToString(" ")}").trim()
        val ok = isFrozen(pkg) == frozen
        return (if (ok) (if (frozen) "Frozen" else "Resumed") + " $pkg (${pids.size} process(es))."
        else "Could not ${if (frozen) "freeze" else "resume"} $pkg.") + if (out.isNotEmpty()) "\n$out" else ""
    }

    /** Resumes every frozen app on the hidden displays. */
    fun resumeAll(): String {
        val pkgs = displays.flatMap { d -> tasks(d.id).map { it.second } }.distinct().filter { isFrozen(it) }
        return if (pkgs.isEmpty()) "No frozen apps." else pkgs.joinToString("\n") { setFrozen(it, false) }
    }

    // ---- per-app mute ----

    private fun mutePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("mute", Context.MODE_PRIVATE)

    /** Apps the user wants muted whenever they run on a hidden display. Saved on the phone. */
    fun mutedApps(ctx: Context): Set<String> = mutePrefs(ctx).getStringSet("pkgs", emptySet()) ?: emptySet()

    fun isMuted(ctx: Context, pkg: String): Boolean = pkg in mutedApps(ctx)

    /** Remembers the choice only. It is applied to the app the next time it is launched. */
    fun setMutedPref(ctx: Context, pkg: String, muted: Boolean) {
        val s = HashSet(mutedApps(ctx))
        if (muted) s.add(pkg) else s.remove(pkg)
        mutePrefs(ctx).edit().putStringSet("pkgs", s).apply()
    }

    /**
     * Turns the app's sound off or on right now, using the PLAY_AUDIO app-op (the same switch
     * that "mute this app" tools use). Returns the shell output for the log.
     */
    private fun applyAudio(pkg: String, muted: Boolean): String {
        val set = exec("cmd appops set $pkg PLAY_AUDIO ${if (muted) "deny" else "default"}")
        val now = exec("cmd appops get $pkg PLAY_AUDIO").trim()
        return (set.trim() + "\n" + now).trim()
    }

    /** Saves the choice and applies it to the running app. */
    fun setMuted(ctx: Context, pkg: String, muted: Boolean): String {
        setMutedPref(ctx, pkg, muted)
        return applyAudio(pkg, muted)
    }

    /**
     * The mute is a system-wide setting for the app, so when the app stops running here we hand its
     * sound back; otherwise it would stay silent on the main screen too. The choice itself is kept
     * and is applied again on the next launch.
     */
    private fun restoreAudio(ctx: Context, pkg: String) {
        if (isMuted(ctx, pkg)) applyAudio(pkg, false)
    }

    /** Gives sound back to every app that is set to muted. The saved choices stay. */
    fun restoreAllAudio(ctx: Context): String =
        mutedApps(ctx).joinToString("\n") { "$it: " + applyAudio(it, false).lines().lastOrNull().orEmpty() }
            .ifEmpty { "No muted apps." }

    /** Force-stops an app and returns its sound to normal. */
    fun stopApp(ctx: Context, pkg: String): String {
        val r = exec("am force-stop $pkg")
        restoreAudio(ctx, pkg)
        return r
    }

    // ---- display size settings ----

    /** How big new hidden displays are. Saved on the phone, applies to displays created from now on. */
    data class SizeCfg(val matchRatio: Boolean, val width: Int, val customH: Int, val dpi: Int)

    const val DEF_WIDTH = 720
    const val DEF_DPI = 320

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("display", Context.MODE_PRIVATE)

    fun sizeCfg(ctx: Context): SizeCfg {
        val p = prefs(ctx)
        return SizeCfg(
            p.getBoolean("match", true),
            p.getInt("w", DEF_WIDTH),
            p.getInt("h", 1600),
            p.getInt("dpi", DEF_DPI)
        )
    }

    fun saveSizeCfg(ctx: Context, c: SizeCfg) {
        prefs(ctx).edit().putBoolean("match", c.matchRatio).putInt("w", c.width)
            .putInt("h", c.customH).putInt("dpi", c.dpi).apply()
    }

    private fun even(v: Int) = v and 1.inv()

    /** The (width, height, dpi) a new display would get. Width/height are already swapped for landscape. */
    fun sizeFor(ctx: Context, landscape: Boolean, cfg: SizeCfg? = null): Triple<Int, Int, Int> {
        val c = cfg ?: sizeCfg(ctx)
        val dm = ctx.resources.displayMetrics
        val ratio = maxOf(dm.heightPixels, dm.widthPixels).toFloat() / minOf(dm.heightPixels, dm.widthPixels)
        // Portrait shape first: w is the short side, h the long side.
        var w = even(c.width.coerceIn(240, 2160))
        var h = if (c.matchRatio) (w * ratio).toInt() else c.customH
        h = even(h.coerceIn(240, 3840))
        val dpi = c.dpi.coerceIn(120, 640)
        return if (landscape) Triple(h, w, dpi) else Triple(w, h, dpi)
    }

    /** Creates a display and returns its id, or -1 on failure. */
    fun createDisplay(ctx: Context, landscape: Boolean): Int {
        val s = service ?: run { log("Not connected"); return -1 }
        val (w, h, dpi) = sizeFor(ctx, landscape)
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

    /** The hidden display [pkg] is already running on, or null. */
    fun displayOf(pkg: String): Int? = displays.firstOrNull { d -> tasks(d.id).any { it.second == pkg } }?.id

    /**
     * Every app gets its own display. Android only keeps the top app on a display resumed, so a
     * second app on the same display would freeze the first. An empty display of the right
     * size is reused; otherwise a new one is created. Returns its id, or -1.
     */
    fun freeDisplay(ctx: Context, landscape: Boolean): Int {
        // Only reuse a display that already has exactly the size a new one would get now.
        val (w, h, _) = sizeFor(ctx, landscape)
        return displays.firstOrNull { it.w == w && it.h == h && tasks(it.id).isEmpty() }?.id
            ?: createDisplay(ctx, landscape)
    }

    fun launch(ctx: Context, pkg: String, display: Int): String {
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return "No launcher activity for $pkg"
        if (isMuted(ctx, pkg)) applyAudio(pkg, true)
        // If it is already open on the main screen, try to move that task instead of starting a copy.
        val onMain = tasks(0).firstOrNull { it.second == pkg }
        if (onMain != null) {
            val r = exec("am display move-stack ${onMain.first} $display")
            if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) return "Moved $pkg to display $display. $r"
            log("move-stack failed, starting fresh: $r")
        }
        // -f = NEW_TASK | EXCLUDE_FROM_RECENTS, so the task is not listed in the phone's Recents.
        return exec("am start --display $display -f $LAUNCH_FLAGS -n $comp")
    }

    private fun stopHostIfIdle(ctx: Context) {
        if (displays.isEmpty()) {
            try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, HostService::class.java)) } catch (_: Throwable) {}
        }
    }

    /** Force-stops the apps on one display, then removes it. */
    fun destroyDisplay(ctx: Context, id: Int) {
        tasks(id).map { it.second }.distinct().forEach { stopApp(ctx, it) }
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
