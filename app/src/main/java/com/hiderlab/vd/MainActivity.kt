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
        root.setPadding(dp(20), dp(12), dp(20), dp(120)) // bottom room for the floating button
        scroll.addView(root)
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        Ui.styleSystemBars(this)

        // header: large title, overflow menu, status chips
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titles.addView(Ui.text(this, "PocketScreen", 32f, Ui.TEXT, bold = true))
        titles.addView(Ui.text(this, "Apps running on hidden displays", 14f, Ui.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        moreBtn = IconView(this, IconView.MORE)
        moreBtn.background = Ui.withRipple(Ui.shape(Ui.SURFACE, Ui.dpf(this, 24f)), Ui.dpf(this, 24f))
        moreBtn.setOnClickListener { showMainMenu() }
        head.addView(moreBtn, LinearLayout.LayoutParams(-2, -2))
        Ui.add(root, head, top = 8)

        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        Ui.add(root, chipsRow, top = 16)

        // setup card: only shown while something still needs doing
        setupCard = Ui.card(this)
        helperView = Ui.text(this, "", 15f, Ui.TEXT)
        setupCard.addView(helperView)
        btnGrant = Ui.button(this, "Grant Shizuku permission", Ui.Kind.FILLED) { grant() }
        Ui.add(setupCard, btnGrant, top = 14)
        btnConnect = Ui.button(this, "Connect service", Ui.Kind.FILLED) { Vd.connect(applicationContext, force = true) }
        Ui.add(setupCard, btnConnect, top = 14)
        Ui.add(root, setupCard, top = 20)

        // launch: extended floating action button
        btnLaunch = Ui.fab(this, "Launch app") { pickApp() }
        val fl = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END)
        fl.setMargins(0, 0, dp(20), dp(20))
        frame.addView(btnLaunch, fl)

        // quick launch
        presetsHeader = LinearLayout(this)
        presetsHeader.orientation = LinearLayout.VERTICAL
        addSection(presetsHeader, "Quick launch", null)
        Ui.add(root, presetsHeader)
        presetsBox = LinearLayout(this)
        presetsBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, presetsBox, top = 8)

        // running apps and the displays they use, in one list
        addSection(root, "Running now", null)
        tasksBox = LinearLayout(this)
        tasksBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, tasksBox, top = 8)

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
    }

    private fun addSection(parent: LinearLayout, title: String, trailing: View?) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.addView(Ui.sectionLabel(this, title), LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        Ui.add(parent, row, top = 28)
    }

    override fun onResume() {
        super.onResume()
        // Light/dark or wallpaper colors changed while we were away: rebuild with the new palette.
        if (Ui.themeChanged(this)) { recreate(); return }
        refresh()
        tickHandler.removeCallbacks(tick)
        tickHandler.postDelayed(tick, 30_000)
    }

    // Keeps running time and time left fresh while the screen is open.
    private val tickHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshTasks()
            tickHandler.postDelayed(this, 30_000)
        }
    }

    override fun onPause() {
        tickHandler.removeCallbacks(tick)
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
        val chips = listOf(
            "Shizuku" to (if (ping) Ui.OK else Ui.BAD),
            "Permission" to (if (perm) Ui.OK else if (ping) Ui.WARN else Ui.BAD),
            "Service" to (if (connected) Ui.OK else if (perm) Ui.WARN else Ui.BAD)
        )
        for ((label, color) in chips) {
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(8)
            chipsRow.addView(Ui.chip(this, color, label), lp)
        }

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

    private fun grant() {
        if (!Vd.shizukuRunning()) { log("Shizuku isn't running. Start it first."); return }
        Shizuku.requestPermission(100)
    }

    private fun emptyNote(text: String): View {
        val t = Ui.text(this, text, 14f, Ui.MUTED)
        t.setPadding(dp(20), dp(18), dp(20), dp(18))
        t.background = Ui.surface(this)
        return t
    }

    /** App icon with rounded corners. */
    private fun appIcon(pkg: String): ImageView {
        val icon = ImageView(this)
        icon.tag = pkg
        icon.clipToOutline = true
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * 0.28f)
            }
        }
        val cached = Apps.icon(this, pkg) { b -> if (icon.tag == pkg) icon.setImageBitmap(b) }
        if (cached != null) icon.setImageBitmap(cached)
        return icon
    }

    private fun listRow(): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(14), dp(6), dp(14))
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
        val rows = ArrayList<View>()
        for ((pkg, landscape) in list) {
            val row = listRow()
            row.setOnClickListener { launchApp(pkg, landscape) } // one tap: start it now
            row.addView(appIcon(pkg), LinearLayout.LayoutParams(dp(46), dp(46)))

            val cfg = Vd.appCfg(this, pkg)
            val sz = Vd.sizeFor(this, landscape, cfg)
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val name = Ui.text(this, Apps.label(this, pkg), 16f, Ui.TEXT, bold = true)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            col.addView(name)
            col.addView(Ui.text(this, "${if (landscape) "Landscape" else "Portrait"} · ${sz.first}×${sz.second} · ${sz.third} dpi" +
                (if (cfg != null) " · custom" else ""), 13f, Ui.MUTED))
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(14)
            row.addView(col, cp)

            val more = IconView(this, IconView.MORE)
            more.tint = Ui.MUTED
            more.setOnClickListener {
                Ui.menu(this, more, listOf(
                    Ui.MenuItem("Launch now") { launchApp(pkg, landscape) },
                    Ui.MenuItem("Edit orientation and size") { askOrientation(Apps.App(Apps.label(this, pkg), pkg, null), landscape) },
                    Ui.MenuItem("Remove from quick launch", danger = true) { Vd.removePreset(this, pkg); renderPresets() }
                ))
            }
            row.addView(more)
            rows.add(row)
        }
        Ui.group(presetsBox, rows)
    }

    private data class Row(val display: Vd.Disp, val taskId: Int, val pkg: String)

    private val emptySince = HashMap<Int, Long>()

    private fun refreshTasks() {
        val disp = Vd.displays
        if (disp.isEmpty() || Vd.service == null) {
            tasksBox.removeAllViews()
            tasksBox.addView(emptyNote("Nothing is running. A hidden display is created when you launch an app."))
            return
        }
        bg {
            val rows = disp.flatMap { d -> Vd.tasks(d.id).map { Row(d, it.first, it.second) } }
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
            val stats = if (AppSettings.showStats(applicationContext)) Vd.appStats(rows.map { it.pkg }.distinct()) else emptyMap()
            runOnUiThread { renderTasks(rows, disp, stats); if (cleaned) refresh() }
        }
    }

    private fun renderTasks(list: List<Row>, disp: List<Vd.Disp>, stats: Map<String, Vd.Stat>) {
        tasksBox.removeAllViews()
        val empty = disp.filter { d -> list.none { it.display.id == d.id } }
        if (list.isEmpty() && empty.isEmpty()) {
            tasksBox.addView(emptyNote("Nothing is running. A hidden display is created when you launch an app."))
            return
        }
        fun detail(d: Vd.Disp) = "Display #${d.id} · ${if (d.landscape) "Landscape" else "Portrait"} · ${d.w}×${d.h}" +
            (if (d.dpi > 0) " · ${d.dpi} dpi" else "")

        val rows = ArrayList<View>()
        for (r in list) {
            val row = listRow()
            row.setOnClickListener { openViewer(r.display.id) }
            row.addView(appIcon(r.pkg), LinearLayout.LayoutParams(dp(46), dp(46)))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val name = Ui.text(this, Apps.label(this, r.pkg), 16f, Ui.TEXT, bold = true)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            col.addView(name)
            col.addView(Ui.text(this, detail(r.display), 13f, Ui.MUTED))
            val muted = Vd.isMuted(this, r.pkg)
            val extra = listOfNotNull(
                Vd.sessionText(this, r.pkg)?.let { "Running $it" },
                Vd.remainingText(this, r.pkg),
                stats[r.pkg]?.memMb?.let { "$it MB" },
                stats[r.pkg]?.cpu?.let { "CPU ${if (it < 10) String.format("%.1f", it) else it.toInt().toString()}%" },
                if (muted) "Muted" else null
            ).joinToString(" · ")
            if (extra.isNotEmpty()) {
                val st = Ui.text(this, extra, 12f, Ui.ON_ACCENT_CONT)
                st.setPadding(dp(10), dp(3), dp(10), dp(3))
                st.background = Ui.shape(Ui.ACCENT_CONT, Ui.dpf(this, 10f))
                Ui.add(col, st, top = 6, w = ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(14)
            row.addView(col, cp)

            val shared = list.count { it.display.id == r.display.id } > 1
            val more = IconView(this, IconView.MORE)
            more.tint = Ui.MUTED
            more.setOnClickListener {
                Ui.menu(this, more, listOf(
                    Ui.MenuItem("Open viewer") { openViewer(r.display.id) },
                    Ui.MenuItem(if (muted) "Unmute sound" else "Mute sound") {
                        bg {
                            log("${if (muted) "Unmute" else "Mute"} ${Apps.label(applicationContext, r.pkg)}:\n" +
                                Vd.setMuted(applicationContext, r.pkg, !muted))
                            runOnUiThread { refreshTasks() }
                        }
                    },
                    Ui.MenuItem("Auto-stop for this app") { showAppAutoStop(r.pkg) },
                    Ui.MenuItem("Close display, keep app in Recents") {
                        bg {
                            log("Detach ${r.pkg}:\n" + Vd.detachApp(applicationContext, r.pkg, r.display.id))
                            runOnUiThread { refresh() }
                        }
                    },
                    Ui.MenuItem("Stop app and remove display", danger = true) {
                        bg {
                            log(Vd.stopAppAndDisplay(applicationContext, r.pkg))
                            runOnUiThread { refresh() }
                        }
                    }
                ) + (if (shared) listOf(Ui.MenuItem("Move to its own display") { moveToOwnDisplay(r) }) else emptyList()))
            }
            row.addView(more)
            rows.add(row)
        }

        // Displays with no app on them
        for (d in empty) {
            val row = listRow()
            row.setOnClickListener { openViewer(d.id) }
            row.addView(PhoneIcon(this, d.landscape, Ui.MUTED), LinearLayout.LayoutParams(dp(46), dp(46)))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, "Empty display", 16f, Ui.TEXT, bold = true))
            col.addView(Ui.text(this, detail(d), 13f, Ui.MUTED))
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(14)
            row.addView(col, cp)
            val more = IconView(this, IconView.MORE)
            more.tint = Ui.MUTED
            more.setOnClickListener {
                Ui.menu(this, more, listOf(
                    Ui.MenuItem("Open viewer") { openViewer(d.id) },
                    Ui.MenuItem("Destroy display", danger = true) { bg { Vd.destroyDisplay(applicationContext, d.id) } }
                ))
            }
            row.addView(more)
            rows.add(row)
        }
        Ui.group(tasksBox, rows)
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
                            Ui.dpf(this, 24f),
                            Ui.ACCENT,
                            if (picked) dp(2) else 0
                        ),
                        Ui.dpf(this, 24f)
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
                sizeRow.text = (if (c != null) "Custom size" else "Default size") + "  ·  ${p.first}×${p.second}  ·  ${p.third} dpi   ›"
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
            val id = Vd.createDisplay(applicationContext, r.display.landscape, Vd.appCfg(applicationContext, r.pkg))
            if (id >= 0) log("Move ${r.pkg} to #$id:\n" + Vd.exec("am display move-stack ${r.taskId} $id"))
            Thread.sleep(600)
            runOnUiThread { refresh() }
        }
    }

    private fun launchApp(pkg: String, landscape: Boolean) {
        Toast.makeText(this, "Launching ${Apps.label(this, pkg)}…", Toast.LENGTH_SHORT).show()
        bg {
            // Already running on a hidden display: just open it instead of starting a second copy.
            val existing = Vd.displayOf(pkg)
            if (existing != null) {
                runOnUiThread { openViewer(existing) }
                return@bg
            }
            val id = Vd.freeDisplay(applicationContext, landscape, Vd.appCfg(applicationContext, pkg))
            if (id >= 0) {
                log("Launch $pkg on #$id:\n" + Vd.launch(applicationContext, pkg, id))
                // Refresh twice so the app shows up as soon as it exists.
                Thread.sleep(600)
                runOnUiThread { refresh() }
                Thread.sleep(900)
                // Once the process exists, set its sound state again.
                Vd.syncAudio(applicationContext, pkg)
                runOnUiThread { refresh() }
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
                Toast.makeText(this, "New displays: ${p.first}×${p.second}", Toast.LENGTH_SHORT).show()
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
                    b.background = Ui.withRipple(Ui.shape(if (on) Ui.ACCENT_CONT else Ui.SURFACE2, Ui.dpf(this, 26f)), Ui.dpf(this, 26f))
                }
                val err = valid()
                if (err != null) { preview.setTextColor(Ui.BAD); preview.text = err; return }
                preview.setTextColor(Ui.ACCENT)
                val c = current()
                val p = Vd.sizeFor(this, false, c)
                val l = Vd.sizeFor(this, true, c)
                preview.text = "Portrait ${p.first}×${p.second}   ·   Landscape ${l.first}×${l.second}   ·   ${p.third} dpi"
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
        items.add(Ui.MenuItem("Settings") { showSettings() })
        if (Vd.service != null && Vd.displays.isNotEmpty()) {
            items.add(Ui.MenuItem("Stop all and destroy displays", danger = true) { confirmStopAll() })
        }
        items.add(Ui.MenuItem("PocketScreen $versionName"))
        Ui.menu(this, moreBtn, items)
    }

    /** Title + subtitle on the left, [trailing] view (switch, value, chevron) on the right. */
    private fun settingRowBase(title: String, sub: String?, trailing: View?): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(68)
        row.setPadding(dp(20), dp(12), dp(16), dp(12))
        row.isClickable = true
        row.isFocusable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, title, 16f, Ui.TEXT, bold = true))
        if (sub != null) col.addView(Ui.text(this, sub, 13f, Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) {
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.leftMargin = dp(12)
            row.addView(trailing, lp)
        }
        return row
    }

    private fun settingsRow(title: String, sub: String, onClick: () -> Unit): View {
        val chev = Ui.text(this, "›", 24f, Ui.MUTED)
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

    private fun limitLabel(isBattery: Boolean, v: Int, defaultValue: Int?): String = when {
        v < 0 -> "Default (${limitLabel(isBattery, defaultValue ?: 0, null)})"
        v == 0 -> "Off"
        isBattery -> "$v%"
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
        row.minimumHeight = dp(56)
        row.setPadding(dp(20), dp(8), dp(16), dp(8))
        row.isClickable = true
        row.isFocusable = true
        row.addView(Ui.text(this, label, 16f, if (selected) Ui.ACCENT else Ui.TEXT, bold = selected), LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) {
            val ck = IconView(this, IconView.CHECK, 24, false)
            ck.tint = Ui.ACCENT
            row.addView(ck, LinearLayout.LayoutParams(dp(24), dp(24)))
        }
        row.setOnClickListener { onClick() }
        return row
    }

    /**
     * A row that shows a battery % or minutes limit. Tapping opens a list of choices plus a box for a
     * custom number. [defaultValue] non-null means this is a per-app row that can fall back to a default.
     */
    private fun settingsLimit(
        title: String, isBattery: Boolean,
        current: Int, defaultValue: Int?, onChange: (Int) -> Unit
    ): View {
        var value = current
        val pill = Ui.text(this, "", 13f, Ui.TEXT, bold = true)
        pill.setPadding(dp(14), dp(7), dp(14), dp(7))
        fun style() {
            val on = value > 0 || (value < 0 && (defaultValue ?: 0) > 0)
            pill.text = limitLabel(isBattery, value, defaultValue)
            pill.setTextColor(if (on) Ui.ON_ACCENT_CONT else Ui.MUTED)
            pill.background = Ui.shape(if (on) Ui.ACCENT_CONT else Ui.SURFACE2, Ui.dpf(this, 16f))
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
                val choices = if (isBattery) listOf(10, 15, 20, 30, 50) else listOf(15, 30, 60, 120, 240)
                val opts = ArrayList<Pair<String, Int>>()
                if (defaultValue != null) opts.add(limitLabel(isBattery, -1, defaultValue) to -1)
                // When the default is already Off, a separate "Off" would just repeat it.
                if (defaultValue == null || defaultValue != 0) opts.add("Off" to 0)
                for (c in choices) opts.add(limitLabel(isBattery, c, null) to c)
                // An explicit Off with a default of Off means the same as "Default (Off)".
                val cur = if (defaultValue == 0 && value == 0) -1 else value
                Ui.group(col, opts.map { (label, v) -> optionRow(label, cur == v) { pick(v) } }, top = 14)

                Ui.add(col, Ui.text(this, if (isBattery) "Custom battery level (%, 1–99)" else "Custom time (minutes, 1–1440)", 13f, Ui.MUTED), top = 22)
                val max = if (isBattery) 99 else 1440
                val e = EditText(this)
                e.filters = arrayOf(InputFilter { src, st, en, dest, ds, de ->
                    val next = dest.toString().substring(0, ds) + src.subSequence(st, en) + dest.toString().substring(de)
                    if (next.isEmpty()) null
                    else if (next.length > max.toString().length || (next.toIntOrNull() ?: Int.MAX_VALUE) > max) "" else null
                })
                e.hint = if (isBattery) "e.g. 25" else "e.g. 90"
                e.inputType = InputType.TYPE_CLASS_NUMBER
                e.isSingleLine = true
                Ui.styleInput(this, e)
                if (value > 0 && value !in choices) e.setText(value.toString())
                Ui.add(col, e, top = 6)
                Ui.add(col, Ui.button(this, "Set custom value", Ui.Kind.FILLED) {
                    val v = e.text.toString().toIntOrNull()
                    if (v == null || v < 1 || v > max) {
                        Toast.makeText(this, if (isBattery) "Enter 1–99" else "Enter 1–1440", Toast.LENGTH_SHORT).show()
                    } else pick(v)
                }, top = 12)
            }
        }
        return row
    }

    private fun showAppAutoStop(pkg: String) {
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, Apps.label(this, pkg), 22f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "Auto-stop for this app only. \"Default\" uses the value from Settings.", 14f, Ui.MUTED), top = 2)
            val gb = AppSettings.autoStopBattery(this)
            val gm = AppSettings.autoStopMinutes(this)
            Ui.group(body, listOf(
                settingsLimit("Stop at low battery", true, AppSettings.appBattery(this, pkg), gb) { AppSettings.setAppBattery(this, pkg, it) },
                settingsLimit("Stop after running", false, AppSettings.appMinutes(this, pkg), gm) { AppSettings.setAppMinutes(this, pkg, it); refreshTasks() }
            ), top = 16)
            Ui.add(body, Ui.button(this, "Done", Ui.Kind.FILLED) { dlg.dismiss() }, top = 20)
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
                settingsRow("Display size", "Portrait ${p.first}×${p.second} · ${p.third} dpi") {
                    dlg.dismiss(); showDisplaySettings()
                },
                settingsRow("Restore sound", "Give sound back to all muted apps") {
                    bg { log(Vd.restoreAllAudio(applicationContext)) }
                    Toast.makeText(this, "Sound restored", Toast.LENGTH_SHORT).show()
                }
            ))
            section("Auto-stop defaults", listOf(
                settingsLimit("Stop at low battery", true, AppSettings.autoStopBattery(this), null) { AppSettings.setAutoStopBattery(this, it) },
                settingsLimit("Stop app after running", false, AppSettings.autoStopMinutes(this), null) { AppSettings.setAutoStopMinutes(this, it); refreshTasks() }
            ), "Used by apps with no setting of their own. Set one per app from its ⋮ menu. Stopping also removes the display. Battery stop only applies when not charging.")
            section("Behavior", listOf(
                settingsToggle("Show memory and CPU", "On each running app. Turn off for faster refreshing.", AppSettings.showStats(this)) {
                    AppSettings.setShowStats(this, it)
                },
                settingsToggle("Back gesture controls the app", "On: back in the viewer goes back inside the app. Off: back does nothing.", AppSettings.backGesture(this)) {
                    AppSettings.setBackGesture(this, it)
                },
                settingsToggle("Suppress keyboard requests", "Keyboard only opens from the keyboard icon in the viewer.", AppSettings.suppressKeyboard(this)) {
                    AppSettings.setSuppressKeyboard(this, it)
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
