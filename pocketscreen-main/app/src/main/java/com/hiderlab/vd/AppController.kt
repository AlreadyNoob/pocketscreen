package com.hiderlab.vd

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the main screen does that is not drawing: Shizuku/service state, the list of running
 * apps and displays, launching, stopping, muting, crash and auto-stop bookkeeping, the periodic
 * refresh, and update checks. It holds no views and no text meant for the user.
 *
 * Usage from a screen:
 *  1. Create it with the application context, set the callbacks you need.
 *  2. Call [attach] once (onCreate) and [detach] once (onDestroy).
 *  3. Call [start] when the screen becomes visible (onResume) and [stop] when it leaves (onPause).
 *  4. Draw whatever you like from the data the callbacks deliver, and call the action functions.
 *
 * Call every public function on the main thread. Every callback runs on the main thread.
 * See FRONTEND_API.md for the full list.
 */
class AppController(private val app: Context) {

    // ------------------------------------------------------------------ data

    /** One app running on one hidden display. [taskId] is the system task id (needed to move it). */
    data class Row(val display: Vd.Disp, val taskId: Int, val pkg: String)

    /** Whether Shizuku and the background service are usable. Delivered on every [refresh]. */
    data class Status(
        val shizukuRunning: Boolean,
        val permitted: Boolean,
        val serviceConnected: Boolean,
        val displayCount: Int
    )

    /**
     * Everything that is running right now.
     * [rows] are apps on displays; [emptyDisplays] are displays with no app on them;
     * [crashes] / [autoStops] are apps that ended on their own and have not been dismissed yet
     * (they can also appear for an app that is currently running again; filter on [rows] if needed);
     * [stats] is memory/CPU by package (empty until the slow readout finishes, or when turned off).
     */
    data class Snapshot(
        val rows: List<Row>,
        val displays: List<Vd.Disp>,
        val emptyDisplays: List<Vd.Disp>,
        val crashes: List<Vd.Crash>,
        val autoStops: List<Vd.AutoStop>,
        val stats: Map<String, Vd.Stat>
    ) {
        val runningAppCount: Int get() = rows.map { it.pkg }.distinct().size
        /** True when more than one app shares this row's display. */
        fun sharesDisplay(r: Row): Boolean = rows.count { it.display.id == r.display.id } > 1
    }

    /** Things that happened during a launch that the screen may want to tell the user about. */
    sealed class Event {
        data class Launching(val pkg: String) : Event()
        data class AlreadyLaunching(val pkg: String) : Event()
        data class AlreadyRunning(val pkg: String) : Event()
        /** Running, but size / refresh rate / orientation now differ. Ask, then call [relaunchWithNewSettings]. */
        data class NeedsRelaunchConfirm(val pkg: String, val landscape: Boolean, val changes: List<Change>) : Event()
        /** This phone cannot run apps on extra displays. Nothing was launched. */
        object Unsupported : Event()
        /** Launched, but the app opened on the main screen instead of the hidden display. */
        data class NotOnHiddenDisplay(val pkg: String) : Event()
    }

    enum class Change { SIZE, REFRESH_RATE, ORIENTATION }

    /** Allowed ranges for user-entered numbers. */
    object Limits {
        const val WIDTH_MIN = 240
        const val WIDTH_MAX = 2160
        const val HEIGHT_MIN = 240
        const val HEIGHT_MAX = 3840
        const val DPI_MIN = 120
        const val DPI_MAX = 640
        const val BATTERY_MIN = 1
        const val BATTERY_MAX = 99
        const val TEMP_MIN = 30
        const val TEMP_MAX = 60
        const val MINUTES_MIN = 1
        const val MINUTES_MAX = 1440
        const val HZ_MIN = 1
        val HZ_MAX: Int get() = AppSettings.MAX_HZ
    }

    enum class SizeProblem { WIDTH, HEIGHT, DPI }

    // ------------------------------------------------------------------ callbacks

    /** Fires on every [refresh]; also the moment to re-read quick-launch entries ([Vd.presets]). */
    var onStatus: ((Status) -> Unit)? = null
    /** Fires whenever the running list changes (including after the slow memory/CPU readout). */
    var onSnapshot: ((Snapshot) -> Unit)? = null
    var onEvent: ((Event) -> Unit)? = null
    /** A newer release exists (from the automatic check that runs on [start]). */
    var onUpdateAvailable: ((Updater.Release) -> Unit)? = null

    val versionName: String by lazy {
        try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
    }

    // ------------------------------------------------------------------ internals

    private val main = Handler(Looper.getMainLooper())
    private var attached = false
    private var started = false

    private val launching: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val emptySince = HashMap<Int, Long>()
    // Rows the user just removed stay hidden for a few seconds, until the system catches up.
    private val hidingPkgs = HashMap<String, Long>()
    private val hidingDisplays = HashMap<Int, Long>()
    @Volatile private var statsCache: Map<String, Vd.Stat> = emptyMap()
    private var lastRows: List<Row> = emptyList()
    private var lastDisp: List<Vd.Disp> = emptyList()
    private var lastCrashes: List<Vd.Crash> = emptyList()
    private var lastStops: List<Vd.AutoStop> = emptyList()

    private val refreshRunnable = Runnable { main.post { refresh() } }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("Permission: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
        main.post { refresh() }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { main.post { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { main.post { refresh() } }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Apps.load(app, force = true) }
    }

    // Keeps running time and time left fresh while the screen is open.
    private val tick = object : Runnable {
        override fun run() {
            refreshTasks()
            main.postDelayed(this, 30_000)
        }
    }

    /**
     * Any change to the settings, a quick launch entry or an app's size triggers a refresh after a
     * moment, so whatever is on screen never shows old values.
     */
    private val watchedPrefs = listOf("settings", "presets", "display", "app_size")
    private var lastAutoRefresh = 0L
    private val refreshSoon = Runnable {
        if (!attached) return@Runnable
        lastAutoRefresh = System.currentTimeMillis()
        refresh()
        refreshTasks()
    }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Not again right after a refresh, in case a refresh itself saves something.
        if (System.currentTimeMillis() - lastAutoRefresh > 400L) {
            main.removeCallbacks(refreshSoon)
            main.postDelayed(refreshSoon, 150)
        }
    }

    private fun log(s: String) { Debug.write(s) }

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (t: Throwable) { log("ERR: $t") }
        }.start()
    }

    // ------------------------------------------------------------------ lifecycle

    /** Once, when the screen is created. */
    fun attach() {
        if (attached) return
        attached = true
        // Load the app list and icons in the background now, so a picker can open instantly later.
        Apps.load(app)
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(pkgReceiver, pf, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(pkgReceiver, pf)

        Debug.init(app)
        Vd.listeners.add(refreshRunnable)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
    }

    /** Once, when the screen is destroyed. */
    fun detach() {
        attached = false
        try { app.unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        Vd.listeners.remove(refreshRunnable)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
    }

    /** When the screen becomes visible. Refreshes now, then every 30 s, and on any settings change. */
    fun start() {
        if (started) return
        started = true
        refresh()
        for (n in watchedPrefs) app.getSharedPreferences(n, Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 30_000)
        Updater.autoCheck(app) { r -> main.post { if (attached) onUpdateAvailable?.invoke(r) } }
    }

    /** When the screen is no longer visible. */
    fun stop() {
        started = false
        main.removeCallbacks(tick)
        main.removeCallbacks(refreshSoon)
        for (n in watchedPrefs) app.getSharedPreferences(n, Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
    }

    // ------------------------------------------------------------------ state

    /** Re-reads Shizuku/service state, reports it through [onStatus], then refreshes the running list. */
    fun refresh() {
        val ping = Vd.shizukuRunning()
        val perm = Vd.permitted()
        if (perm && Vd.service == null) Vd.connect(app)
        val connected = Vd.service != null
        onStatus?.invoke(Status(ping, perm, connected, Vd.displays.size))
        refreshTasks()
    }

    /** Asks Shizuku for permission. The result arrives as a refresh. */
    fun grantShizukuPermission() {
        if (!Vd.shizukuRunning()) { log("Shizuku isn't running. Start it first."); return }
        Shizuku.requestPermission(100)
    }

    fun connectService() { Vd.connect(app, force = true) }

    /** Re-reads the running list (apps, displays, crashes, auto-stops) and delivers a [Snapshot]. */
    fun refreshTasks() {
        val disp = Vd.displays
        if (Vd.service == null) {
            lastRows = emptyList(); lastDisp = emptyList(); lastCrashes = Vd.crashes(app); lastStops = Vd.autoStops(app)
            publish()
            return
        }
        bg {
            val rows = disp.flatMap { d -> Vd.tasks(d.id).map { Row(d, it.first, it.second) } }
            // Note apps that died on their own, and tell the user.
            for (c in Vd.checkCrashes(app, rows.map { it.pkg }.toSet(), launching.toSet())) {
                HostService.notifyCrash(app, c)
            }
            val crashes = Vd.crashes(app)
            val stops = Vd.autoStops(app)
            // Auto-clean displays that have had nothing on them for a while.
            val now = System.currentTimeMillis()
            var cleaned = false
            for (d in disp) {
                if (rows.any { it.display.id == d.id }) { emptySince.remove(d.id); continue }
                val since = emptySince.getOrPut(d.id) { now }
                if (now - since > 30_000L) {
                    emptySince.remove(d.id)
                    log("Auto-removed unused display #${d.id}")
                    Vd.releaseOnly(app, d.id)
                    cleaned = true
                }
            }
            // Deliver the rows right away with the last known numbers; the slow memory/CPU readout follows.
            main.post {
                lastRows = rows; lastDisp = disp; lastCrashes = crashes; lastStops = stops
                publish()
                if (cleaned) refresh()
            }
            if (AppSettings.showStats(app) && rows.isNotEmpty()) {
                val stats = Vd.appStats(rows.map { it.pkg }.distinct())
                statsCache = stats
                main.post { publish() }
            }
        }
    }

    private fun publish() {
        val now = System.currentTimeMillis()
        hidingPkgs.entries.removeAll { now - it.value > 5000L }
        hidingDisplays.entries.removeAll { now - it.value > 5000L }
        val rows = lastRows.filter { it.pkg !in hidingPkgs && it.display.id !in hidingDisplays }
        val disp = lastDisp.filter { it.id !in hidingDisplays }
        val empty = disp.filter { d -> lastRows.none { it.display.id == d.id } }
        onSnapshot?.invoke(Snapshot(rows, disp, empty, lastCrashes, lastStops, statsCache))
    }

    private fun hidePkg(pkg: String) { hidingPkgs[pkg] = System.currentTimeMillis(); publish() }
    private fun hideDisplay(id: Int) { hidingDisplays[id] = System.currentTimeMillis(); publish() }

    // ------------------------------------------------------------------ launching

    /**
     * Starts [pkg] on a hidden display. Reports progress and problems through [onEvent].
     * A second call while the first is still starting is ignored ([Event.AlreadyLaunching]).
     */
    fun launchApp(pkg: String, landscape: Boolean) {
        if (!launching.add(pkg)) {
            onEvent?.invoke(Event.AlreadyLaunching(pkg))
            return
        }
        hidingPkgs.remove(pkg)
        bg {
            try {
                // Already running on a hidden display: don't start anything.
                val runningOn = Vd.displayOf(pkg)
                if (runningOn != null) {
                    // Size and refresh rate only apply when a display is created. If those settings have
                    // changed since this one was, offer a relaunch; otherwise there is nothing to apply.
                    val changes = changedSettings(pkg, landscape, Vd.display(runningOn))
                    main.post {
                        if (changes.isEmpty()) onEvent?.invoke(Event.AlreadyRunning(pkg))
                        else onEvent?.invoke(Event.NeedsRelaunchConfirm(pkg, landscape, changes))
                    }
                    return@bg
                }
                if (!Vd.supportsSecondaryDisplays()) {
                    log("Blocked launch of $pkg: this phone reports no support for apps on secondary displays.")
                    main.post { onEvent?.invoke(Event.Unsupported) }
                    return@bg
                }
                main.post { onEvent?.invoke(Event.Launching(pkg)) }
                val id = Vd.freeDisplay(app, landscape, Vd.appCfg(app, pkg), Vd.refreshFor(app, pkg))
                if (id >= 0) {
                    log("Launch $pkg on #$id:\n" + Vd.launch(app, pkg, id))
                    // Refresh twice so the app shows up as soon as it exists.
                    Thread.sleep(600)
                    main.post { refresh() }
                    Thread.sleep(900)
                    // Some phones accept the launch but open the app on the main screen instead.
                    if (Vd.tasks(id).none { it.second == pkg }) {
                        log(Vd.diagnose(pkg, id))
                        main.post { onEvent?.invoke(Event.NotOnHiddenDisplay(pkg)) }
                    }
                    // Once the process exists, set its sound state again.
                    Vd.syncAudio(app, pkg)
                    main.post { refresh() }
                }
            } finally {
                launching.remove(pkg)
            }
        }
    }

    /** What a new launch of [pkg] would get that the running display doesn't have. Empty = nothing differs. */
    private fun changedSettings(pkg: String, landscape: Boolean, d: Vd.Disp?): List<Change> {
        if (d == null) return emptyList()
        val out = ArrayList<Change>()
        val (w, h, dpi) = Vd.sizeFor(app, d.landscape, Vd.appCfg(app, pkg))
        if (d.w != w || d.h != h || (d.dpi > 0 && d.dpi != dpi)) out.add(Change.SIZE)
        if (d.fps != Vd.refreshFor(app, pkg)) out.add(Change.REFRESH_RATE)
        if (d.landscape != landscape) out.add(Change.ORIENTATION)
        return out
    }

    /** After [Event.NeedsRelaunchConfirm] and the user agreed: stop the app, then launch it again. */
    fun relaunchWithNewSettings(pkg: String, landscape: Boolean) {
        bg {
            Vd.stopAppAndDisplay(app, pkg)
            main.post { launchApp(pkg, landscape) }
        }
    }

    fun relaunchCrashed(c: Vd.Crash) {
        Vd.clearCrash(app, c.pkg) // also removes the notification
        lastCrashes = lastCrashes.filter { it.pkg != c.pkg }
        launchApp(c.pkg, c.landscape)
        publish()
    }

    fun relaunchAutoStopped(a: Vd.AutoStop) {
        Vd.clearAutoStop(app, a.pkg)
        lastStops = lastStops.filter { it.pkg != a.pkg }
        launchApp(a.pkg, a.landscape)
        publish()
    }

    fun dismissCrash(pkg: String) { Vd.clearCrash(app, pkg); lastCrashes = Vd.crashes(app); publish() }
    fun dismissAutoStop(pkg: String) { Vd.clearAutoStop(app, pkg); lastStops = Vd.autoStops(app); publish() }

    // ------------------------------------------------------------------ actions on running things

    /** Closes the app and removes its display. The row disappears at once. */
    fun stopApp(pkg: String) {
        hidePkg(pkg)
        bg {
            log(Vd.stopAppAndDisplay(app, pkg))
            main.post { refresh() }
        }
    }

    /** Flips mute for [pkg] (sound state is applied on the display). */
    fun toggleMute(pkg: String) {
        val muted = Vd.isMuted(app, pkg)
        bg {
            log("${if (muted) "Unmute" else "Mute"} ${Apps.label(app, pkg)}:\n" + Vd.setMuted(app, pkg, !muted))
            main.post { refreshTasks() }
        }
    }

    /** Gives sound back to every muted app. */
    fun restoreAllSound() {
        bg {
            log(Vd.restoreAllAudio(app))
            main.post { refreshTasks() }
        }
    }

    /** Moves an app that shares a display onto a new display of its own. */
    fun moveToOwnDisplay(r: Row) {
        bg {
            val id = Vd.createDisplay(app, r.display.landscape, Vd.appCfg(app, r.pkg), Vd.refreshFor(app, r.pkg))
            if (id >= 0) log("Move ${r.pkg} to #$id:\n" + Vd.exec("am display move-stack ${r.taskId} $id"))
            Thread.sleep(600)
            main.post { refresh() }
        }
    }

    /** Removes an empty display. The row disappears at once. */
    fun destroyDisplay(id: Int) {
        hideDisplay(id)
        bg { Vd.destroyDisplay(app, id) }
    }

    /** Closes every app and removes every hidden display. */
    fun stopAll() { bg { Vd.stopAll(app) } }

    /** Call after changing a "stop at time" setting so the alarm is rescheduled and the list redraws. */
    fun clockLimitChanged() { Vd.refreshDeadlines(app); refreshTasks() }

    // ------------------------------------------------------------------ settings helpers

    /** null when [c] is usable, otherwise which field is out of range. Height is ignored when [Vd.SizeCfg.matchRatio]. */
    fun validateSize(c: Vd.SizeCfg): SizeProblem? {
        if (c.width < Limits.WIDTH_MIN || c.width > Limits.WIDTH_MAX) return SizeProblem.WIDTH
        if (!c.matchRatio && (c.customH < Limits.HEIGHT_MIN || c.customH > Limits.HEIGHT_MAX)) return SizeProblem.HEIGHT
        if (c.dpi < Limits.DPI_MIN || c.dpi > Limits.DPI_MAX) return SizeProblem.DPI
        return null
    }

    /** Checks GitHub now. [done] runs on the main thread. */
    fun checkForUpdates(done: (Updater.Result) -> Unit) {
        bg {
            val r = Updater.check(app)
            main.post { done(r) }
        }
    }

    /** The saved debug log as text (empty when there is none). */
    fun debugLogText(): String = Debug.text(app)
}
