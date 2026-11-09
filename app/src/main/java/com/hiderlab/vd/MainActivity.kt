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
import android.text.InputType
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
import java.io.File

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

    private val logBuf = StringBuilder()
    private var logTv: TextView? = null
    private var logScroll: ScrollView? = null
    private var logHeader: View? = null

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

        Vd.logSink = { log(it) }
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
        Vd.logSink = null
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun log(s: String) = runOnUiThread {
        val t = "$s\n\n$logBuf".take(8000)
        logBuf.setLength(0)
        logBuf.append(t)
        val tv = logTv
        if (tv != null) {
            tv.text = t
            // Bring the fresh output into view if the sheet is open.
            val sc = logScroll
            val h = logHeader
            if (sc != null && h != null) sc.post { sc.smoothScrollTo(0, h.top) }
        } else if (::moreBtn.isInitialized) {
            moreBtn.badge = true
        }
    }

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
        val suggest = a.landscape
        Ui.sheet(this) { body, dlg ->
            body.addView(Ui.text(this, a.label, 18f, Ui.TEXT, bold = true))
            Ui.add(body, Ui.text(this, "Which hidden display should it run on?", 13f, Ui.MUTED), top = 2)

            val tiles = LinearLayout(this)
            tiles.orientation = LinearLayout.HORIZONTAL
            fun tile(label: String, landscape: Boolean, first: Boolean) {
                val t = LinearLayout(this)
                t.orientation = LinearLayout.VERTICAL
                t.gravity = Gravity.CENTER_HORIZONTAL
                t.setPadding(dp(8), dp(16), dp(8), dp(16))
                val picked = suggest == landscape
                t.background = Ui.withRipple(
                    Ui.shape(Ui.SURFACE2, Ui.dpf(this, 18f), if (picked) Ui.ACCENT else Ui.OUTLINE, dp(if (picked) 2 else 1)),
                    Ui.dpf(this, 18f)
                )
                t.isClickable = true
                t.addView(PhoneIcon(this, landscape, if (picked) Ui.ACCENT else Ui.TEXT))
                t.addView(Ui.text(this, label, 15f, Ui.TEXT, bold = true))
                t.addView(Ui.text(this, if (picked) "Suggested" else " ", 11f, Ui.ACCENT))
                t.setOnClickListener { dlg.dismiss(); launchApp(a.pkg, landscape) }
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                if (!first) lp.leftMargin = dp(12)
                tiles.addView(t, lp)
            }
            tile("Portrait", false, true)
            tile("Landscape", true, false)
            Ui.add(body, tiles, top = 16)
            val p = Vd.sizeFor(this, false)
            val l = Vd.sizeFor(this, true)
            Ui.add(body, Ui.button(this, "Size ${p.first}×${p.second} / ${l.first}×${l.second}  ·  Change", Ui.Kind.TEXT) {
                dlg.dismiss(); showDisplaySettings()
            }, top = 8)
            val soundBtn = Ui.button(this, "", Ui.Kind.TEXT) {}
            fun styleSound() {
                soundBtn.text = if (Vd.isMuted(this, a.pkg)) "Sound: muted  ·  Unmute" else "Sound: on  ·  Mute"
            }
            soundBtn.setOnClickListener {
                Vd.setMutedPref(this, a.pkg, !Vd.isMuted(this, a.pkg))
                styleSound()
            }
            styleSound()
            Ui.add(body, soundBtn, top = 0)
            Ui.add(body, Ui.button(this, "Cancel", Ui.Kind.TEXT) { dlg.dismiss() }, top = 0)
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

            fun field(hint: String, value: Int): EditText {
                val e = EditText(this)
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

            val wField = field("Width", cfg.width)
            val hField = field("Height", cfg.customH)
            val dpiField = field("DPI", cfg.dpi)
            val preview = Ui.text(this, "", 13f, Ui.ACCENT)

            val fields = LinearLayout(this)
            fields.orientation = LinearLayout.VERTICAL
            Ui.add(body, fields, top = 4)
            labeled(fields, "Width (px, portrait)", wField, 12)
            val hBox = LinearLayout(this)
            hBox.orientation = LinearLayout.VERTICAL
            labeled(hBox, "Height (px, portrait)", hField, 12)
            Ui.add(fields, hBox)
            labeled(fields, "Density (DPI). Lower = smaller apps, more fits on screen", dpiField, 12)

            // width presets
            val presets = LinearLayout(this)
            presets.orientation = LinearLayout.HORIZONTAL
            for (pw in intArrayOf(540, 720, 1080)) {
                val b = Ui.button(this, "${pw}", Ui.Kind.TONAL) { wField.setText(pw.toString()) }
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                lp.rightMargin = dp(8)
                presets.addView(b, lp)
            }
            Ui.add(fields, presets, top = 8)
            Ui.add(body, preview, top = 12)

            fun current(): Vd.SizeCfg = Vd.SizeCfg(
                match,
                wField.text.toString().toIntOrNull() ?: Vd.DEF_WIDTH,
                hField.text.toString().toIntOrNull() ?: 1600,
                dpiField.text.toString().toIntOrNull() ?: Vd.DEF_DPI
            )
            fun updatePreview() {
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
        val sz = Vd.sizeFor(this, false)
        items.add(Ui.MenuItem("Display size  ·  ${sz.first}×${sz.second}") { showDisplaySettings() })
        items.add(Ui.MenuItem("Diagnostics & log" + if (moreBtn.badge) "  •" else "") { showDiagnostics() })
        if (Vd.service != null && Vd.displays.isNotEmpty()) {
            items.add(Ui.MenuItem("Stop all and destroy displays", danger = true) { confirmStopAll() })
        }
        items.add(Ui.MenuItem("PocketScreen $versionName"))
        Ui.menu(this, moreBtn, items)
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

    private fun showDiagnostics() {
        moreBtn.badge = false
        Ui.sheet(this, 0.85f) { body, dlg ->
            body.addView(Ui.text(this, "Diagnostics & log", 18f, Ui.TEXT, bold = true))

            val scroll = ScrollView(this)
            scroll.isVerticalScrollBarEnabled = false
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            scroll.addView(col)
            Ui.add(body, scroll, top = 8, h = 0, weight = 1f)

            fun diag(label: String, action: () -> Unit) {
                Ui.add(col, Ui.button(this, label, Ui.Kind.TONAL) { action() }, top = 8)
            }
            diag("Heartbeat test on first hidden display") { heartbeatOn(Vd.displays.firstOrNull()?.id ?: -1) }
            diag("Heartbeat control on main display") { heartbeatOn(0) }
            diag("Check heartbeat result") { log(analyze()) }
            diag("Reset heartbeat file") { File(filesDir, "hb.txt").delete(); log("Heartbeat file deleted") }
            diag("Give sound back to all muted apps") { bg { log(Vd.restoreAllAudio(applicationContext)) } }
            diag("Show where apps are") {
                bg { log(Vd.exec("dumpsys activity activities | grep -E 'Display #|topResumedActivity'")) }
            }
            diag("Show Recents entries") {
                bg { log(Vd.exec("dumpsys activity recents | grep -E 'Recent #|baseIntent|flg=' | head -40")) }
            }
            diag("Show Hiber log for PocketScreen") {
                bg { log(Vd.exec("logcat -d | grep -i Usf_Hiber | grep -i com.hiderlab | tail -15")) }
            }

            val header = Ui.text(this, "Log", 13f, Ui.MUTED, bold = true)
            Ui.add(col, header, top = 22)
            val tv = Ui.text(this, if (logBuf.isEmpty()) "Nothing logged yet." else logBuf.toString(), 12f, Ui.MUTED)
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.setTextIsSelectable(true)
            Ui.add(col, tv, top = 6)
            Ui.add(col, Ui.button(this, "Clear log", Ui.Kind.TEXT) {
                logBuf.setLength(0)
                tv.text = "Nothing logged yet."
            }, top = 6, w = ViewGroup.LayoutParams.WRAP_CONTENT)

            logTv = tv
            logScroll = scroll
            logHeader = header
            dlg.setOnDismissListener { logTv = null; logScroll = null; logHeader = null }
        }
    }

    // ---------------------------------------------------------------- heartbeat diagnostics

    private fun heartbeatOn(display: Int) {
        if (display < 0) { log("No hidden display yet."); return }
        bg { log("am start on $display:\n" + Vd.exec("am start --display $display -n $packageName/.HeartbeatActivity")) }
    }

    private fun analyze(): String {
        val f = File(filesDir, "hb.txt")
        if (!f.exists()) return "No heartbeat file yet. Run the test first."
        val ts = f.readLines().mapNotNull { it.trim().toLongOrNull() }
        if (ts.size < 2) return "Only ${ts.size} sample(s) so far."
        var maxGap = 0L
        val gaps = ArrayList<String>()
        for (i in 1 until ts.size) {
            val g = ts[i] - ts[i - 1]
            if (g > maxGap) maxGap = g
            if (g > 3000) gaps.add("  at +${(ts[i - 1] - ts[0]) / 1000}s: frozen for ${g / 1000}s")
        }
        val span = (ts.last() - ts.first()) / 1000
        val age = (System.currentTimeMillis() - ts.last()) / 1000
        val verdict = if (gaps.isEmpty()) "ALIVE: no gaps over 3s" else "FROZEN at times: ${gaps.size} gap(s)"
        return "$verdict\nsamples=${ts.size} span=${span}s maxGap=${maxGap / 1000}s lastBeat=${age}s ago\n" +
            gaps.take(15).joinToString("\n")
    }
}
