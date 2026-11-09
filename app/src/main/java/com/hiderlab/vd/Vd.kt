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
    data class Disp(val id: Int, val w: Int, val h: Int, val dpi: Int = 0) {
        val landscape: Boolean get() = w > h
        val label: String get() = "#$id ${if (landscape) "landscape" else "portrait"} ${w}x$h"
    }

    private const val LAUNCH_FLAGS = 0x10000000 // FLAG_ACTIVITY_NEW_TASK only, so the task can appear in Recents

    @Volatile var service: IVdService? = null
    @Volatile var displays: List<Disp> = emptyList()
    @Volatile var inputError = ""
    @Volatile private var binding = false

    val listeners = CopyOnWriteArrayList<Runnable>()
    @Volatile var logSink: ((String) -> Unit)? = null

    fun log(s: String) { Debug.write(s); logSink?.invoke(s) }
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
                if (p.size >= 3) {
                    val id = p[0].toIntOrNull(); val w = p[1].toIntOrNull(); val h = p[2].toIntOrNull()
                    val dpi = p.getOrNull(3)?.toIntOrNull() ?: 0
                    if (id != null && w != null && h != null) Disp(id, w, h, dpi) else null
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
        val set = exec("cmd appops set $pkg PLAY_AUDIO ${if (muted) "deny" else "allow"}")
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

    /** Re-applies the saved sound state; used shortly after launch, once the app's process exists. */
    fun syncAudio(ctx: Context, pkg: String): String = applyAudio(pkg, isMuted(ctx, pkg))

    /** Force-stops an app and returns its sound to normal. */
    fun stopApp(ctx: Context, pkg: String): String {
        val r = exec("am force-stop $pkg")
        restoreAudio(ctx, pkg)
        clearSession(ctx, pkg)
        return r
    }

    /** Force-stops an app, then removes its display if nothing else is left on it. */
    fun stopAppAndDisplay(ctx: Context, pkg: String): String {
        val id = displayOf(pkg)
        val r = stopApp(ctx, pkg)
        if (id != null) {
            Thread.sleep(700)
            if (tasks(id).isEmpty()) releaseOnly(ctx, id)
        }
        return r
    }

    /**
     * Removes the app's hidden display (if nothing else is on it) and leaves the app alone: it is
     * not launched anywhere and not stopped, it just stays in Recents.
     */
    fun detachApp(ctx: Context, pkg: String, displayId: Int): String {
        restoreAudio(ctx, pkg)
        clearSession(ctx, pkg)
        val others = tasks(displayId).any { it.second != pkg }
        if (!others) releaseOnly(ctx, displayId)
        return if (others) "Display kept: other apps are on it." else "Display $displayId removed."
    }

    // ---- memory and CPU ----

    data class Stat(val memMb: Int?, val cpu: Double?)

    /**
     * Approximate memory (PSS, from dumpsys meminfo) and recent CPU use (from dumpsys cpuinfo, an
     * average over the last few minutes) for each package. Missing values are null.
     */
    fun appStats(pkgs: List<String>): Map<String, Stat> {
        if (pkgs.isEmpty()) return emptyMap()
        val cpuByPkg = HashMap<String, Double>()
        try {
            val pat = pkgs.joinToString("|")
            val out = exec("dumpsys cpuinfo | grep -E '/($pat)'")
            val re = Regex("(\\d+(?:\\.\\d+)?)%\\s+\\d+/([\\w.]+)(?::[\\w.]+)?:")
            for (line in out.lines()) {
                val m = re.find(line) ?: continue
                val name = m.groupValues[2]
                if (name in pkgs) cpuByPkg[name] = (cpuByPkg[name] ?: 0.0) + (m.groupValues[1].toDoubleOrNull() ?: 0.0)
            }
        } catch (_: Throwable) {}
        // dumpsys cpuinfo counts one full core as 100%, so 130% means 1.3 cores. Show share of the whole CPU.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val res = HashMap<String, Stat>()
        for (pkg in pkgs) {
            var mem: Int? = null
            try {
                val out = exec("dumpsys meminfo $pkg")
                val m = Regex("TOTAL PSS:\\s+(\\d+)").find(out) ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)").find(out)
                mem = m?.groupValues?.get(1)?.toLongOrNull()?.let { (it / 1024).toInt() }
            } catch (_: Throwable) {}
            res[pkg] = Stat(mem, cpuByPkg[pkg]?.let { (it / cores).coerceAtMost(100.0) })
        }
        return res
    }

    // ---- quick launch presets ----

    private fun presetPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("presets", Context.MODE_PRIVATE)

    /** Saved apps for one-tap launch: package and orientation, in the order they were saved. */
    fun presets(ctx: Context): List<Pair<String, Boolean>> =
        (presetPrefs(ctx).getString("list", "") ?: "").lines().mapNotNull {
            val p = it.split("|")
            if (p.size == 2 && p[0].isNotEmpty()) p[0] to (p[1] == "1") else null
        }

    fun presetOf(ctx: Context, pkg: String): Boolean? = presets(ctx).firstOrNull { it.first == pkg }?.second

    private fun writePresets(ctx: Context, l: List<Pair<String, Boolean>>) {
        presetPrefs(ctx).edit().putString("list", l.joinToString("\n") { "${it.first}|${if (it.second) 1 else 0}" }).apply()
    }

    fun savePreset(ctx: Context, pkg: String, landscape: Boolean) {
        val l = presets(ctx).toMutableList()
        val i = l.indexOfFirst { it.first == pkg }
        if (i >= 0) l[i] = pkg to landscape else l.add(pkg to landscape)
        writePresets(ctx, l)
    }

    fun removePreset(ctx: Context, pkg: String) { writePresets(ctx, presets(ctx).filter { it.first != pkg }) }

    // ---- session time ----

    private fun sessionPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    fun markSession(ctx: Context, pkg: String) { sessionPrefs(ctx).edit().putLong(pkg, System.currentTimeMillis()).apply() }
    fun clearSession(ctx: Context, pkg: String) { sessionPrefs(ctx).edit().remove(pkg).apply() }

    /** Whole minutes [pkg] has been running here, or null if unknown. */
    fun sessionMinutes(ctx: Context, pkg: String): Long? {
        val t = sessionPrefs(ctx).getLong(pkg, 0L)
        return if (t <= 0L) null else ((System.currentTimeMillis() - t) / 60000L).coerceAtLeast(0)
    }

    /** Minutes as "5m" / "2h 10m". */
    fun fmtMinutes(mins: Long): String = when {
        mins < 1 -> "<1m"
        mins < 60 -> "${mins}m"
        else -> "${mins / 60}h ${mins % 60}m"
    }

    /** How long [pkg] has been running here, as "5m" / "2h 10m", or null if unknown. */
    fun sessionText(ctx: Context, pkg: String): String? {
        val t = sessionPrefs(ctx).getLong(pkg, 0L)
        if (t <= 0L) return null
        return fmtMinutes(((System.currentTimeMillis() - t) / 60000L).coerceAtLeast(0))
    }

    /**
     * Time left before auto-stop, as "18m left" / "1h 5m left", or null when no time limit applies
     * to [pkg] (neither its own nor the default) or the start time is unknown.
     */
    fun remainingText(ctx: Context, pkg: String): String? {
        val limit = AppSettings.effectiveMinutes(ctx, pkg)
        if (limit <= 0) return null
        val t = sessionPrefs(ctx).getLong(pkg, 0L)
        if (t <= 0L) return null
        val leftMs = limit * 60000L - (System.currentTimeMillis() - t)
        if (leftMs <= 0L) return "stopping soon"
        val mins = (leftMs + 59999L) / 60000L
        return "${fmtMinutes(mins)} left"
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

    // ---- per-app size (overrides the default only for that app) ----

    private fun appSizePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("app_size", Context.MODE_PRIVATE)

    fun appCfg(ctx: Context, pkg: String): SizeCfg? {
        val v = appSizePrefs(ctx).getString(pkg, null) ?: return null
        val p = v.split(",")
        if (p.size != 4) return null
        return SizeCfg(p[0] == "1", p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null, p[3].toIntOrNull() ?: return null)
    }

    /** Pass null to go back to the default size. */
    fun setAppCfg(ctx: Context, pkg: String, c: SizeCfg?) {
        val e = appSizePrefs(ctx).edit()
        if (c == null) e.remove(pkg) else e.putString(pkg, "${if (c.matchRatio) 1 else 0},${c.width},${c.customH},${c.dpi}")
        e.apply()
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
    fun createDisplay(ctx: Context, landscape: Boolean, cfg: SizeCfg? = null): Int {
        val s = service ?: run { log("Not connected"); return -1 }
        val (w, h, dpi) = sizeFor(ctx, landscape, cfg)
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
    fun freeDisplay(ctx: Context, landscape: Boolean, cfg: SizeCfg? = null): Int {
        // Only reuse a display that already has exactly the size a new one would get now.
        val (w, h, dpi) = sizeFor(ctx, landscape, cfg)
        return displays.firstOrNull { it.w == w && it.h == h && (it.dpi == 0 || it.dpi == dpi) && tasks(it.id).isEmpty() }?.id
            ?: createDisplay(ctx, landscape, cfg)
    }

    fun launch(ctx: Context, pkg: String, display: Int): String {
        val comp = ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            ?: return "No launcher activity for $pkg"
        // Apps always start with sound on. Mute again from the app's menu if wanted.
        setMutedPref(ctx, pkg, false)
        applyAudio(pkg, false)
        markSession(ctx, pkg)
        // If it is already open on the main screen, try to move that task instead of starting a copy.
        val onMain = tasks(0).firstOrNull { it.second == pkg }
        if (onMain != null) {
            val r = exec("am display move-stack ${onMain.first} $display")
            if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) return "Moved $pkg to display $display. $r"
            log("move-stack failed, starting fresh: $r")
        }
        // -f = NEW_TASK, plus EXCLUDE_FROM_RECENTS unless turned off in Settings.
        val flags = if (AppSettings.hideFromRecents(ctx)) LAUNCH_FLAGS or 0x00800000 else LAUNCH_FLAGS // 0x00800000 = EXCLUDE_FROM_RECENTS
        return exec("am start --display $display -f $flags -n $comp")
    }

    private fun stopHostIfIdle(ctx: Context) {
        if (displays.isEmpty()) {
            try { ctx.applicationContext.stopService(Intent(ctx.applicationContext, HostService::class.java)) } catch (_: Throwable) {}
        }
    }

    /** Removes a display without touching the apps on it. */
    fun releaseOnly(ctx: Context, id: Int) {
        try { service?.releaseDisplay(id) } catch (_: Throwable) {}
        refreshInfo()
        stopHostIfIdle(ctx)
        changed()
    }

    /** Force-stops the apps on one display, then removes it. */
    fun destroyDisplay(ctx: Context, id: Int) {
        tasks(id).map { it.second }.distinct().forEach { stopApp(ctx, it) }
        releaseOnly(ctx, id)
    }

    fun stopAll(ctx: Context) {
        displays.map { it.id }.forEach { destroyDisplay(ctx, it) }
        displays = emptyList()
        stopHostIfIdle(ctx)
        changed()
    }
}
