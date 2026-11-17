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
    /**
     * Orientation each display was asked for. The shape alone can't tell a square display (a custom
     * 240x240, say) from a portrait one, so what was requested at creation is remembered here.
     */
    private val requestedLandscape = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    data class Disp(val id: Int, val w: Int, val h: Int, val dpi: Int = 0, val fps: Int = 0) {
        val landscape: Boolean get() = if (w != h) w > h else requestedLandscape[id] == true
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
                    val fps = p.getOrNull(4)?.toIntOrNull() ?: 0
                    if (id != null && w != null && h != null) Disp(id, w, h, dpi, fps) else null
                } else null
            }
            inputError = parts.getOrElse(1) { "" }
        } catch (t: Throwable) { log("info failed: $t") }
    }

    fun exec(cmd: String): String = try { service?.exec(cmd) ?: "ERR: not connected" } catch (t: Throwable) { "ERR: $t" }

    /** A task on a display: its id, which copy of the app it is, and how its user was worked out (see [CloneSpace.parseTasks]). */
    data class Task(val id: Int, val app: AppRef, val rule: Char = 'd')

    private var cloneTaskLogs = 0

    /**
     * The tasks on a display, from [IVdService.tasksOn]. A line is `taskId|package|user|rule`, with the raw Task line
     * as a fifth column for a task that isn't the owner's. The older two-column form is still understood (user =
     * owner), in case an older service is still bound after an update.
     */
    fun tasks(display: Int): List<Task> {
        val raw = try { service?.tasksOn(display) ?: "" } catch (t: Throwable) { "" }
        if (raw.startsWith("ERR")) { log(raw); return emptyList() }
        return raw.lines().mapNotNull {
            val p = it.split("|", limit = 5)
            val id = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            if (p.size == 2) return@mapNotNull Task(id, AppRef(p[1]))
            if (p.size < 4) return@mapNotNull null
            val user = p[2].toIntOrNull() ?: return@mapNotNull null
            val t = Task(id, AppRef(p[1], user), p[3].firstOrNull() ?: 'd')
            // The service can't write the app's log, so the first few clone task lines are written here, to check the parsing.
            if (t.app.isClone && p.size == 5 && cloneTaskLogs < 5) {
                cloneTaskLogs++
                log("clone task (user $user, rule ${t.rule}): ${p[4]}")
            }
            t
        }
    }

    /** True if a process of this copy of the app exists, false if not, null if that can't be told. */
    fun isRunning(app: AppRef): Boolean? =
        CloneSpace.processes({ exec(it) }, listOf(app.pkg))?.any { it.name == app.pkg && it.user == app.user }

    // ---- per-app mute ----

    private fun mutePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("mute", Context.MODE_PRIVATE)

    /** The [AppRef.key]s the user wants muted whenever they run on a hidden display. Saved on the phone. */
    fun mutedApps(ctx: Context): Set<String> = mutePrefs(ctx).getStringSet("pkgs", emptySet()) ?: emptySet()

    fun isMuted(ctx: Context, app: AppRef): Boolean = app.key in mutedApps(ctx)

    /** Remembers the choice only. It is applied to the app the next time it is launched. */
    fun setMutedPref(ctx: Context, app: AppRef, muted: Boolean) {
        val s = HashSet(mutedApps(ctx))
        if (muted) s.add(app.key) else s.remove(app.key)
        mutePrefs(ctx).edit().putStringSet("pkgs", s).apply()
    }

    /**
     * Turns the app's sound off or on right now, using the PLAY_AUDIO app-op (the same switch
     * that "mute this app" tools use). It is set for this copy's user only. Returns the shell output for the log.
     */
    private fun applyAudio(app: AppRef, muted: Boolean): String {
        val set = exec("cmd appops set --user ${app.user} ${app.pkg} PLAY_AUDIO ${if (muted) "deny" else "allow"}")
        val now = exec("cmd appops get --user ${app.user} ${app.pkg} PLAY_AUDIO").trim()
        return (set.trim() + "\n" + now).trim()
    }

    /** Saves the choice and applies it to the running app. */
    fun setMuted(ctx: Context, app: AppRef, muted: Boolean): String {
        setMutedPref(ctx, app, muted)
        return applyAudio(app, muted)
    }

    /**
     * The mute is a system-wide setting for the app, so when the app stops running here we hand its
     * sound back; otherwise it would stay silent on the main screen too. The choice itself is kept
     * and is applied again on the next launch.
     */
    private fun restoreAudio(ctx: Context, app: AppRef) {
        if (isMuted(ctx, app)) applyAudio(app, false)
    }

    /** Gives sound back to every muted app and clears the saved choice, so the mute icons show it too. */
    fun restoreAllAudio(ctx: Context): String =
        mutedApps(ctx).toList().joinToString("\n") {
            val app = AppRef.fromKey(it)
            "$it: " + setMuted(ctx, app, false).lines().lastOrNull().orEmpty()
        }.ifEmpty { "No muted apps." }

    /** Re-applies the saved sound state; used shortly after launch, once the app's process exists. */
    fun syncAudio(ctx: Context, app: AppRef): String = applyAudio(app, isMuted(ctx, app))

    /** Force-stops one copy of an app (only that user's) and returns its sound to normal. */
    fun stopApp(ctx: Context, app: AppRef): String {
        val r = exec("am force-stop --user ${app.user} ${app.pkg}")
        try { Freezer.forget(ctx, app) } catch (_: Throwable) {} // drop its "frozen" record
        restoreAudio(ctx, app)
        clearSession(ctx, app)
        return r
    }

    /**
     * Force-stops an app, then removes its display. The display is removed even if the app's task
     * lingers for a moment; it is only kept when a different app is still running on it.
     */
    fun stopAppAndDisplay(ctx: Context, app: AppRef): String {
        val id = displayOf(app)
        var r = stopApp(ctx, app)
        if (id != null) {
            var tries = 0
            while (tries < 30 && tasks(id).any { it.app == app }) {
                Thread.sleep(100); tries++
                if (tries == 15) r = exec("am force-stop --user ${app.user} ${app.pkg}") // still there after 1.5 s: ask again
            }
            if (tasks(id).none { it.app != app }) releaseOnly(ctx, id)
        }
        return r
    }

    // ---- memory and CPU ----

    data class Stat(val memMb: Int?, val cpu: Double?)

    /**
     * Approximate memory (PSS, from dumpsys meminfo) and recent CPU use (from dumpsys cpuinfo, an
     * average over the last few minutes) for each app, keyed by [AppRef.key]. Missing values are null.
     * Only the processes of that copy's user count. If the process list can't be read, an app that has no clone
     * is still looked up by name; a copy that could be mixed up with another gets no values rather than the wrong ones.
     */
    fun appStats(ctx: Context, apps: List<AppRef>): Map<String, Stat> {
        if (apps.isEmpty()) return emptyMap()
        val pkgs = CloneSpace.safePkgs(apps.map { it.pkg })
        val procs = CloneSpace.processes({ exec(it) }, pkgs, children = true)
        fun pidsOf(a: AppRef): Set<Int>? = procs?.filter { it.user == a.user && (it.name == a.pkg || it.name.startsWith(a.pkg + ":")) }?.map { it.pid }?.toSet()
        fun byNameOk(a: AppRef) = !a.isClone && Clones.forPkg(ctx, a.pkg) == null
        val cpuByKey = HashMap<String, Double>()
        try {
            val pat = pkgs.joinToString("|")
            val out = exec("dumpsys cpuinfo | grep -E '/($pat)'")
            val re = Regex("(\\d+(?:\\.\\d+)?)%\\s+(\\d+)/([\\w.]+)(?::[\\w.]+)?:")
            for (line in out.lines()) {
                val m = re.find(line) ?: continue
                val pid = m.groupValues[2].toIntOrNull() ?: continue
                val name = m.groupValues[3]
                val pct = m.groupValues[1].toDoubleOrNull() ?: 0.0
                for (a in apps) {
                    if (a.pkg != name) continue
                    val mine = pidsOf(a)?.let { pid in it } ?: byNameOk(a)
                    if (mine) cpuByKey[a.key] = (cpuByKey[a.key] ?: 0.0) + pct
                }
            }
        } catch (_: Throwable) {}
        // dumpsys cpuinfo counts one full core as 100%, so 130% means 1.3 cores. Show share of the whole CPU.
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val res = HashMap<String, Stat>()
        for (a in apps) {
            var mem: Int? = null
            try {
                val pids = pidsOf(a)
                mem = if (pids != null) { if (pids.isEmpty()) null else memOfPids(pids) }
                else if (byNameOk(a)) memOf(exec("dumpsys meminfo ${a.pkg}"))
                else null
            } catch (_: Throwable) {}
            res[a.key] = Stat(mem, cpuByKey[a.key]?.let { (it / cores).coerceAtMost(100.0) })
        }
        return res
    }

    /** The PSS in MB from one `dumpsys meminfo` output, or null. */
    private fun memOf(out: String): Int? {
        val m = Regex("TOTAL PSS:\\s+(\\d+)").find(out) ?: Regex("(?m)^\\s*TOTAL\\s+(\\d+)").find(out)
        return m?.groupValues?.get(1)?.toLongOrNull()?.let { (it / 1024).toInt() }
    }

    /** Memory of exactly these processes, added up: one `dumpsys meminfo <pid>` each. Null if none could be read. */
    private fun memOfPids(pids: Set<Int>): Int? {
        val cmd = "for p in ${pids.joinToString(" ")}; do echo ==; dumpsys meminfo \$p | grep -E 'TOTAL PSS:|^ *TOTAL +[0-9]'; done"
        val parts = exec(cmd).split("==").mapNotNull { memOf(it) }
        return if (parts.isEmpty()) null else parts.sum()
    }

    // ---- quick launch presets ----

    private fun presetPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("presets", Context.MODE_PRIVATE)

    /** Saved apps for one-tap launch: the app and its orientation, in the order they were saved. */
    fun presets(ctx: Context): List<Pair<AppRef, Boolean>> =
        (presetPrefs(ctx).getString("list", "") ?: "").lines().mapNotNull {
            val p = it.split("|")
            if (p.size == 2 && p[0].isNotEmpty()) AppRef.fromKey(p[0]) to (p[1] == "1") else null
        }

    fun presetOf(ctx: Context, app: AppRef): Boolean? = presets(ctx).firstOrNull { it.first == app }?.second

    private fun writePresets(ctx: Context, l: List<Pair<AppRef, Boolean>>) {
        presetPrefs(ctx).edit().putString("list", l.joinToString("\n") { "${it.first.key}|${if (it.second) 1 else 0}" }).apply()
    }

    fun savePreset(ctx: Context, app: AppRef, landscape: Boolean) {
        val l = presets(ctx).toMutableList()
        val i = l.indexOfFirst { it.first == app }
        if (i >= 0) l[i] = app to landscape else l.add(app to landscape)
        writePresets(ctx, l)
    }

    fun removePreset(ctx: Context, app: AppRef) { writePresets(ctx, presets(ctx).filter { it.first != app }) }

    // ---- session time ----

    private fun sessionPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("sessions", Context.MODE_PRIVATE)

    fun markSession(ctx: Context, app: AppRef) {
        sessionPrefs(ctx).edit().putLong(app.key, System.currentTimeMillis()).apply()
        setDeadline(ctx, app)
    }
    fun clearSession(ctx: Context, app: AppRef) {
        sessionPrefs(ctx).edit().remove(app.key).apply()
        deadlinePrefs(ctx).edit().remove(app.key).apply()
    }

    // ---- "stop at a clock time" ----

    private fun deadlinePrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("deadlines", Context.MODE_PRIVATE)

    /** The next moment after [from] when the clock reads [minuteOfDay] (0..1439). */
    fun nextOccurrence(minuteOfDay: Int, from: Long): Long {
        val c = java.util.Calendar.getInstance()
        c.timeInMillis = from
        c.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(java.util.Calendar.MINUTE, minuteOfDay % 60)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= from) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Fixes the stop moment for [app] from now: the next time its clock limit comes round. Removed if there is none. */
    fun setDeadline(ctx: Context, app: AppRef) {
        val at = AppSettings.effectiveAt(ctx, app)
        val e = deadlinePrefs(ctx).edit()
        if (at > 0) e.putLong(app.key, nextOccurrence(at - 1, System.currentTimeMillis())) else e.remove(app.key)
        e.apply()
    }

    /** After a clock limit was changed in Settings: running apps get their next stop moment from now. */
    fun refreshDeadlines(ctx: Context) {
        for (key in sessionPrefs(ctx).all.keys) setDeadline(ctx, AppRef.fromKey(key))
    }

    /** When [app] will be stopped by its clock limit (ms since epoch), or null if none applies. */
    fun deadline(ctx: Context, app: AppRef): Long? {
        if (AppSettings.effectiveAt(ctx, app) <= 0) return null
        return deadlinePrefs(ctx).getLong(app.key, 0L).takeIf { it > 0L }
    }

    fun clockText(ctx: Context, minuteOfDay: Int): String {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, minuteOfDay / 60)
        c.set(java.util.Calendar.MINUTE, minuteOfDay % 60)
        return android.text.format.DateFormat.getTimeFormat(ctx).format(c.time)
    }

    /** Battery, temperature and clock limits that apply to [app], as "35% battery · 45°C · 7:00 AM". Null if none. */
    fun limitsText(ctx: Context, app: AppRef): String? {
        val parts = ArrayList<String>()
        AppSettings.effectiveBattery(ctx, app).takeIf { it > 0 }?.let { parts.add("$it% battery") }
        AppSettings.effectiveTemp(ctx, app).takeIf { it > 0 }?.let { parts.add("$it°C") }
        deadline(ctx, app)?.let { parts.add(deadlineText(ctx, it)) }
        return if (parts.isEmpty()) null else "Stops at " + parts.joinToString(" · ")
    }

    /** "7:00 AM", or "tomorrow 7:00 AM" when it isn't today. */
    fun deadlineText(ctx: Context, at: Long): String {
        val clock = android.text.format.DateFormat.getTimeFormat(ctx).format(java.util.Date(at))
        val day = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        return if (day.format(java.util.Date(at)) == day.format(java.util.Date())) clock else "tomorrow $clock"
    }

    /** When [app]'s current session started (ms since epoch), or null if unknown. */
    fun sessionStart(ctx: Context, app: AppRef): Long? = sessionPrefs(ctx).getLong(app.key, 0L).takeIf { it > 0L }

    /** Whole minutes [app] has been running here, or null if unknown. */
    fun sessionMinutes(ctx: Context, app: AppRef): Long? {
        val t = sessionPrefs(ctx).getLong(app.key, 0L)
        return if (t <= 0L) null else ((System.currentTimeMillis() - t) / 60000L).coerceAtLeast(0)
    }

    /** Minutes as "5m" / "2h 10m". */
    fun fmtMinutes(mins: Long): String = when {
        mins < 1 -> "<1m"
        mins < 60 -> "${mins}m"
        else -> "${mins / 60}h ${mins % 60}m"
    }

    /** How long [app] has been running here, as "5m" / "2h 10m", or null if unknown. */
    fun sessionText(ctx: Context, app: AppRef): String? {
        val t = sessionPrefs(ctx).getLong(app.key, 0L)
        if (t <= 0L) return null
        return fmtMinutes(((System.currentTimeMillis() - t) / 60000L).coerceAtLeast(0))
    }

    /**
     * Time left before auto-stop, as "18m left" / "1h 5m left", or null when no time limit applies
     * to [app] (neither its own nor the default) or the start time is unknown.
     */
    fun remainingText(ctx: Context, app: AppRef): String? {
        val limit = AppSettings.effectiveMinutes(ctx, app)
        if (limit <= 0) return null
        val t = sessionPrefs(ctx).getLong(app.key, 0L)
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

    fun appCfg(ctx: Context, app: AppRef): SizeCfg? {
        val v = appSizePrefs(ctx).getString(app.key, null) ?: return null
        val p = v.split(",")
        if (p.size != 4) return null
        return SizeCfg(p[0] == "1", p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null, p[3].toIntOrNull() ?: return null)
    }

    /** Pass null to go back to the default size. */
    fun setAppCfg(ctx: Context, app: AppRef, c: SizeCfg?) {
        val e = appSizePrefs(ctx).edit()
        if (c == null) e.remove(app.key) else e.putString(app.key, "${if (c.matchRatio) 1 else 0},${c.width},${c.customH},${c.dpi}")
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

    /**
     * The rate a new display should really get for a wanted [hz]. The display is paced by the phone's
     * screen, so a rate that isn't a divisor of one of the screen's modes judders (75 Hz on a
     * 60/90/120 screen, or anything above the maximum). Those are moved to the nearest mode that
     * works; 0 (Auto) stays 0.
     */
    fun snapRate(ctx: Context, hz: Int): Int {
        if (hz <= 0) return 0
        return try {
            val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
            val d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val cur = d.mode
            val rates = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .map { Math.round(it.refreshRate) }.distinct().sorted()
            val out = when {
                rates.isEmpty() -> hz
                rates.any { it % hz == 0 } -> hz
                else -> rates.firstOrNull { it >= hz } ?: rates.last()
            }
            if (out != hz) log("Refresh rate $hz Hz -> $out Hz (screen modes: ${rates.joinToString()} Hz)")
            out
        } catch (_: Throwable) { hz }
    }

    /** The rate [app] gets on its next display, already adjusted for the screen. */
    fun refreshFor(ctx: Context, app: AppRef): Int = snapRate(ctx, AppSettings.effectiveRefreshRate(ctx, app))

    /** Creates a display and returns its id, or -1 on failure. */
    fun createDisplay(ctx: Context, landscape: Boolean, cfg: SizeCfg? = null, fps: Int = 0): Int {
        val s = service ?: run { log("Not connected"); return -1 }
        val (w, h, dpi) = sizeFor(ctx, landscape, cfg)
        val r = try { s.createDisplay(w, h, dpi, fps) } catch (t: Throwable) { "ERR: $t" }
        log("createDisplay: $r")
        var id = -1
        if (r.startsWith("OK:")) {
            id = r.removePrefix("OK:").substringBefore(' ').toIntOrNull() ?: -1
            if (id >= 0) requestedLandscape[id] = landscape
            refreshInfo()
            if (fps > 0) log("display #$id refresh as Android reports it:\n" +
                exec("dumpsys display | grep -i hiderlab | cut -c1-400"))
            // The notification service starts once the app is up (see startHost), so its first count is right.
        }
        changed()
        return id
    }

    /**
     * Starts the notification service (or lets a running one know). Called when an app has been
     * confirmed on its display, so the first notification already counts it. [app] is that app.
     */
    fun startHost(ctx: Context, app: AppRef? = null) {
        try {
            val i = Intent(ctx.applicationContext, HostService::class.java)
            if (app != null) i.putExtra(HostService.EXTRA_PKG, app.key)
            ctx.applicationContext.startForegroundService(i)
        } catch (t: Throwable) { log("Notification service not started: $t") }
    }

    /** The hidden display this copy of the app is already running on, or null. A clone and its original are different apps here. */
    fun displayOf(app: AppRef): Int? = displays.firstOrNull { d -> tasks(d.id).any { it.app == app } }?.id

    /**
     * Every app gets its own display. Android only keeps the top app on a display resumed, so a
     * second app on the same display would freeze the first. An empty display of the right
     * size is reused; otherwise a new one is created. Returns its id, or -1.
     */
    fun freeDisplay(ctx: Context, landscape: Boolean, cfg: SizeCfg? = null, fps: Int = 0): Int {
        // Only reuse a display that already has exactly the size and refresh rate a new one would get now.
        val (w, h, dpi) = sizeFor(ctx, landscape, cfg)
        return displays.firstOrNull { it.w == w && it.h == h && it.landscape == landscape && (it.dpi == 0 || it.dpi == dpi) && it.fps == fps && tasks(it.id).isEmpty() }?.id
            ?: createDisplay(ctx, landscape, cfg, fps)
    }

    fun launch(ctx: Context, app: AppRef, display: Int): String {
        val comp = Clones.launchComponent(ctx, app) ?: return "No launcher activity for ${app.pkg}"
        // A clone lives in the clone space; it must be running and unlocked before anything starts in it.
        if (app.isClone && !CloneSpace.ensureRunning(app.user)) return "The clone space won't start."
        // Apps always start with sound on. Mute again from the app's menu if wanted.
        setMutedPref(ctx, app, false)
        applyAudio(app, false)
        markSession(ctx, app)
        clearCrash(ctx, app)
        clearAutoStop(ctx, app) // it is running again
        displays.firstOrNull { it.id == display }?.let { orientPrefs(ctx).edit().putBoolean(app.key, it.landscape).apply() }
        // If this copy is already open on the main screen, try to move that task instead of starting a copy.
        // Only a task of the same user counts, so launching a clone never takes the original off the main screen.
        val onMain = tasks(0).firstOrNull { it.app == app }
        if (onMain != null) {
            val r = exec("am display move-stack ${onMain.id} $display")
            if (!Regex("(?i)error|exception|unknown|usage").containsMatchIn(r)) return "Moved ${app.pkg} to display $display. $r"
            log("move-stack failed, starting fresh: $r")
        }
        // -f = NEW_TASK, plus EXCLUDE_FROM_RECENTS unless turned off in Settings. Android only reads
        // EXCLUDE_FROM_RECENTS when it creates or resets a task. If the app already has a leftover task
        // (still listed in Recents from an earlier run), the flag is ignored and the entry stays.
        // CLEAR_TASK resets that task so the flag takes effect on it too.
        val flags = if (AppSettings.hideFromRecents(ctx)) LAUNCH_FLAGS or 0x00800000 or 0x00008000 else LAUNCH_FLAGS // EXCLUDE_FROM_RECENTS | CLEAR_TASK
        val started = exec("am start --user ${app.user} --display $display -f $flags -n $comp")
        return started
    }

    /**
     * False only when Android says outright that this phone cannot run apps on secondary displays
     * (low-RAM / Go builds, for example). Then launches always end up on the main screen.
     */
    fun supportsSecondaryDisplays(): Boolean =
        exec("cmd package has-feature android.software.activities_on_secondary_displays").trim() != "false"

    // ---- unexpected exits ----

    /** An app PocketScreen launched whose process died without us stopping it. */
    data class Crash(val app: AppRef, val whenMs: Long, val reason: String, val landscape: Boolean)

    private fun crashPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("crashes", Context.MODE_PRIVATE)
    private fun orientPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("orient", Context.MODE_PRIVATE)

    /** The orientation this copy last ran in, or null if it never ran here. */
    fun lastLandscape(ctx: Context, app: AppRef): Boolean? =
        orientPrefs(ctx).takeIf { it.contains(app.key) }?.getBoolean(app.key, false)

    fun crashes(ctx: Context): List<Crash> = crashPrefs(ctx).all.mapNotNull { (key, v) ->
        val p = (v as? String)?.split("|", limit = 3) ?: return@mapNotNull null
        val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        Crash(AppRef.fromKey(key), t, p.getOrNull(2).orEmpty(), p.getOrNull(1) == "1")
    }.sortedByDescending { it.whenMs }

    // ---- apps stopped by an auto-stop limit ----

    /** [kind] is "Temperature", "Battery" or "Timer"; [ranMin] is how long the app had been running. */
    data class AutoStop(val app: AppRef, val whenMs: Long, val ranMin: Long, val kind: String, val detail: String, val landscape: Boolean)

    private fun stopPrefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("autostops", Context.MODE_PRIVATE)

    fun recordAutoStop(ctx: Context, app: AppRef, kind: String, detail: String) {
        val ran = sessionMinutes(ctx, app) ?: 0L
        val land = orientPrefs(ctx).getBoolean(app.key, false)
        stopPrefs(ctx).edit().putString(app.key, listOf(System.currentTimeMillis(), ran, if (land) 1 else 0, kind, detail).joinToString("|")).apply()
    }

    /** Kept until the user dismisses or relaunches the app. */
    fun autoStops(ctx: Context): List<AutoStop> = stopPrefs(ctx).all.mapNotNull { (key, v) ->
        val p = (v as? String)?.split("|", limit = 5) ?: return@mapNotNull null
        val t = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
        AutoStop(AppRef.fromKey(key), t, p.getOrNull(1)?.toLongOrNull() ?: 0L, p.getOrNull(3).orEmpty(), p.getOrNull(4).orEmpty(), p.getOrNull(2) == "1")
    }.sortedByDescending { it.whenMs }

    fun clearAutoStop(ctx: Context, app: AppRef) { stopPrefs(ctx).edit().remove(app.key).apply() }

    fun clearCrash(ctx: Context, app: AppRef) {
        crashPrefs(ctx).edit().remove(app.key).apply()
        HostService.cancelCrash(ctx, app) // the notification goes away with the entry
    }

    /**
     * Why the system says this copy's last process ended, e.g. "CRASH" or "EXIT_SELF". Null if it can't be read.
     * The exit list is by package name, so an entry only counts when it shows the same user. An entry that shows
     * no user is used only for an original that has no clone, where nothing can be mixed up.
     */
    private fun lastExitReason(ctx: Context, app: AppRef): String? {
        val entries = CloneSpace.parseExitInfo(exec("dumpsys activity exit-info ${app.pkg}"))
        val loose = !app.isClone && Clones.forPkg(ctx, app.pkg) == null
        return entries.firstOrNull { it.first == app.user || (it.first == null && loose) }?.second
    }

    private val EXPECTED_EXITS = setOf("EXIT_SELF", "USER_REQUESTED", "USER_STOPPED", "PACKAGE_UPDATED", "PACKAGE_STATE_CHANGE", "PERMISSION_CHANGE")

    /**
     * Looks for apps we launched that are no longer on any hidden display, whose process is gone, and
     * that we did not stop ourselves. Those are recorded as unexpected exits. Returns the new ones.
     * [onDisplays] are the apps currently on a hidden display, [busy] are being launched right now.
     */
    fun checkCrashes(ctx: Context, onDisplays: Set<AppRef>, busy: Set<AppRef>): List<Crash> {
        if (service == null) return emptyList()
        val found = ArrayList<Crash>()
        val now = System.currentTimeMillis()
        // An app that is running again (for example opened from the app drawer) is no longer "closed".
        for (c in crashes(ctx)) {
            if (isRunning(c.app) == true) clearCrash(ctx, c.app)
        }
        // Only listed when an app we started is missing from the hidden displays.
        val onMain by lazy { tasks(0).map { it.app }.toSet() }
        for ((key, v) in sessionPrefs(ctx).all) {
            val started = v as? Long ?: continue
            val app = AppRef.fromKey(key)
            if (app in onDisplays || app in busy || now - started < 10_000L) continue
            if (app in onMain) { // the user moved it to the main screen: a hand-off, not a crash
                clearSession(ctx, app)
                restoreAudio(ctx, app)
                clearCrash(ctx, app)
                continue
            }
            val alive = isRunning(app) ?: continue
            if (alive) continue // still alive, just not on a display
            val reason = lastExitReason(ctx, app)
            clearSession(ctx, app)
            restoreAudio(ctx, app)
            if (reason != null && reason in EXPECTED_EXITS) continue
            val c = Crash(app, now, reason?.lowercase()?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Reason unknown",
                orientPrefs(ctx).getBoolean(app.key, false))
            crashPrefs(ctx).edit().putString(app.key, "${c.whenMs}|${if (c.landscape) 1 else 0}|${c.reason}").apply()
            log("Unexpected exit: ${app.key} (${c.reason})")
            found.add(c)
        }
        return found
    }

    /** Which display this copy's task is on, according to the system, or null if no task is found. */
    fun displayOfTask(app: AppRef): Int? {
        val pkg = CloneSpace.safePkgs(listOf(app.pkg)).firstOrNull() ?: return null
        val out = exec("dumpsys activity activities | grep -E 'Display #|mDisplayId=|Task\\{|ActivityRecord\\{' | grep -E 'Display #|$pkg'")
        return CloneSpace.parseDisplayOf(out, pkg, app.user)
    }

    /** Facts about this phone that decide whether apps can run on a hidden display. For the debug log. */
    fun diagnose(app: AppRef, display: Int): String {
        fun sh(c: String) = exec(c).trim().lines().take(12).joinToString("\n")
        return buildString {
            appendLine("--- Why did ${app.key} not start on display $display? ---")
            appendLine("model: " + sh("getprop ro.product.model") + ", android " + sh("getprop ro.build.version.release") + " (sdk " + sh("getprop ro.build.version.sdk") + ")")
            appendLine("low_ram: " + sh("getprop ro.config.low_ram"))
            appendLine("secondary-display feature: " + sh("cmd package has-feature android.software.activities_on_secondary_displays"))
            appendLine("freeform feature: " + sh("cmd package has-feature android.software.freeform_window_management"))
            appendLine("picture-in-picture feature: " + sh("cmd package has-feature android.software.picture_in_picture"))
            appendLine("force_resizable: " + sh("settings get global force_resizable_activities"))
            appendLine("freeform setting: " + sh("settings get global enable_freeform_support"))
            appendLine("non-resizable multi-window: " + sh("settings get global enable_non_resizable_multi_window"))
            appendLine("task display: " + displayOfTask(app))
        }
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
        tasks(id).map { it.app }.distinct().forEach { stopApp(ctx, it) }
        releaseOnly(ctx, id)
    }

    fun stopAll(ctx: Context) {
        displays.map { it.id }.forEach { destroyDisplay(ctx, it) }
        displays = emptyList()
        stopHostIfIdle(ctx)
        changed()
    }
}
