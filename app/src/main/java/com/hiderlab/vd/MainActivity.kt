package com.hiderlab.vd

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.Outline
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var chipsRow: LinearLayout
    private lateinit var setupCard: LinearLayout
    private lateinit var helperView: TextView
    private lateinit var btnGrant: View
    private lateinit var btnConnect: View
    private lateinit var btnLaunch: View
    private lateinit var presetsHeader: LinearLayout
    private lateinit var presetsBox: LinearLayout
    private lateinit var moreBtn: IconView
    private lateinit var tasksBox: LinearLayout

    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Throwable) { "" }
    }

    private val refreshRunnable = Runnable { runOnUiThread { refresh() } }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("Permission: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
        runOnUiThread { refresh() }
    }
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { Apps.load(applicationContext, force = true) }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = Ui.dp(this, v)

    // ---------------------------------------------------------------- layout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this)
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))

        val frame = FrameLayout(this)
        frame.setBackgroundColor(Ui.BG)
        Ui.applyInsets(frame)

        val scroll = ScrollView(this)
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        scroll.overScrollMode = View.OVER_SCROLL_NEVER

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(10), dp(20), dp(130)) // bottom room for the bottom bar
        scroll.addView(root)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        Ui.styleSystemBars(this)

        // header: large title, overflow menu, status chips
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val logo = LogoView(this, 44)
        val logoLp = LinearLayout.LayoutParams(-2, -2)
        logoLp.rightMargin = dp(14)
        head.addView(logo, logoLp)
        val brand = LinearLayout(this)
        brand.orientation = LinearLayout.VERTICAL
        brand.addView(Ui.text(this, "PocketScreen", 26f, Ui.TEXT, bold = true))
        brand.addView(Ui.text(this, "Apps that stay awake", 12.5f, Ui.MUTED))
        head.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        Ui.add(root, head, top = 0)

        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        Ui.add(root, chipsRow, top = 18)

        // setup card: only shown while something still needs doing
        setupCard = Ui.card(this)
        setupCard.setPadding(dp(20), dp(20), dp(20), dp(20))
        setupCard.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 28f))
        setupCard.addView(Ui.text(this, "Finish setup", 15f, Ui.TEXT, bold = true))
        helperView = Ui.text(this, "", 12f, Ui.MUTED)
        helperView.setLineSpacing(0f, 1.15f)
        Ui.add(setupCard, helperView, top = 4)
        val setupBtns = LinearLayout(this)
        setupBtns.orientation = LinearLayout.HORIZONTAL
        btnGrant = Ui.smallButton(this, "Grant Shizuku permission", Ui.Kind.FILLED) { grant() }
        btnConnect = Ui.smallButton(this, "Connect service", Ui.Kind.TEXT) { Vd.connect(applicationContext, force = true) }
        setupBtns.addView(btnGrant, LinearLayout.LayoutParams(-2, -2))
        setupBtns.addView(btnConnect, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8) })
        Ui.add(setupCard, setupBtns, top = 12)
        Ui.add(root, setupCard, top = 16)

        // list fades out under the floating button
        val fade = View(this)
        fade.background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(Ui.BG, (Ui.BG and 0x00FFFFFF))
        )
        frame.addView(fade, FrameLayout.LayoutParams(-1, dp(150), Gravity.BOTTOM))

        // bottom bar: settings on the left, the big launch button in the middle, menu on the right
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(8), dp(8), dp(8), dp(8))
        bar.background = Ui.shape(Ui.SURFACE2, Ui.dpf(this, 38f))
        bar.elevation = Ui.dpf(this, 10f)
        fun barItem(kind: Int, label: String, desc: String, onClick: () -> Unit): Pair<LinearLayout, IconView> {
            val item = LinearLayout(this)
            item.orientation = LinearLayout.VERTICAL
            item.gravity = Gravity.CENTER_HORIZONTAL
            item.setPadding(dp(8), dp(6), dp(8), dp(6))
            val ic = IconView(this, kind, 28, false)
            ic.tint = Ui.MUTED
            item.addView(ic, LinearLayout.LayoutParams(dp(28), dp(28)))
            item.addView(Ui.text(this, label, 11f, Ui.MUTED, bold = true))
            item.background = Ui.withRipple(null, Ui.dpf(this, 26f))
            item.isClickable = true
            item.contentDescription = desc
            item.setOnClickListener { onClick() }
            return Pair(item, ic)
        }
        val settingsItem = barItem(IconView.SETTINGS, "Settings", "Settings") { showSettings() }
        val moreItem = barItem(IconView.MORE, "More", "More options") { showMainMenu() }
        moreBtn = moreItem.second
        btnLaunch = Ui.fab(this, "Launch") { pickApp() }
        bar.addView(settingsItem.first, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(btnLaunch, LinearLayout.LayoutParams(-2, -2))
        bar.addView(moreItem.first, LinearLayout.LayoutParams(0, -2, 1f))
        val fl = FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
        fl.setMargins(dp(16), 0, dp(16), dp(16))
        frame.addView(bar, fl)

        // running apps and the displays they use, in one list
        runningCount = Ui.text(this, "0 apps", 12f, Ui.MUTED)
        addSection(root, "Running now", runningCount)
        tasksBox = LinearLayout(this)
        tasksBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, tasksBox, top = 12)

        // quick launch
        presetsHeader = LinearLayout(this)
        presetsHeader.orientation = LinearLayout.VERTICAL
        presetsCount = Ui.text(this, "", 12f, Ui.MUTED)
        addSection(presetsHeader, "Quick launch", presetsCount)
        Ui.add(root, presetsHeader)
        presetsBox = LinearLayout(this)
        presetsBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, presetsBox, top = 12)

        // Load the app list and icons in the background now, so the picker opens instantly later.
        Apps.load(applicationContext)
        val pf = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(pkgReceiver, pf, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(pkgReceiver, pf)

        Debug.init(applicationContext)
        Vd.listeners.add(refreshRunnable)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)

        refresh()
        val intro = ArrayList<View>()
        for (i in 0 until root.childCount) intro.add(root.getChildAt(i))
        Ui.reveal(intro)
    }

    private lateinit var runningCount: TextView
    private lateinit var presetsCount: TextView

    private fun addSection(parent: LinearLayout, title: String, trailing: View?) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.sectionTitle(this, title), LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        Ui.add(parent, row, top = 26)
    }

    override fun onResume() {
        super.onResume()
        // Light/dark or wallpaper colors changed while we were away: rebuild with the new palette.
        if (Ui.themeChanged(this)) { recreate(); return }
        refresh()
        for (n in watchedPrefs) getSharedPreferences(n, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        tickHandler.removeCallbacks(tick)
        tickHandler.postDelayed(tick, 30_000)
        Updater.autoCheck(applicationContext) { r -> runOnUiThread { if (!isFinishing) showUpdate(r) } }
    }

    private fun showUpdate(r: Updater.Release) {
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, "Update available", 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "PocketScreen ${r.version} is out. You have $versionName.", 14f, Ui.MUTED), top = 4)
            val notes = r.notes.trim().lines().filter { it.isNotBlank() }.take(12).joinToString("\n")
            if (notes.isNotEmpty()) Ui.add(body, Ui.text(this, notes, 13f, Ui.TEXT), top = 12)
            Ui.add(body, Ui.button(this, "Download", Ui.Kind.FILLED) {
                dlg.dismiss()
                try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(r.page))) }
                catch (_: Throwable) { Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show() }
            }, top = 16)
            Ui.add(body, Ui.button(this, "Later", Ui.Kind.TEXT) { dlg.dismiss() }, top = 4)
        }
    }

    private fun checkUpdatesNow() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        bg {
            val r = Updater.check(applicationContext)
            runOnUiThread {
                when (r) {
                    is Updater.Result.Newer -> showUpdate(r.release)
                    is Updater.Result.UpToDate -> Toast.makeText(this, "You're up to date", Toast.LENGTH_SHORT).show()
                    is Updater.Result.Failed -> Toast.makeText(this, "Couldn't check: ${r.why}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Keeps running time and time left fresh while the screen is open.
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshTasks()
            tickHandler.postDelayed(this, 30_000)
        }
    }

    /**
     * Any change to the settings, a quick launch entry or an app's size redraws the main screen after a
     * moment, so the lists never show old values (limits, sizes, refresh rate...).
     */
    private val watchedPrefs = listOf("settings", "presets", "display", "app_size")
    private var lastAutoRefresh = 0L
    private val refreshSoon = Runnable {
        if (isFinishing || isDestroyed) return@Runnable
        lastAutoRefresh = System.currentTimeMillis()
        refresh()
        refreshTasks()
    }
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        // Not again right after a refresh, in case a refresh itself saves something.
        if (System.currentTimeMillis() - lastAutoRefresh > 400L) {
            tickHandler.removeCallbacks(refreshSoon)
            tickHandler.postDelayed(refreshSoon, 150)
        }
    }

    override fun onPause() {
        tickHandler.removeCallbacks(tick)
        tickHandler.removeCallbacks(refreshSoon)
        for (n in watchedPrefs) getSharedPreferences(n, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    override fun onDestroy() {
        try { unregisterReceiver(pkgReceiver) } catch (_: Throwable) {}
        Vd.listeners.remove(refreshRunnable)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun log(s: String) { Debug.write(s) }

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (t: Throwable) { log("ERR: $t") }
        }.start()
    }

    // ---------------------------------------------------------------- state

    private fun refresh() {
        val ping = Vd.shizukuRunning()
        val perm = Vd.permitted()
        if (perm && Vd.service == null) Vd.connect(applicationContext)
        val connected = Vd.service != null
        val disp = Vd.displays

        chipsRow.removeAllViews()
        chipsRow.addView(heroCard(ping, perm, connected, disp.size), LinearLayout.LayoutParams(-1, -2))

        helperView.text = when {
            !ping -> "Shizuku isn't running. Start it from wireless debugging, then come back."
            !perm -> "Allow PocketScreen in Shizuku to continue."
            else -> "Connecting to the service…"
        }
        setupCard.visibility = if (connected) View.GONE else View.VISIBLE
        btnGrant.visibility = if (ping && !perm) View.VISIBLE else View.GONE
        btnConnect.visibility = if (perm && !connected) View.VISIBLE else View.GONE
        btnLaunch.visibility = if (connected) View.VISIBLE else View.GONE
        renderPresets()
        refreshTasks()
    }

    private var runningN = 0
    private var heroCount: TextView? = null
    private var heroLabel: TextView? = null

    private fun heroLabelText(n: Int) = if (n == 1) "app on a hidden display" else "apps on hidden displays"

    /** Dashboard card: the running count in big type, with the three status lines beside it. */
    private fun heroCard(ping: Boolean, perm: Boolean, connected: Boolean, displays: Int): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.HORIZONTAL
        card.gravity = Gravity.CENTER_VERTICAL
        card.setPadding(dp(24), dp(22), dp(22), dp(22))
        card.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 32f))
        val left = LinearLayout(this)
        left.orientation = LinearLayout.VERTICAL
        val cap = Ui.text(this, "RUNNING", 11f, Ui.ON_ACCENT_CONT, bold = true)
        cap.letterSpacing = 0.12f
        cap.alpha = 0.75f
        left.addView(cap)
        val count = Ui.text(this, runningN.toString(), 52f, Ui.ON_ACCENT_CONT, bold = true)
        heroCount = count
        left.addView(count)
        val lab = Ui.text(this, heroLabelText(runningN), 12.5f, Ui.ON_ACCENT_CONT)
        lab.alpha = 0.8f
        heroLabel = lab
        left.addView(lab)
        card.addView(left, LinearLayout.LayoutParams(0, -2, 1f))
        val right = LinearLayout(this)
        right.orientation = LinearLayout.VERTICAL
        fun line(color: Int, text: String, top: Int) {
            val r = LinearLayout(this)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.addView(Ui.dot(this, color, 8), LinearLayout.LayoutParams(dp(8), dp(8)).also { it.rightMargin = dp(8) })
            r.addView(Ui.text(this, text, 13f, Ui.ON_ACCENT_CONT, bold = true))
            Ui.add(right, r, top = top, w = ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        line(if (!ping) Ui.BAD else if (!perm) Ui.WARN else Ui.OK,
            "Shizuku " + (if (!ping) "stopped" else if (!perm) "no access" else "running"), 0)
        line(if (connected) Ui.OK else if (perm) Ui.WARN else Ui.BAD,
            "Service " + (if (connected) "connected" else if (perm) "connecting" else "offline"), 10)
        line(Ui.ON_ACCENT_CONT, "$displays " + (if (displays == 1) "display" else "displays"), 10)
        card.addView(right, LinearLayout.LayoutParams(-2, -2))
        return card
    }

    private fun grant() {
        if (!Vd.shizukuRunning()) { log("Shizuku isn't running. Start it first."); return }
        Shizuku.requestPermission(100)
    }

    private fun emptyNote(text: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(30), dp(20), dp(30))
        val title = Ui.text(this, "Nothing running", 14f, Ui.TEXT, bold = true)
        title.gravity = Gravity.CENTER
        Ui.add(box, title)
        val body = Ui.text(this, text, 12.5f, Ui.MUTED)
        body.gravity = Gravity.CENTER
        body.setLineSpacing(0f, 1.2f)
        Ui.add(box, body, top = 4)
        val dash = android.graphics.drawable.GradientDrawable()
        dash.cornerRadius = Ui.dpf(this, 24f)
        dash.setStroke(Ui.dp(this, 2), Ui.OUTLINE, Ui.dpf(this, 6f), Ui.dpf(this, 5f))
        box.background = dash
        return box
    }

    /** App icon with rounded corners. */
    private fun appIcon(pkg: String): ImageView {
        val icon = ImageView(this)
        icon.tag = pkg
        icon.clipToOutline = true
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.32f)
            }
        }
        val cached = Apps.icon(this, pkg) { b -> if (icon.tag == pkg) icon.setImageBitmap(b) }
        if (cached != null) icon.setImageBitmap(cached)
        return icon
    }

    /** Round-square three-dot button, same look as the header buttons. */
    private fun moreButton(): IconView {
        val b = IconView(this, IconView.MORE, 40)
        b.contentDescription = "More options"
        b.tint = Ui.MUTED
        b.background = Ui.circleRipple()
        return b
    }

    private fun listRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = 0
        row.setPadding(dp(16), dp(16), dp(12), dp(16))
        return row
    }

    private fun openViewer(id: Int) {
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("display", id))
    }

    // ---------------------------------------------------------------- displays

    private fun renderPresets() {
        presetsBox.removeAllViews()
        val list = Vd.presets(this)
        val show = list.isNotEmpty() && Vd.service != null
        presetsHeader.visibility = if (show) View.VISIBLE else View.GONE
        presetsBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        presetsCount.text = "${list.size} saved"
        val tiles = ArrayList<View>()
        for ((pkg, landscape) in list) {
            val cfg = Vd.appCfg(this, pkg)
            val sz = Vd.sizeFor(this, landscape, cfg)
            val tile = FrameLayout(this)
            tile.background = Ui.withRipple(Ui.shape(Ui.SURFACE, Ui.dpf(this, 28f)), Ui.dpf(this, 28f))
            tile.isClickable = true
            tile.isFocusable = true
            tile.setOnClickListener { launchApp(pkg, landscape) } // one tap: start it now
            Ui.pressable(tile, 0.97f)
            val inner = LinearLayout(this)
            inner.orientation = LinearLayout.VERTICAL
            inner.setPadding(dp(18), dp(18), dp(18), dp(18))
            inner.addView(appIcon(pkg), LinearLayout.LayoutParams(dp(56), dp(56)))
            val name = Ui.text(this, Apps.label(this, pkg), 15f, Ui.TEXT, bold = true)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            Ui.add(inner, name, top = 14)
            inner.addView(Ui.text(this, "${if (landscape) "Landscape" else "Portrait"} · ${sz.first}×${sz.second}", 11.5f, Ui.MUTED)
                .also { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END })
            val meta = LinearLayout(this)
            meta.orientation = LinearLayout.HORIZONTAL
            meta.gravity = Gravity.CENTER_VERTICAL
            meta.addView(Ui.text(this, "${sz.third} DPI", 11.5f, Ui.MUTED))
            if (cfg != null) {
                val custom = Ui.pill(this, "Custom", Ui.ACCENT, Ui.ACCENT_CONT, 10f)
                custom.setPadding(dp(8), dp(2), dp(8), dp(2))
                meta.addView(custom, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8) })
            }
            Ui.add(inner, meta, top = 4)
            tile.addView(inner, FrameLayout.LayoutParams(-1, -2))
            val more = moreButton()
            more.setOnClickListener {
                Ui.menu(this, more, listOf(
                    Ui.MenuItem("App Settings") { showAppSettings(pkg, landscape) },
                    Ui.MenuItem("Remove", danger = true) { Vd.removePreset(this, pkg); renderPresets() }
                ))
            }
            tile.addView(more, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).also {
                it.topMargin = dp(8); it.rightMargin = dp(8)
            })
            tiles.add(tile)
        }
        var k = 0
        while (k < tiles.size) {
            val pair = LinearLayout(this)
            pair.orientation = LinearLayout.HORIZONTAL
            pair.addView(tiles[k], LinearLayout.LayoutParams(0, -1, 1f))
            val second: View = if (k + 1 < tiles.size) tiles[k + 1] else View(this)
            pair.addView(second, LinearLayout.LayoutParams(0, -1, 1f).also { it.leftMargin = dp(12) })
            Ui.add(presetsBox, pair, top = if (k == 0) 0 else 12)
            k += 2
        }
    }

    private data class Row(val display: Vd.Disp, val taskId: Int, val pkg: String)

    private val emptySince = HashMap<Int, Long>()

    // Rows the user just removed stay hidden for a few seconds, until the system catches up.
    private val hidingPkgs = HashMap<String, Long>()
    private val hidingDisplays = HashMap<Int, Long>()
    private val launching: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var statsCache: Map<String, Vd.Stat> = emptyMap()
    private var lastRows: List<Row> = emptyList()
    private var lastDisp: List<Vd.Disp> = emptyList()
    private var lastCrashes: List<Vd.Crash> = emptyList()
    private var lastStops: List<Vd.AutoStop> = emptyList()

    private fun hidePkg(pkg: String) { hidingPkgs[pkg] = System.currentTimeMillis(); rerender() }
    private fun hideDisplay(id: Int) { hidingDisplays[id] = System.currentTimeMillis(); rerender() }
    private fun rerender() = renderTasks(lastRows, lastDisp, statsCache, lastCrashes, lastStops)

    private fun refreshTasks() {
        val disp = Vd.displays
        if (Vd.service == null) {
            lastRows = emptyList(); lastDisp = emptyList(); lastCrashes = Vd.crashes(this); lastStops = Vd.autoStops(this)
            rerender()
            return
        }
        bg {
            val rows = disp.flatMap { d -> Vd.tasks(d.id).map { Row(d, it.first, it.second) } }
            // Note apps that died on their own, and tell the user.
            for (c in Vd.checkCrashes(applicationContext, rows.map { it.pkg }.toSet(), launching.toSet())) {
                HostService.notifyCrash(applicationContext, c)
            }
            val crashes = Vd.crashes(applicationContext)
            val stops = Vd.autoStops(applicationContext)
            // Auto-clean displays that have had nothing on them for a while.
            val now = System.currentTimeMillis()
            var cleaned = false
            for (d in disp) {
                if (rows.any { it.display.id == d.id }) { emptySince.remove(d.id); continue }
                val since = emptySince.getOrPut(d.id) { now }
                if (now - since > 30_000L) {
                    emptySince.remove(d.id)
                    log("Auto-removed unused display #${d.id}")
                    Vd.releaseOnly(applicationContext, d.id)
                    cleaned = true
                }
            }
            // Show the rows right away with the last known numbers; the slow memory/CPU readout follows.
            runOnUiThread {
                lastRows = rows; lastDisp = disp; lastCrashes = crashes; lastStops = stops
                rerender()
                if (cleaned) refresh()
            }
            if (AppSettings.showStats(applicationContext) && rows.isNotEmpty()) {
                val stats = Vd.appStats(rows.map { it.pkg }.distinct())
                statsCache = stats
                runOnUiThread { rerender() }
            }
        }
    }

    private fun relaunch(c: Vd.Crash) {
        Vd.clearCrash(this, c.pkg) // also removes the notification
        lastCrashes = lastCrashes.filter { it.pkg != c.pkg }
        launchApp(c.pkg, c.landscape)
        rerender()
    }

    private fun relaunchStopped(a: Vd.AutoStop) {
        Vd.clearAutoStop(this, a.pkg)
        lastStops = lastStops.filter { it.pkg != a.pkg }
        launchApp(a.pkg, a.landscape)
        rerender()
    }

    /** "3:42 AM", or "Nov 11, 3:42 AM" when it wasn't today. */
    private fun stopTime(whenMs: Long): String {
        val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US)
        val clock = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(whenMs))
        return if (today.format(java.util.Date(whenMs)) == today.format(java.util.Date())) clock
        else java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(whenMs)) + ", " + clock
    }

    /** An app an auto-stop limit closed. It stays until dismissed or relaunched. */
    private fun autoStopRow(a: Vd.AutoStop): View {
        val row = listRow()
        row.setOnClickListener { relaunchStopped(a) }
        row.addView(appIcon(a.pkg), LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = Ui.text(this, Apps.label(this, a.pkg), 16f, Ui.TEXT, bold = true)
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        col.addView(Ui.text(this, "Auto-stopped · ${a.kind}", 12f, Ui.WARN))
        if (a.detail.isNotBlank()) col.addView(Ui.text(this, a.detail, 12f, Ui.MUTED))
        col.addView(Ui.text(this, "Ran for ${Vd.fmtMinutes(a.ranMin)} · Stopped at ${stopTime(a.whenMs)}", 12f, Ui.MUTED))
        val btn = Ui.text(this, "Relaunch", 13f, Ui.ON_ACCENT_CONT, bold = true)
        btn.setPadding(dp(14), dp(7), dp(14), dp(7))
        btn.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 12f))
        btn.setOnClickListener { relaunchStopped(a) }
        Ui.add(col, btn, top = 8, w = ViewGroup.LayoutParams.WRAP_CONTENT)
        val cp = LinearLayout.LayoutParams(0, -2, 1f)
        cp.leftMargin = dp(14)
        row.addView(col, cp)
        val more = moreButton()
        more.setOnClickListener {
            Ui.menu(this, more, listOf(
                Ui.MenuItem("Relaunch") { relaunchStopped(a) },
                Ui.MenuItem("Dismiss") { Vd.clearAutoStop(this, a.pkg); lastStops = Vd.autoStops(this); rerender() }
            ))
        }
        row.addView(more)
        return row
    }

    private fun crashRow(c: Vd.Crash): View {
        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.tag = "crash"
        outer.isClickable = true
        outer.setOnClickListener { relaunch(c) }
        val row = listRow()
        row.addView(appIcon(c.pkg), LinearLayout.LayoutParams(dp(48), dp(48)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val name = Ui.text(this, Apps.label(this, c.pkg), 16f, Ui.TEXT, bold = true)
        name.isSingleLine = true
        name.ellipsize = TextUtils.TruncateAt.END
        col.addView(name)
        col.addView(Ui.text(this, "Stopped unexpectedly", 11.5f, Ui.MUTED))
        if (c.reason.isNotBlank()) col.addView(Ui.text(this, "Reason: ${c.reason}", 11.5f, Ui.MUTED))
        val cp = LinearLayout.LayoutParams(0, -2, 1f)
        cp.leftMargin = dp(11)
        row.addView(col, cp)
        val btn = Ui.text(this, "Relaunch", 13f, Ui.ACCENT, bold = true)
        btn.gravity = Gravity.CENTER
        btn.setPadding(dp(14), dp(0), dp(14), dp(0))
        btn.background = Ui.withRipple(Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 14f)), Ui.dpf(this, 14f))
        btn.isClickable = true
        btn.setOnClickListener { relaunch(c) }
        row.addView(btn, LinearLayout.LayoutParams(-2, dp(34)).also { it.rightMargin = dp(8) })
        val more = moreButton()
        more.setOnClickListener {
            Ui.menu(this, more, listOf(
                Ui.MenuItem("Relaunch") { relaunch(c) },
                Ui.MenuItem("Dismiss") { Vd.clearCrash(this, c.pkg); lastCrashes = Vd.crashes(this); rerender() }
            ))
        }
        row.addView(more)
        row.setPadding(dp(16), dp(16), dp(12), dp(0))
        outer.addView(row)
        val mins = ((System.currentTimeMillis() - c.whenMs) / 60000L).coerceAtLeast(0)
        val chip = Ui.pill(this, "Crashed " + (if (mins < 1) "just now" else "${Vd.fmtMinutes(mins)} ago"),
            Ui.BAD, (Ui.BAD and 0x00FFFFFF) or 0x29000000, 11f)
        chip.setPadding(dp(9), dp(5), dp(9), dp(5))
        chip.background = Ui.shape((Ui.BAD and 0x00FFFFFF) or 0x29000000, Ui.dpf(this, 9f))
        val chipRow = LinearLayout(this)
        chipRow.setPadding(dp(16), dp(12), dp(16), dp(16))
        chipRow.addView(chip, LinearLayout.LayoutParams(-2, -2))
        outer.addView(chipRow, LinearLayout.LayoutParams(-1, -2))
        return outer
    }

    private fun renderTasks(allRows: List<Row>, allDisp: List<Vd.Disp>, stats: Map<String, Vd.Stat>, crashes: List<Vd.Crash>, stops: List<Vd.AutoStop>) {
        val now = System.currentTimeMillis()
        hidingPkgs.entries.removeAll { now - it.value > 5000L }
        hidingDisplays.entries.removeAll { now - it.value > 5000L }
        val list = allRows.filter { it.pkg !in hidingPkgs && it.display.id !in hidingDisplays }
        val disp = allDisp.filter { it.id !in hidingDisplays }
        tasksBox.removeAllViews()
        runningCount.text = list.map { it.pkg }.distinct().size.let { "$it ${if (it == 1) "app" else "apps"}" }
        runningN = list.map { it.pkg }.distinct().size
        heroCount?.text = runningN.toString()
        heroLabel?.text = heroLabelText(runningN)
        val empty = disp.filter { d -> allRows.none { it.display.id == d.id } }
        if (list.isEmpty() && empty.isEmpty() && crashes.isEmpty() && stops.isEmpty()) {
            tasksBox.addView(emptyNote("Tap Launch below to start one on a hidden display."))
            return
        }
        fun detail(d: Vd.Disp) = "${if (d.landscape) "Landscape" else "Portrait"} · ${d.w}×${d.h}" +
            (if (d.dpi > 0) " · ${d.dpi} DPI" else "")
        fun detailLines(d: Vd.Disp, col: LinearLayout) {
            col.addView(Ui.text(this, detail(d), 11.5f, Ui.MUTED).also { it.maxLines = 2; it.ellipsize = TextUtils.TruncateAt.END })
            col.addView(Ui.text(this, "Display #${d.id} · ${hzText(d.fps)}", 11f, Ui.MUTED))
        }

        val rows = ArrayList<View>()
        for (c in crashes) if (list.none { it.pkg == c.pkg }) rows.add(crashRow(c))
        for (a in stops) if (list.none { it.pkg == a.pkg }) rows.add(autoStopRow(a))
        for (r in list) {
            val outer = LinearLayout(this)
            outer.orientation = LinearLayout.VERTICAL
            outer.setPadding(dp(18), dp(18), dp(18), dp(16))
            outer.isClickable = true
            outer.setOnClickListener { openViewer(r.display.id) }
            val muted = Vd.isMuted(this, r.pkg)
            val shared = list.count { it.display.id == r.display.id } > 1
            fun stopApp() {
                hidePkg(r.pkg)
                bg {
                    log(Vd.stopAppAndDisplay(applicationContext, r.pkg))
                    runOnUiThread { refresh() }
                }
            }
            outer.setOnLongClickListener {
                Ui.menu(this, it, listOf(
                    Ui.MenuItem("App Settings") { showAppSettings(r.pkg, r.display.landscape) },
                    Ui.MenuItem("Stop App", danger = true) { stopApp() }
                ) + (if (shared) listOf(Ui.MenuItem("Move to its own display") { moveToOwnDisplay(r) }) else emptyList()))
                true
            }

            // who: icon, name, where it runs, time left
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(appIcon(r.pkg), LinearLayout.LayoutParams(dp(52), dp(52)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val name = Ui.text(this, Apps.label(this, r.pkg), 17f, Ui.TEXT, bold = true)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            col.addView(name)
            col.addView(Ui.text(this, "Display #${r.display.id} · ${if (r.display.landscape) "Landscape" else "Portrait"} · ${r.display.w}×${r.display.h}", 12f, Ui.MUTED)
                .also { it.isSingleLine = true; it.ellipsize = TextUtils.TruncateAt.END })
            top.addView(col, LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(14) })
            (Vd.remainingText(this, r.pkg) ?: Vd.sessionText(this, r.pkg)?.let { "Running $it" })?.let { t ->
                val pill = Ui.pill(this, t, Ui.ACCENT, Ui.ACCENT_CONT, 11f)
                pill.isSingleLine = true
                top.addView(pill, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8) })
            }
            outer.addView(top)

            // numbers: memory, CPU, refresh rate (and sound when muted)
            val st = stats[r.pkg]
            val facts = ArrayList<Pair<String, String>>()
            st?.memMb?.let { facts.add(Pair("Memory", "$it MB")) }
            st?.cpu?.let { facts.add(Pair("CPU", "${if (it < 10) String.format("%.1f", it) else it.toInt().toString()}%")) }
            facts.add(Pair("Refresh", hzText(r.display.fps)))
            if (muted) facts.add(Pair("Sound", "Muted"))
            val strip = LinearLayout(this)
            strip.orientation = LinearLayout.HORIZONTAL
            for ((fi, f) in facts.withIndex()) {
                val cell = LinearLayout(this)
                cell.orientation = LinearLayout.VERTICAL
                cell.setPadding(dp(12), dp(10), dp(12), dp(10))
                cell.background = Ui.shape(Ui.SURFACE2, Ui.dpf(this, 16f))
                cell.addView(Ui.text(this, f.first, 11f, Ui.MUTED))
                val v = Ui.text(this, f.second, 14f, Ui.TEXT, bold = true)
                v.isSingleLine = true
                cell.addView(v)
                strip.addView(cell, LinearLayout.LayoutParams(0, -2, 1f).also { if (fi > 0) it.leftMargin = dp(8) })
            }
            Ui.add(outer, strip, top = 16)
            Vd.limitsText(this, r.pkg)?.let { Ui.add(outer, Ui.text(this, it, 11.5f, Ui.MUTED), top = 10) }

            // actions: open, mute, app settings, stop
            val actions = LinearLayout(this)
            actions.orientation = LinearLayout.HORIZONTAL
            actions.gravity = Gravity.CENTER_VERTICAL
            actions.addView(Ui.smallButton(this, "Open", Ui.Kind.FILLED) { openViewer(r.display.id) }, LinearLayout.LayoutParams(0, dp(44), 1f))
            val muteBtn = IconView(this, if (muted) IconView.MUTED else IconView.VOLUME, 44)
            muteBtn.background = Ui.withRipple(Ui.shape(Ui.SURFACE2, Ui.dpf(this, 22f)), Ui.dpf(this, 22f))
            muteBtn.tint = if (muted) Ui.MUTED else Ui.ACCENT
            muteBtn.contentDescription = if (muted) "Unmute sound" else "Mute sound"
            muteBtn.setOnClickListener {
                bg {
                    log("${if (muted) "Unmute" else "Mute"} ${Apps.label(applicationContext, r.pkg)}:\n" +
                        Vd.setMuted(applicationContext, r.pkg, !muted))
                    runOnUiThread { refreshTasks() }
                }
            }
            actions.addView(muteBtn, LinearLayout.LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(8) })
            val setBtn = IconView(this, IconView.SETTINGS, 44)
            setBtn.background = Ui.withRipple(Ui.shape(Ui.SURFACE2, Ui.dpf(this, 22f)), Ui.dpf(this, 22f))
            setBtn.tint = Ui.TEXT
            setBtn.contentDescription = "App settings"
            setBtn.setOnClickListener { showAppSettings(r.pkg, r.display.landscape) }
            actions.addView(setBtn, LinearLayout.LayoutParams(dp(44), dp(44)).also { it.leftMargin = dp(8) })
            actions.addView(Ui.smallButton(this, "Stop", Ui.Kind.DANGER) { stopApp() }, LinearLayout.LayoutParams(-2, dp(44)).also { it.leftMargin = dp(8) })
            Ui.add(outer, actions, top = 16)
            rows.add(outer)
        }

        // Displays with no app on them
        for (d in empty) {
            val row = listRow()
            row.setOnClickListener { openViewer(d.id) }
            row.addView(PhoneIcon(this, d.landscape, Ui.MUTED), LinearLayout.LayoutParams(dp(48), dp(48)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, "Empty display", 16f, Ui.TEXT, bold = true))
            detailLines(d, col)
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(14)
            row.addView(col, cp)
            val more = moreButton()
            more.setOnClickListener {
                Ui.menu(this, more, listOf(
                    Ui.MenuItem("Open viewer") { openViewer(d.id) },
                    Ui.MenuItem("Destroy display", danger = true) {
                        hideDisplay(d.id)
                        bg { Vd.destroyDisplay(applicationContext, d.id) }
                    }
                ))
            }
            row.addView(more)
            rows.add(row)
        }
        Ui.cards(tasksBox, rows)
    }

    // ---------------------------------------------------------------- launching

    private fun pickApp() {
        Ui.sheet(this, 0.8f) { body, dlg ->
            body.addView(Ui.text(this, "Launch on a hidden display", 22f, Ui.TEXT, bold = true))

            val search = EditText(this)
            search.hint = "Search apps"
            search.isSingleLine = true
            Ui.styleInput(this, search)
            Ui.add(body, search, top = 14)

            val shown = ArrayList<Apps.App>()
            val note = Ui.text(this, "Loading apps…", 13f, Ui.MUTED)
            note.setPadding(dp(4), dp(12), dp(4), dp(4))
            Ui.add(body, note, top = 4)

            val adapter = object : BaseAdapter() {
                override fun getCount() = shown.size
                override fun getItem(position: Int): Any = shown[position]
                override fun getItemId(position: Int) = position.toLong()
                override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                    val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).also {
                        it.orientation = LinearLayout.HORIZONTAL
                        it.gravity = Gravity.CENTER_VERTICAL
                        it.setPadding(dp(8), dp(10), dp(8), dp(10))
                        it.addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(42), dp(42)))
                        val tv = Ui.text(this@MainActivity, "", 16f, Ui.TEXT)
                        tv.isSingleLine = true
                        tv.ellipsize = TextUtils.TruncateAt.END
                        val lp = LinearLayout.LayoutParams(0, -2, 1f)
                        lp.leftMargin = dp(14)
                        it.addView(tv, lp)
                    }
                    val a = shown[position]
                    val iv = row.getChildAt(0) as ImageView
                    iv.tag = a.pkg
                    val cached = Apps.icon(this@MainActivity, a.pkg) { b -> if (iv.tag == a.pkg) iv.setImageBitmap(b) }
                    if (cached != null) iv.setImageBitmap(cached) else iv.setImageDrawable(null)
                    (row.getChildAt(1) as TextView).text = a.label
                    return row
                }
            }
            val list = ListView(this)
            list.adapter = adapter
            list.divider = null
            list.dividerHeight = 0
            list.selector = ColorDrawable(Color.TRANSPARENT)
            list.setOnItemClickListener { _, _, pos, _ ->
                val a = shown[pos]
                dlg.dismiss()
                askOrientation(a)
            }
            Ui.add(body, list, top = 8, h = 0, weight = 1f)

            fun applyFilter() {
                val q = search.text.toString().trim().lowercase()
                val all = Apps.list
                shown.clear()
                shown.addAll(if (q.isEmpty()) all else all.filter { it.key.contains(q) })
                adapter.notifyDataSetChanged()
                note.text = if (!Apps.ready) "Loading apps…" else "No apps found"
                note.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
            }
            applyFilter()
            // Already loaded in the background in the normal case; this only waits on a slow first load.
            Apps.load(applicationContext) { if (dlg.isShowing) applyFilter() }

            search.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = applyFilter()
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
    }

    private fun askOrientation(a: Apps.App, initial: Boolean? = null) {
        var sel: Boolean = initial ?: Vd.presetOf(this, a.pkg) ?: a.landscape ?: false
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, a.label, 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "Choose the hidden display orientation", 14f, Ui.MUTED), top = 2)

            val tiles = LinearLayout(this)
            tiles.orientation = LinearLayout.HORIZONTAL
            Ui.add(body, tiles, top = 16)

            var onSelChanged: () -> Unit = {}
            fun drawTiles() {
                tiles.removeAllViews()
                fun tile(label: String, landscape: Boolean) {
                    val picked = sel == landscape
                    val t = LinearLayout(this)
                    t.orientation = LinearLayout.VERTICAL
                    t.gravity = Gravity.CENTER_HORIZONTAL
                    t.setPadding(dp(8), dp(16), dp(8), dp(16))
                    t.background = Ui.withRipple(
                        Ui.shape(
                            if (picked) Ui.ACCENT_CONT else Ui.SURFACE2,
                            Ui.dpf(this, 18f),
                            if (picked) Ui.ACCENT else Ui.hair(),
                            if (picked) dp(2) else dp(1)
                        ),
                        Ui.dpf(this, 18f)
                    )
                    t.isClickable = true
                    t.isFocusable = true
                    Ui.pressable(t)
                    t.addView(PhoneIcon(this, landscape, if (picked) Ui.ON_ACCENT_CONT else Ui.MUTED))
                    t.addView(Ui.text(this, label, 15f, if (picked) Ui.ON_ACCENT_CONT else Ui.MUTED, bold = true))
                    t.addView(Ui.text(this, if (a.landscape == landscape) "Suggested" else " ", 11f, Ui.ACCENT))
                    t.setOnClickListener {
                        sel = landscape; drawTiles(); onSelChanged()
                        if (Vd.presetOf(this, a.pkg) != null) { Vd.savePreset(this, a.pkg, sel); renderPresets() }
                    }
                    val lp = LinearLayout.LayoutParams(0, -2, 1f)
                    if (landscape) lp.leftMargin = dp(12)
                    tiles.addView(t, lp)
                }
                tile("Portrait", false)
                tile("Landscape", true)
            }
            drawTiles()

            // Size for this app only; the default from Settings is used unless one is set here.
            val sizeRow = Ui.button(this, "", Ui.Kind.TONAL) {}
            fun sizeLabel() {
                val c = Vd.appCfg(this, a.pkg)
                val p = Vd.sizeFor(this, sel, c)
                sizeRow.text = (if (c != null) "Custom size" else "Default size") + "  ·  ${p.first}x${p.second}  ·  ${p.third} DPI"
            }
            sizeRow.setOnClickListener {
                val c = Vd.appCfg(this, a.pkg) ?: Vd.sizeCfg(this)
                showSizeEditor(
                    a.label, "Size for this app only. Other apps keep the default from Settings.",
                    c, "Use default size",
                    onSave = { Vd.setAppCfg(this, a.pkg, it); sizeLabel() },
                    onReset = { Vd.setAppCfg(this, a.pkg, null); sizeLabel() }
                )
            }
            sizeLabel()
            onSelChanged = { sizeLabel() }
            Ui.add(body, sizeRow, top = 14)

            val presetBtn = Ui.button(this, "", Ui.Kind.TEXT) {}
            fun presetLabel() {
                presetBtn.text = if (Vd.presetOf(this, a.pkg) != null) "Remove from quick launch" else "Save for one-tap launch"
            }
            presetBtn.setOnClickListener {
                if (Vd.presetOf(this, a.pkg) != null) {
                    Vd.removePreset(this, a.pkg)
                } else {
                    Vd.savePreset(this, a.pkg, sel)
                    Toast.makeText(this, "Saved with ${if (sel) "landscape" else "portrait"} and this size", Toast.LENGTH_SHORT).show()
                }
                presetLabel(); renderPresets()
            }
            presetLabel()
            Ui.add(body, presetBtn, top = 6)

            Ui.add(body, Ui.button(this, "Confirm and launch", Ui.Kind.FILLED) {
                dlg.dismiss(); launchApp(a.pkg, sel)
            }, top = 18)
            Ui.add(body, Ui.button(this, "Cancel", Ui.Kind.TEXT) { dlg.dismiss() }, top = 4)
        }
    }

    private fun moveToOwnDisplay(r: Row) {
        bg {
            val id = Vd.createDisplay(applicationContext, r.display.landscape, Vd.appCfg(applicationContext, r.pkg), Vd.refreshFor(applicationContext, r.pkg))
            if (id >= 0) log("Move ${r.pkg} to #$id:\n" + Vd.exec("am display move-stack ${r.taskId} $id"))
            Thread.sleep(600)
            runOnUiThread { refresh() }
        }
    }

    private fun showUnsupported() {
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, "Not supported on this phone", 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this,
                "Android on this phone says it cannot run apps on extra displays. This is common on low-memory " +
                "\"Go\" phones. Apps would open on the main screen instead of a hidden display, so PocketScreen " +
                "doesn't launch them. No developer setting changes this.", 14f, Ui.MUTED), top = 8)
            Ui.add(body, Ui.button(this, "OK", Ui.Kind.FILLED) { dlg.dismiss() }, top = 20)
        }
    }

    private fun launchApp(pkg: String, landscape: Boolean) {
        // A second tap while the first launch is still starting must not start another copy.
        if (!launching.add(pkg)) {
            Toast.makeText(this, "${Apps.label(this, pkg)} is already launching", Toast.LENGTH_SHORT).show()
            return
        }
        hidingPkgs.remove(pkg)
        bg {
            try {
                // Already running on a hidden display: say so and don't start anything.
                val runningOn = Vd.displayOf(pkg)
                if (runningOn != null) {
                    // Size and refresh rate only apply when a display is created. If those settings have
                    // changed since this one was, offer a relaunch; otherwise there is nothing to apply.
                    val changes = changedSettings(pkg, landscape, Vd.display(runningOn))
                    runOnUiThread {
                        if (changes.isEmpty()) Toast.makeText(this, "${Apps.label(this, pkg)} is already running", Toast.LENGTH_SHORT).show()
                        else confirmRelaunch(pkg, landscape, changes)
                    }
                    return@bg
                }
                if (!Vd.supportsSecondaryDisplays()) {
                    log("Blocked launch of $pkg: this phone reports no support for apps on secondary displays.")
                    runOnUiThread { showUnsupported() }
                    return@bg
                }
                runOnUiThread { Toast.makeText(this, "Launching ${Apps.label(this, pkg)}…", Toast.LENGTH_SHORT).show() }
                val id = Vd.freeDisplay(applicationContext, landscape, Vd.appCfg(applicationContext, pkg), Vd.refreshFor(applicationContext, pkg))
                if (id >= 0) {
                    log("Launch $pkg on #$id:\n" + Vd.launch(applicationContext, pkg, id))
                    // Refresh twice so the app shows up as soon as it exists.
                    Thread.sleep(600)
                    runOnUiThread { refresh() }
                    Thread.sleep(900)
                    // Some phones accept the launch but open the app on the main screen instead.
                    if (Vd.tasks(id).none { it.second == pkg }) {
                        log(Vd.diagnose(pkg, id))
                        runOnUiThread {
                            Toast.makeText(this, "This phone didn't open ${Apps.label(this, pkg)} on the hidden display. " +
                                "It may not support apps on secondary displays. Turn on Debug log and share it.", Toast.LENGTH_LONG).show()
                        }
                    }
                    // Once the process exists, set its sound state again.
                    Vd.syncAudio(applicationContext, pkg)
                    runOnUiThread { refresh() }
                }
            } finally {
                launching.remove(pkg)
            }
        }
    }

    // ---------------------------------------------------------------- display size

    private fun showDisplaySettings() {
        showSizeEditor(
            "Display size",
            "Default for new displays. Existing displays keep their size. An app can use its own size instead.",
            Vd.sizeCfg(this), "Reset to default",
            onSave = { c ->
                Vd.saveSizeCfg(this, c)
                val p = Vd.sizeFor(this, false)
                Toast.makeText(this, "New displays: ${p.first}x${p.second}", Toast.LENGTH_SHORT).show()
            },
            onReset = { Vd.saveSizeCfg(this, Vd.SizeCfg(true, Vd.DEF_WIDTH, 1600, Vd.DEF_DPI)) }
        )
    }

    private fun showSizeEditor(
        title: String, subtitle: String, cfg: Vd.SizeCfg, resetLabel: String,
        onSave: (Vd.SizeCfg) -> Unit, onReset: () -> Unit
    ) {
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, title, 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, subtitle, 14f, Ui.MUTED), top = 2)

            var match = cfg.matchRatio

            fun field(hint: String, value: Int, max: Int): EditText {
                val e = EditText(this)
                // Block typing anything the display can't use: too many digits or above the maximum.
                e.filters = arrayOf(InputFilter { src, st, en, dest, ds, de ->
                    val next = dest.toString().substring(0, ds) + src.subSequence(st, en) + dest.toString().substring(de)
                    if (next.isEmpty()) null
                    else if (next.length > max.toString().length || (next.toIntOrNull() ?: Int.MAX_VALUE) > max) "" else null
                })
                e.hint = hint
                e.inputType = InputType.TYPE_CLASS_NUMBER
                e.isSingleLine = true
                Ui.styleInput(this, e)
                e.setText(value.toString())
                return e
            }
            fun labeled(parent: LinearLayout, label: String, e: EditText, top: Int) {
                Ui.add(parent, Ui.text(this, label, 12f, Ui.MUTED), top = top)
                Ui.add(parent, e, top = 4)
            }

            // mode toggle
            val modeRow = LinearLayout(this)
            modeRow.orientation = LinearLayout.HORIZONTAL
            Ui.add(body, modeRow, top = 14)

            val wField = field("Width", cfg.width, 2160)
            val hField = field("Height", cfg.customH, 3840)
            val dpiField = field("DPI", cfg.dpi, 640)
            val preview = Ui.text(this, "", 13f, Ui.ACCENT)

            val fields = LinearLayout(this)
            fields.orientation = LinearLayout.VERTICAL
            Ui.add(body, fields, top = 4)
            labeled(fields, "Width (px, 240–2160)", wField, 12)
            val wPresets = LinearLayout(this)
            wPresets.orientation = LinearLayout.VERTICAL
            Ui.add(fields, wPresets)
            val hBox = LinearLayout(this)
            hBox.orientation = LinearLayout.VERTICAL
            labeled(hBox, "Height (px, 240–3840)", hField, 12)
            Ui.add(fields, hBox)

            // presets: highlighted when the field matches
            val chipRefs = ArrayList<Triple<TextView, EditText, Int>>()
            fun presetRow(values: IntArray, target: EditText, into: LinearLayout) {
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                for ((i, pv) in values.withIndex()) {
                    val b = Ui.button(this, "$pv", Ui.Kind.TONAL) { target.setText(pv.toString()) }
                    chipRefs.add(Triple(b, target, pv))
                    val lp = LinearLayout.LayoutParams(0, -2, 1f)
                    if (i > 0) lp.leftMargin = dp(8)
                    row.addView(b, lp)
                }
                Ui.add(into, row, top = 8)
            }
            presetRow(intArrayOf(540, 720, 1080), wField, wPresets)
            Ui.add(fields, Ui.text(this, "Density (DPI, 120–640). Lower = smaller apps", 12f, Ui.MUTED), top = 12)
            Ui.add(fields, dpiField, top = 4)
            presetRow(intArrayOf(240, 320, 420), dpiField, fields)
            Ui.add(body, preview, top = 12)

            fun current(): Vd.SizeCfg = Vd.SizeCfg(
                match,
                wField.text.toString().toIntOrNull() ?: Vd.DEF_WIDTH,
                hField.text.toString().toIntOrNull() ?: 1600,
                dpiField.text.toString().toIntOrNull() ?: Vd.DEF_DPI
            )
            fun valid(): String? {
                val w = wField.text.toString().toIntOrNull()
                val h = hField.text.toString().toIntOrNull()
                val d = dpiField.text.toString().toIntOrNull()
                if (w == null || w < 240 || w > 2160) return "Width must be 240–2160"
                if (!match && (h == null || h < 240 || h > 3840)) return "Height must be 240–3840"
                if (d == null || d < 120 || d > 640) return "DPI must be 120–640"
                return null
            }
            fun updatePreview() {
                for ((b, f, v) in chipRefs) {
                    val on = f.text.toString() == v.toString()
                    b.setTextColor(if (on) Ui.ON_ACCENT_CONT else Ui.TEXT)
                    b.background = Ui.withRipple(
                        Ui.shape(if (on) Ui.ACCENT_CONT else Ui.SURFACE2, Ui.dpf(this, 18f), if (on) Ui.ACCENT else Ui.hair(), dp(1)),
                        Ui.dpf(this, 18f)
                    )
                }
                val err = valid()
                if (err != null) { preview.setTextColor(Ui.BAD); preview.text = err; return }
                preview.setTextColor(Ui.ACCENT)
                val c = current()
                val p = Vd.sizeFor(this, false, c)
                val l = Vd.sizeFor(this, true, c)
                preview.text = "Portrait ${p.first}x${p.second}   ·   Landscape ${l.first}x${l.second}   ·   ${p.third} DPI"
            }

            fun styleModes() {
                modeRow.removeAllViews()
                fun mode(label: String, isMatch: Boolean, first: Boolean) {
                    val b = Ui.button(this, label, if (match == isMatch) Ui.Kind.FILLED else Ui.Kind.TONAL) {
                        match = isMatch
                        styleModes()
                    }
                    val lp = LinearLayout.LayoutParams(0, -2, 1f)
                    if (!first) lp.leftMargin = dp(8)
                    modeRow.addView(b, lp)
                }
                mode("Match screen ratio", true, true)
                mode("Custom size", false, false)
                hBox.visibility = if (match) View.GONE else View.VISIBLE
                updatePreview()
            }
            val watcher = object : TextWatcher {
                override fun afterTextChanged(s: Editable?) = updatePreview()
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            }
            wField.addTextChangedListener(watcher)
            hField.addTextChangedListener(watcher)
            dpiField.addTextChangedListener(watcher)
            styleModes()

            Ui.add(body, Ui.button(this, "Save", Ui.Kind.FILLED) {
                val err = valid()
                if (err != null) { Toast.makeText(this, err, Toast.LENGTH_SHORT).show(); return@button }
                onSave(current())
                dlg.dismiss()
            }, top = 14)
            Ui.add(body, Ui.button(this, resetLabel, Ui.Kind.TEXT) {
                onReset()
                dlg.dismiss()
            }, top = 0)
        }
    }

    // ---------------------------------------------------------------- menus

    private fun showMainMenu() {
        val items = ArrayList<Ui.MenuItem>()
        items.add(Ui.MenuItem("Refresh") { refresh() })
        if (Vd.service != null && Vd.displays.isNotEmpty()) {
            items.add(Ui.MenuItem("Stop All", danger = true) { confirmStopAll() })
        }
        items.add(Ui.MenuItem("PocketScreen $versionName"))
        Ui.menu(this, moreBtn, items, above = true)
    }

    /** Title + subtitle on the left, [trailing] view (switch, value, chevron) on the right. */
    private fun settingRowBase(title: String, sub: String?, trailing: View?): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = 0
        row.setPadding(dp(14), dp(12), dp(14), dp(12))
        row.isClickable = true
        row.isFocusable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, title, 13.5f, Ui.TEXT, bold = true))
        if (sub != null) col.addView(Ui.text(this, sub, 11.5f, Ui.MUTED).also { it.setLineSpacing(0f, 1.1f) }, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(2) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) {
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.leftMargin = dp(14)
            row.addView(trailing, lp)
        }
        return row
    }

    private fun settingsRow(title: String, sub: String, onClick: () -> Unit): View {
        val chev = IconView(this, IconView.CHEVRON, 24, false)
        chev.tint = Ui.MUTED
        val row = settingRowBase(title, sub, chev)
        row.setOnClickListener { onClick() }
        return row
    }

    private fun settingsToggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val sw = SwitchView(this)
        sw.set(checked, animate = false)
        val row = settingRowBase(title, sub, sw)
        row.setOnClickListener {
            val on = !sw.isOn
            sw.set(on)
            onChange(on)
        }
        return row
    }

    private enum class Limit { BATTERY, MINUTES, TEMP, CLOCK }

    private fun limitLabel(kind: Limit, v: Int, defaultValue: Int?): String = when {
        v < 0 -> "Default (${limitLabel(kind, defaultValue ?: 0, null)})"
        v == 0 -> "Off"
        kind == Limit.BATTERY -> "$v%"
        kind == Limit.TEMP -> "$v°C"
        kind == Limit.CLOCK -> Vd.clockText(this, v - 1)
        v < 60 -> "$v min"
        v == 60 -> "1 hour"
        v % 60 == 0 -> "${v / 60} hours"
        else -> "${v / 60}h ${v % 60}m"
    }

    /** One choice in a list: label and a check mark when selected. */
    private fun optionRow(label: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = 0
        row.setPadding(dp(14), dp(14), dp(14), dp(14))
        row.isClickable = true
        row.isFocusable = true
        row.addView(Ui.text(this, label, 13.5f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Ui.radio(this, selected), LinearLayout.LayoutParams(dp(20), dp(20)))
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * A row that shows a battery % or minutes limit. Tapping opens a list of choices plus a box for a
     * custom number. [defaultValue] non-null means this is a per-app row that can fall back to a default.
     */
    private fun settingsLimit(
        title: String, kind: Limit,
        current: Int, defaultValue: Int?, onChange: (Int) -> Unit
    ): View {
        var value = current
        val pill = Ui.pill(this, "", Ui.ACCENT, Ui.ACCENT_CONT, 12f)
        pill.setPadding(dp(10), dp(6), dp(10), dp(6))
        fun style() {
            val on = value > 0 || (value < 0 && (defaultValue ?: 0) > 0)
            pill.text = limitLabel(kind, value, defaultValue)
            pill.setTextColor(Ui.ACCENT)
            pill.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 10f))
        }
        style()
        val row = settingRowBase(title, null, pill)
        row.setOnClickListener {
            Ui.sheet(this, 0.85f) { body, dlg ->
                body.addView(Ui.text(this, title, 22f, Ui.TEXT, bold = true))
                val scroll = ScrollView(this)
                scroll.isVerticalScrollBarEnabled = false
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                scroll.addView(col)
                Ui.add(body, scroll, top = 4, h = 0, weight = 1f)

                fun pick(v: Int) { value = v; style(); onChange(v); dlg.dismiss() }
                val choices = when (kind) {
                    Limit.BATTERY -> listOf(10, 15, 20, 30, 50)
                    Limit.TEMP -> listOf(40, 42, 45, 48, 50)
                    Limit.MINUTES -> listOf(15, 30, 60, 120, 240)
                    Limit.CLOCK -> listOf(6, 7, 8, 9, 10).map { it * 60 + 1 }
                }
                val opts = ArrayList<Pair<String, Int>>()
                if (defaultValue != null) opts.add(limitLabel(kind, -1, defaultValue) to -1)
                // When the default is already Off, a separate "Off" would just repeat it.
                if (defaultValue == null || defaultValue != 0) opts.add("Off" to 0)
                for (c in choices) opts.add(limitLabel(kind, c, null) to c)
                // An explicit Off with a default of Off means the same as "Default (Off)".
                val cur = if (defaultValue == 0 && value == 0) -1 else value
                Ui.group(col, opts.map { (label, v) -> optionRow(label, cur == v) { pick(v) } }, top = 14)

                Ui.add(col, Ui.text(this, when (kind) { Limit.BATTERY -> "Custom battery level (%, 1–99)"; Limit.TEMP -> "Custom temperature (°C, 30–60)"; Limit.MINUTES -> "Custom time (minutes, 1–1440)"; Limit.CLOCK -> "Next time the clock reaches it, once" }, 13f, Ui.MUTED), top = 22)
                if (kind == Limit.CLOCK) {
                    Ui.add(col, Ui.button(this, "Pick a time", Ui.Kind.FILLED) {
                        val base = if (value > 0) value - 1 else 7 * 60
                        android.app.TimePickerDialog(this, { _, h, m -> pick(h * 60 + m + 1) },
                            base / 60, base % 60, android.text.format.DateFormat.is24HourFormat(this)).show()
                    }, top = 12)
                    return@sheet
                }
                val min = if (kind == Limit.TEMP) 30 else 1
                val max = when (kind) { Limit.BATTERY -> 99; Limit.TEMP -> 60; Limit.MINUTES -> 1440; Limit.CLOCK -> 1440 }
                val e = EditText(this)
                e.filters = arrayOf(InputFilter { src, st, en, dest, ds, de ->
                    val next = dest.toString().substring(0, ds) + src.subSequence(st, en) + dest.toString().substring(de)
                    if (next.isEmpty()) null
                    else if (next.length > max.toString().length || (next.toIntOrNull() ?: Int.MAX_VALUE) > max) "" else null
                })
                e.hint = when (kind) { Limit.BATTERY -> "e.g. 25"; Limit.TEMP -> "e.g. 43"; Limit.MINUTES -> "e.g. 90"; Limit.CLOCK -> "" }
                e.inputType = InputType.TYPE_CLASS_NUMBER
                e.isSingleLine = true
                Ui.styleInput(this, e)
                if (value > 0 && value !in choices) e.setText(value.toString())
                Ui.add(col, e, top = 6)
                Ui.add(col, Ui.button(this, "Set custom value", Ui.Kind.FILLED) {
                    val v = e.text.toString().toIntOrNull()
                    if (v == null || v < min || v > max) {
                        Toast.makeText(this, "Enter $min–$max", Toast.LENGTH_SHORT).show()
                    } else pick(v)
                }, top = 12)
            }
        }
        return row
    }

    /** 0 means Auto (the phone's own rate). */
    private fun hzText(hz: Int): String = if (hz > 0) "$hz Hz" else "Auto"

    private fun hzLabel(v: Int, defaultValue: Int?): String = when {
        v < 0 -> "Default (${hzLabel(defaultValue ?: 0, null)})"
        v == 0 -> "Auto"
        else -> "$v Hz"
    }

    /**
     * A row for the display refresh rate: Auto (the phone's own rate), presets, or a custom number up
     * to 144. [defaultValue] non-null means a per-app row that can fall back to the Settings value.
     */
    private fun settingsRefresh(current: Int, defaultValue: Int?, onChange: (Int) -> Unit): View {
        val title = "Refresh rate"
        var value = current
        val pill = Ui.text(this, "", 12f, Ui.ACCENT, bold = true)
        pill.setPadding(dp(10), dp(6), dp(10), dp(6))
        fun style() {
            val shown = if (value < 0) (defaultValue ?: 0) else value
            pill.text = if (value < 0) hzLabel(shown, null) else hzLabel(value, null)
            pill.setTextColor(Ui.ACCENT)
            pill.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 10f))
        }
        style()
        val row = settingRowBase(title, null, pill)
        row.setOnClickListener {
            Ui.sheet(this, 0.85f) { body, dlg ->
                body.addView(Ui.text(this, title, 22f, Ui.TEXT, bold = true))
                Ui.add(body, Ui.text(this, "Applies the next time the app is launched. Android rounds it to a rate your screen can show, so it can't go above the phone's own maximum.", 13f, Ui.MUTED), top = 2)
                val scroll = ScrollView(this)
                scroll.isVerticalScrollBarEnabled = false
                val col = LinearLayout(this)
                col.orientation = LinearLayout.VERTICAL
                scroll.addView(col)
                Ui.add(body, scroll, top = 4, h = 0, weight = 1f)

                fun pick(v: Int) { value = v; style(); onChange(v); dlg.dismiss() }
                val presets = listOf(30, 60, 90, 120, 144)
                val opts = ArrayList<Pair<String, Int>>()
                if (defaultValue != null) opts.add(hzLabel(-1, defaultValue) to -1)
                // With a default of Auto, a separate "Auto" would just repeat it.
                if (defaultValue == null || defaultValue != 0) opts.add("Auto" to 0)
                for (c in presets) opts.add("$c Hz" to c)
                val cur = if (defaultValue == 0 && value == 0) -1 else value
                Ui.group(col, opts.map { (label, v) -> optionRow(label, cur == v) { pick(v) } }, top = 14)

                Ui.add(col, Ui.text(this, "Custom rate (Hz, 1–${AppSettings.MAX_HZ})", 13f, Ui.MUTED), top = 22)
                val e = EditText(this)
                e.filters = arrayOf(InputFilter { src, st, en, dest, ds, de ->
                    val next = dest.toString().substring(0, ds) + src.subSequence(st, en) + dest.toString().substring(de)
                    if (next.isEmpty()) null
                    else if (next.length > 3 || (next.toIntOrNull() ?: Int.MAX_VALUE) > AppSettings.MAX_HZ) "" else null
                })
                e.hint = "e.g. 75"
                e.inputType = InputType.TYPE_CLASS_NUMBER
                e.isSingleLine = true
                Ui.styleInput(this, e)
                if (value > 0 && value !in presets) e.setText(value.toString())
                Ui.add(col, e, top = 6)
                Ui.add(col, Ui.button(this, "Set custom value", Ui.Kind.FILLED) {
                    val v = e.text.toString().toIntOrNull()
                    if (v == null || v < 1 || v > AppSettings.MAX_HZ) {
                        Toast.makeText(this, "Enter 1–${AppSettings.MAX_HZ}", Toast.LENGTH_SHORT).show()
                    } else pick(v)
                }, top = 12)
            }
        }
        return row
    }

    /**
     * A per-app switch that normally follows Settings. [current] is -1 (follow Settings), 0 or 1.
     * The list offers only two choices: follow Settings (shown with its value) or the opposite.
     */
    private fun settingsTri(title: String, sub: String, current: Int, defaultOn: Boolean, onChange: (Int) -> Unit): View {
        val opposite = if (defaultOn) 0 else 1
        // An explicit choice equal to the default is the same thing as following it.
        var value = if (current == opposite) opposite else -1
        fun label(v: Int) = if (v < 0) "Same as Settings (${if (defaultOn) "On" else "Off"})" else if (v == 1) "On" else "Off"
        val pill = Ui.text(this, "", 12f, Ui.ACCENT, bold = true)
        pill.setPadding(dp(10), dp(6), dp(10), dp(6))
        fun style() {
            val on = (if (value < 0) defaultOn else value == 1)
            pill.text = if (value < 0) (if (defaultOn) "On" else "Off") else label(value)
            pill.setTextColor(Ui.ACCENT)
            pill.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 10f))
        }
        style()
        val row = settingRowBase(title, sub, pill)
        row.setOnClickListener {
            Ui.sheet(this) { body, dlg ->
                body.addView(Ui.text(this, title, 22f, Ui.TEXT, bold = true))
                Ui.add(body, Ui.text(this, sub, 14f, Ui.MUTED), top = 2)
                fun pick(v: Int) { value = v; style(); onChange(v); dlg.dismiss() }
                Ui.group(body, listOf(-1, opposite).map { v -> optionRow(label(v), value == v) { pick(v) } }, top = 16)
            }
        }
        return row
    }

    /** Everything that is set per app, in one place. Reached from the app's ⋮ menu. */
    private fun showAppSettings(pkg: String, landscape: Boolean) {
        Ui.sheet(this, 0.85f) { body, dlg ->
            body.addView(Ui.text(this, Apps.label(this, pkg), 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "Settings for this app only.", 14f, Ui.MUTED), top = 2)
            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            scroll.addView(col)
            Ui.add(body, scroll, top = 4, h = 0, weight = 1f)

            fun section(title: String, rows: List<View>, note: String? = null) {
                Ui.add(col, Ui.sectionLabel(this, title), top = 22)
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                Ui.add(col, box, top = 8)
                Ui.group(box, rows)
                if (note != null) {
                    val n = Ui.text(this, note, 12f, Ui.MUTED)
                    n.setPadding(dp(8), 0, dp(8), 0)
                    Ui.add(col, n, top = 8)
                }
            }

            val sz = Vd.sizeFor(this, landscape, Vd.appCfg(this, pkg))
            val isPreset = Vd.presetOf(this, pkg) != null
            section("Display", listOf(
                settingsRow(if (isPreset) "Orientation and size" else "Display size",
                    "${if (landscape) "Landscape" else "Portrait"} · ${sz.first}x${sz.second} · ${sz.third} DPI") {
                    dlg.dismiss()
                    if (isPreset) askOrientation(Apps.App(Apps.label(this, pkg), pkg, null), landscape)
                    else showSizeEditor(
                        Apps.label(this, pkg), "Size for this app only. Applies the next time it launches.",
                        Vd.appCfg(this, pkg) ?: Vd.sizeCfg(this), "Use default size",
                        onSave = { Vd.setAppCfg(this, pkg, it) },
                        onReset = { Vd.setAppCfg(this, pkg, null) }
                    )
                },
                settingsRefresh(AppSettings.appRefreshRate(this, pkg), AppSettings.refreshRate(this)) { AppSettings.setAppRefreshRate(this, pkg, it) }
            ), "Applies the next time the app is launched.")

            section("Auto-stop", listOf(
                settingsLimit("Stop at low battery", Limit.BATTERY, AppSettings.appBattery(this, pkg), AppSettings.autoStopBattery(this)) { AppSettings.setAppBattery(this, pkg, it) },
                settingsLimit("Stop when overheating", Limit.TEMP, AppSettings.appTemp(this, pkg), AppSettings.autoStopTemp(this)) { AppSettings.setAppTemp(this, pkg, it) },
                settingsLimit("Stop after running", Limit.MINUTES, AppSettings.appMinutes(this, pkg), AppSettings.autoStopMinutes(this)) { AppSettings.setAppMinutes(this, pkg, it); refreshTasks() },
                settingsLimit("Stop at time", Limit.CLOCK, AppSettings.appAt(this, pkg), AppSettings.autoStopAt(this)) { AppSettings.setAppAt(this, pkg, it); Vd.refreshDeadlines(applicationContext); refreshTasks() }
            ))

            section("Viewer", listOf(
                settingsTri("Suppress keyboard requests", "Keyboard only opens from the keyboard icon.",
                    AppSettings.appSuppressKeyboard(this, pkg), AppSettings.suppressKeyboard(this)) { AppSettings.setAppSuppressKeyboard(this, pkg, it) },
                settingsTri("Back gesture controls the app", "Back inside the viewer goes back inside the app.",
                    AppSettings.appBackGesture(this, pkg), AppSettings.backGesture(this)) { AppSettings.setAppBackGesture(this, pkg, it) }
            ), "Take effect the next time you open the viewer.")
        }
    }

    private fun showSettings() {
        Ui.sheet(this, 0.85f) { body, dlg ->
            body.addView(Ui.text(this, "Settings", 22f, Ui.TEXT, bold = true))
            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            scroll.addView(col)
            Ui.add(body, scroll, top = 4, h = 0, weight = 1f)

            fun section(title: String, rows: List<View>, note: String? = null) {
                Ui.add(col, Ui.sectionLabel(this, title), top = 22)
                val box = LinearLayout(this)
                box.orientation = LinearLayout.VERTICAL
                Ui.add(col, box, top = 8)
                Ui.group(box, rows)
                if (note != null) {
                    val n = Ui.text(this, note, 12f, Ui.MUTED)
                    n.setPadding(dp(8), 0, dp(8), 0)
                    Ui.add(col, n, top = 8)
                }
            }

            val p = Vd.sizeFor(this, false)
            section("General", listOf(
                settingsRow("Display size", "Portrait ${p.first}x${p.second} · ${p.third} DPI") {
                    dlg.dismiss(); showDisplaySettings()
                },
                settingsRefresh(AppSettings.refreshRate(this), null) { AppSettings.setRefreshRate(this, it) },
                settingsRow("Restore sound", "Give sound back to all muted apps") {
                    bg {
                        log(Vd.restoreAllAudio(applicationContext))
                        runOnUiThread { refreshTasks() }
                    }
                    Toast.makeText(this, "Sound restored", Toast.LENGTH_SHORT).show()
                }
            ))
            section("Auto-stop defaults", listOf(
                settingsLimit("Stop at low battery", Limit.BATTERY, AppSettings.autoStopBattery(this), null) { AppSettings.setAutoStopBattery(this, it) },
                settingsLimit("Stop when overheating", Limit.TEMP, AppSettings.autoStopTemp(this), null) { AppSettings.setAutoStopTemp(this, it) },
                settingsLimit("Stop app after running", Limit.MINUTES, AppSettings.autoStopMinutes(this), null) { AppSettings.setAutoStopMinutes(this, it); refreshTasks() },
                settingsLimit("Stop app at time", Limit.CLOCK, AppSettings.autoStopAt(this), null) { AppSettings.setAutoStopAt(this, it); Vd.refreshDeadlines(applicationContext); refreshTasks() }
            ), "Used by apps with no setting of their own. Set one per app from its ⋮ menu. Stopping closes the app and removes its display. Low battery only applies when not charging. Overheating uses the battery temperature and applies even while charging.")
            section("Behavior", listOf(
                settingsToggle("Show memory and CPU", "On each running app. Turn off for faster refreshing.", AppSettings.showStats(this)) {
                    AppSettings.setShowStats(this, it)
                },
                settingsToggle("Back gesture controls the app", "On: back in the viewer goes back inside the app. Off: back does nothing.", AppSettings.backGesture(this)) {
                    AppSettings.setBackGesture(this, it)
                },
                settingsToggle("Hide apps from Recents", "Apps you launch won't appear in Recents while running here. Applies to the next launch.", AppSettings.hideFromRecents(this)) {
                    AppSettings.setHideFromRecents(this, it)
                },
                settingsToggle("Suppress keyboard requests", "Keyboard only opens from the keyboard icon in the viewer.", AppSettings.suppressKeyboard(this)) {
                    AppSettings.setSuppressKeyboard(this, it)
                }
            ))
            section("Updates", listOf(
                settingsRow("Check for updates", "You have $versionName") { checkUpdatesNow() },
                settingsToggle("Check automatically", "Looks for a new release on GitHub at most twice a day.", Updater.autoEnabled(this)) {
                    Updater.setAutoEnabled(this, it)
                }
            ))
            if (Build.VERSION.SDK_INT >= 31) {
                section("Appearance", listOf(
                    settingsToggle("Use system colors", "Follow your wallpaper colors. Light and dark follow the system.", AppSettings.dynamicColor(this)) {
                        AppSettings.setDynamicColor(this, it)
                        dlg.dismiss()
                        recreate()
                    }
                ))
            }

            // Debug log: file only, off by default, never shown on screen.
            val dbgBox = LinearLayout(this)
            dbgBox.orientation = LinearLayout.VERTICAL
            fun showDbgRows(on: Boolean) { dbgBox.visibility = if (on) View.VISIBLE else View.GONE }
            section("Debug", listOf(
                settingsToggle("Debug log", "Saves a log file you can share. Off by default.", Debug.enabled(this)) {
                    Debug.setEnabled(this, it)
                    showDbgRows(it)
                }
            ))
            Ui.add(col, dbgBox, top = 8)
            Ui.group(dbgBox, listOf(
                settingsRow("Share log file", "Send the saved log as text") {
                    val t = Debug.text(this)
                    if (t.isEmpty()) {
                        Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
                    } else {
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "PocketScreen debug log")
                            .putExtra(Intent.EXTRA_TEXT, t.takeLast(200_000))
                        startActivity(Intent.createChooser(i, "Share log"))
                    }
                },
                settingsRow("Clear log file", "Delete everything saved so far") {
                    Debug.clear(this)
                    Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
                }
            ))
            showDbgRows(Debug.enabled(this))
            val ver = Ui.text(this, "PocketScreen $versionName", 12f, Ui.MUTED)
            ver.gravity = Gravity.CENTER
            Ui.add(col, ver, top = 24)
        }
    }

    /** What a new launch of [pkg] would get that the running display doesn't have. Empty = nothing differs. */
    private fun changedSettings(pkg: String, landscape: Boolean, d: Vd.Disp?): List<String> {
        if (d == null) return emptyList()
        val out = ArrayList<String>()
        val (w, h, dpi) = Vd.sizeFor(this, d.landscape, Vd.appCfg(this, pkg))
        if (d.w != w || d.h != h || (d.dpi > 0 && d.dpi != dpi)) out.add("size")
        if (d.fps != Vd.refreshFor(this, pkg)) out.add("refresh rate")
        if (d.landscape != landscape) out.add("orientation")
        return out
    }

    private fun confirmRelaunch(pkg: String, landscape: Boolean, changes: List<String>) {
        val name = Apps.label(this, pkg)
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, "Relaunch $name?", 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "$name is already running, but its ${changes.joinToString(" and ")} " +
                "changed since it started. Relaunching closes the app and starts it again with the new settings.", 14f, Ui.MUTED), top = 4)
            Ui.add(body, Ui.button(this, "Relaunch", Ui.Kind.DANGER) {
                dlg.dismiss()
                bg {
                    Vd.stopAppAndDisplay(applicationContext, pkg)
                    runOnUiThread { launchApp(pkg, landscape) }
                }
            }, top = 16)
            Ui.add(body, Ui.button(this, "Keep running", Ui.Kind.TEXT) { dlg.dismiss() }, top = 4)
        }
    }

    private fun confirmStopAll() {
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, "Stop everything?", 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "This closes every app on the hidden displays and destroys the displays.", 14f, Ui.MUTED), top = 4)
            Ui.add(body, Ui.button(this, "Stop all", Ui.Kind.DANGER) {
                dlg.dismiss()
                bg { Vd.stopAll(applicationContext) }
            }, top = 16)
            Ui.add(body, Ui.button(this, "Cancel", Ui.Kind.TEXT) { dlg.dismiss() }, top = 4)
        }
    }
}
