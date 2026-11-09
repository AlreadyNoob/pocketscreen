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
import android.widget.BaseAdapter
import android.widget.EditText
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
    private lateinit var moreBtn: IconView
    private lateinit var displaysBox: LinearLayout
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
        window.setBackgroundDrawable(ColorDrawable(Ui.BG))

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Ui.BG)
        scroll.clipToPadding = false
        scroll.isVerticalScrollBarEnabled = false
        Ui.applyInsets(scroll)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(20), dp(8), dp(20), dp(40))
        scroll.addView(root)
        setContentView(scroll)

        // header: title, overflow menu, status chips
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.addView(Ui.text(this, "PocketScreen", 28f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        moreBtn = IconView(this, IconView.MORE)
        moreBtn.setOnClickListener { showMainMenu() }
        val mlp = LinearLayout.LayoutParams(-2, -2)
        mlp.rightMargin = -dp(12)
        head.addView(moreBtn, mlp)
        root.addView(head)

        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        Ui.add(root, chipsRow, top = 4)

        // setup card: only shown while something still needs doing
        setupCard = Ui.card(this)
        helperView = Ui.text(this, "", 14f, Ui.TEXT)
        setupCard.addView(helperView)
        btnGrant = Ui.button(this, "Grant Shizuku permission", Ui.Kind.FILLED) { grant() }
        Ui.add(setupCard, btnGrant, top = 12)
        btnConnect = Ui.button(this, "Connect service", Ui.Kind.FILLED) { Vd.connect(applicationContext, force = true) }
        Ui.add(setupCard, btnConnect, top = 12)
        Ui.add(root, setupCard, top = 16)

        // launch
        btnLaunch = Ui.button(this, "＋  Launch app", Ui.Kind.FILLED) { pickApp() }
        Ui.add(root, btnLaunch, top = 18)

        // displays
        addSection(root, "Hidden displays", null)
        displaysBox = LinearLayout(this)
        displaysBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, displaysBox, top = 2)

        // apps
        addSection(root, "Running apps", null)
        tasksBox = LinearLayout(this)
        tasksBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, tasksBox, top = 2)

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
        row.addView(Ui.text(this, title, 17f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing != null) row.addView(trailing)
        Ui.add(parent, row, top = 26)
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        renderDisplays(disp)
        refreshTasks()
    }

    private fun grant() {
        if (!Vd.shizukuRunning()) { log("Shizuku isn't running. Start it first."); return }
        Shizuku.requestPermission(100)
    }

    private fun emptyNote(text: String): View {
        val t = Ui.text(this, text, 13f, Ui.MUTED)
        t.setPadding(dp(4), dp(4), dp(4), dp(4))
        return t
    }

    private fun openViewer(id: Int) {
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("display", id))
    }

    // ---------------------------------------------------------------- displays

    private fun renderDisplays(list: List<Vd.Disp>) {
        displaysBox.removeAllViews()
        if (list.isEmpty()) {
            displaysBox.addView(emptyNote("None yet. One is created when you launch an app."))
            return
        }
        for (d in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(6), dp(4), dp(6))
            row.background = Ui.surface(this, clickable = true)
            row.setOnClickListener { openViewer(d.id) }

            row.addView(PhoneIcon(this, d.landscape, Ui.ACCENT))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, "Display #${d.id}", 16f, Ui.TEXT, bold = true))
            col.addView(Ui.text(this, "${if (d.landscape) "Landscape" else "Portrait"} · ${d.w}×${d.h}", 12f, Ui.MUTED))
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(8)
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
            Ui.add(displaysBox, row, top = 10)
        }
    }

    // ---------------------------------------------------------------- app list

    private data class Row(val display: Vd.Disp, val taskId: Int, val pkg: String)

    private fun refreshTasks() {
        val disp = Vd.displays
        if (disp.isEmpty() || Vd.service == null) {
            tasksBox.removeAllViews()
            tasksBox.addView(emptyNote("Nothing is running on a hidden display."))
            return
        }
        bg {
            val rows = disp.flatMap { d -> Vd.tasks(d.id).map { Row(d, it.first, it.second) } }
            runOnUiThread { renderTasks(rows) }
        }
    }

    private fun renderTasks(list: List<Row>) {
        tasksBox.removeAllViews()
        if (list.isEmpty()) {
            tasksBox.addView(emptyNote("Nothing is running on a hidden display."))
            return
        }
        for (r in list) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(dp(14), dp(6), dp(4), dp(6))
            row.background = Ui.surface(this, clickable = true)
            row.setOnClickListener { openViewer(r.display.id) }

            val icon = ImageView(this)
            icon.tag = r.pkg
            val cached = Apps.icon(this, r.pkg) { b -> if (icon.tag == r.pkg) icon.setImageBitmap(b) }
            if (cached != null) icon.setImageBitmap(cached)
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val name = Ui.text(this, Apps.label(this, r.pkg), 15f, Ui.TEXT, bold = true)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            col.addView(name)
            val muted = Vd.isMuted(this, r.pkg)
            col.addView(Ui.text(this, "Display #${r.display.id} · ${if (r.display.landscape) "Landscape" else "Portrait"}" + (if (muted) " · Muted" else ""), 12f, Ui.MUTED))
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(12)
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
                    Ui.MenuItem("Stop app", danger = true) {
                        bg {
                            log(Vd.stopApp(applicationContext, r.pkg))
                            Thread.sleep(800)
                            runOnUiThread { refreshTasks() }
                        }
                    }
                ) + (if (shared) listOf(Ui.MenuItem("Move to its own display") { moveToOwnDisplay(r) }) else emptyList()))
            }
            row.addView(more)
            Ui.add(tasksBox, row, top = 10)
        }
    }

    // ---------------------------------------------------------------- launching

    private fun pickApp() {
        Ui.sheet(this, 0.8f) { body, dlg ->
            body.addView(Ui.text(this, "Launch on a hidden display", 18f, Ui.TEXT, bold = true))

            val search = EditText(this)
            search.hint = "Search apps"
            search.setHintTextColor(Ui.MUTED)
            search.setTextColor(Ui.TEXT)
            search.textSize = 15f
            search.isSingleLine = true
            search.setPadding(dp(16), dp(12), dp(16), dp(12))
            search.background = Ui.shape(Ui.SURFACE2, Ui.dpf(this, 14f))
            Ui.add(body, search, top = 12)

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
                        it.setPadding(dp(8), dp(8), dp(8), dp(8))
                        it.addView(ImageView(this@MainActivity), LinearLayout.LayoutParams(dp(40), dp(40)))
                        val tv = Ui.text(this@MainActivity, "", 15f, Ui.TEXT)
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

    private fun askOrientation(a: Apps.App) {
        var sel = a.landscape
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, a.label, 18f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "Choose the hidden display orientation", 13f, Ui.MUTED), top = 2)

            val tiles = LinearLayout(this)
            tiles.orientation = LinearLayout.HORIZONTAL
            Ui.add(body, tiles, top = 16)

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
                            if (picked) Ui.SURFACE2 else Ui.SURFACE,
                            Ui.dpf(this, 18f),
                            if (picked) Ui.ACCENT else Ui.OUTLINE,
                            dp(if (picked) 2 else 1)
                        ),
                        Ui.dpf(this, 18f)
                    )
                    t.isClickable = true
                    t.isFocusable = true
                    t.addView(PhoneIcon(this, landscape, if (picked) Ui.ACCENT else Ui.MUTED))
                    t.addView(Ui.text(this, label, 15f, if (picked) Ui.TEXT else Ui.MUTED, bold = true))
                    t.addView(Ui.text(this, if (a.landscape == landscape) "Suggested" else " ", 11f, Ui.ACCENT))
                    t.setOnClickListener { sel = landscape; drawTiles() }
                    val lp = LinearLayout.LayoutParams(0, -2, 1f)
                    if (landscape) lp.leftMargin = dp(12)
                    tiles.addView(t, lp)
                }
                tile("Portrait", false)
                tile("Landscape", true)
            }
            drawTiles()

            Ui.add(body, Ui.button(this, "Confirm and launch", Ui.Kind.FILLED) {
                dlg.dismiss(); launchApp(a.pkg, sel)
            }, top = 18)
            Ui.add(body, Ui.button(this, "Cancel", Ui.Kind.TEXT) { dlg.dismiss() }, top = 4)
        }
    }

    private fun moveToOwnDisplay(r: Row) {
        bg {
            val id = Vd.createDisplay(applicationContext, r.display.landscape)
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
            val id = Vd.freeDisplay(applicationContext, landscape)
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
        val cfg = Vd.sizeCfg(this)
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, "Display size", 18f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "Applies to displays created from now on. Existing displays keep their size.", 13f, Ui.MUTED), top = 2)

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
                e.setHintTextColor(Ui.MUTED)
                e.setTextColor(Ui.TEXT)
                e.textSize = 15f
                e.inputType = InputType.TYPE_CLASS_NUMBER
                e.isSingleLine = true
                e.setPadding(dp(16), dp(12), dp(16), dp(12))
                e.background = Ui.shape(Ui.SURFACE2, Ui.dpf(this, 14f))
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
                    b.setTextColor(if (on) Ui.ON_ACCENT else Ui.TEXT)
                    b.background = Ui.withRipple(Ui.shape(if (on) Ui.ACCENT else Ui.SURFACE2, Ui.dpf(this, 22f)), Ui.dpf(this, 22f))
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
                Vd.saveSizeCfg(this, current())
                val p = Vd.sizeFor(this, false)
                Toast.makeText(this, "New displays: ${p.first}×${p.second}", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }, top = 14)
            Ui.add(body, Ui.button(this, "Reset to default", Ui.Kind.TEXT) {
                Vd.saveSizeCfg(this, Vd.SizeCfg(true, Vd.DEF_WIDTH, 1600, Vd.DEF_DPI))
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

    private fun settingsRow(parent: LinearLayout, title: String, sub: String, onClick: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.VERTICAL
        row.setPadding(dp(16), dp(12), dp(16), dp(12))
        row.background = Ui.surface(this, clickable = true)
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener { onClick() }
        row.addView(Ui.text(this, title, 15f, Ui.TEXT, bold = true))
        row.addView(Ui.text(this, sub, 12f, Ui.MUTED))
        Ui.add(parent, row, top = 10)
    }

    private fun settingsToggle(parent: LinearLayout, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        var on = checked
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(16), dp(12), dp(12), dp(12))
        row.background = Ui.surface(this, clickable = true)
        row.isClickable = true
        row.isFocusable = true
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.addView(Ui.text(this, title, 15f, Ui.TEXT, bold = true))
        col.addView(Ui.text(this, sub, 12f, Ui.MUTED))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = Ui.text(this, "", 12f, Ui.TEXT, bold = true)
        pill.gravity = Gravity.CENTER
        pill.setPadding(dp(14), dp(6), dp(14), dp(6))
        fun style() {
            pill.text = if (on) "On" else "Off"
            pill.setTextColor(if (on) Ui.ON_ACCENT else Ui.MUTED)
            pill.background = Ui.shape(if (on) Ui.ACCENT else Ui.SURFACE2, Ui.dpf(this, 14f))
        }
        style()
        row.addView(pill)
        row.setOnClickListener { on = !on; style(); onChange(on) }
        Ui.add(parent, row, top = 10)
    }

    private fun showSettings() {
        Ui.sheet(this, 0.7f) { body, dlg ->
            body.addView(Ui.text(this, "Settings", 18f, Ui.TEXT, bold = true))
            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            scroll.addView(col)
            Ui.add(body, scroll, top = 4, h = 0, weight = 1f)

            val p = Vd.sizeFor(this, false)
            settingsRow(col, "Display size", "Portrait ${p.first}×${p.second} · ${p.third} dpi") {
                dlg.dismiss(); showDisplaySettings()
            }
            settingsRow(col, "Restore sound", "Give sound back to all muted apps") {
                bg { log(Vd.restoreAllAudio(applicationContext)) }
                Toast.makeText(this, "Sound restored", Toast.LENGTH_SHORT).show()
            }
            settingsToggle(col, "Suppress keyboard requests", "Keyboard only opens from the keyboard icon in the viewer. Off by default.", AppSettings.suppressKeyboard(this)) {
                AppSettings.setSuppressKeyboard(this, it)
            }
            // Debug log: file only, off by default, never shown on screen.
            val dbgBox = LinearLayout(this)
            dbgBox.orientation = LinearLayout.VERTICAL
            fun showDbgRows(on: Boolean) { dbgBox.visibility = if (on) View.VISIBLE else View.GONE }
            settingsToggle(col, "Debug log", "Saves a log file you can share. Off by default.", Debug.enabled(this)) {
                Debug.setEnabled(this, it)
                showDbgRows(it)
            }
            Ui.add(col, dbgBox)
            settingsRow(dbgBox, "Share log file", "Send the saved log as text") {
                val t = Debug.text(this)
                if (t.isEmpty()) {
                    Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show()
                } else {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "PocketScreen debug log")
                        .putExtra(Intent.EXTRA_TEXT, t.takeLast(200_000))
                    startActivity(Intent.createChooser(i, "Share log"))
                }
            }
            settingsRow(dbgBox, "Clear log file", "Delete everything saved so far") {
                Debug.clear(this)
                Toast.makeText(this, "Log cleared", Toast.LENGTH_SHORT).show()
            }
            showDbgRows(Debug.enabled(this))
            Ui.add(col, Ui.text(this, "PocketScreen $versionName", 12f, Ui.MUTED), top = 20)
        }
    }

    private fun confirmStopAll() {
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, "Stop everything?", 18f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "This closes every app on the hidden displays and destroys the displays.", 13f, Ui.MUTED), top = 4)
            Ui.add(body, Ui.button(this, "Stop all", Ui.Kind.DANGER) {
                dlg.dismiss()
                bg { Vd.stopAll(applicationContext) }
            }, top = 16)
            Ui.add(body, Ui.button(this, "Cancel", Ui.Kind.TEXT) { dlg.dismiss() }, top = 4)
        }
    }
}
