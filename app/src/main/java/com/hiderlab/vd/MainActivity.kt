package com.hiderlab.vd

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
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
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {

    private lateinit var chipsRow: LinearLayout
    private lateinit var helperView: TextView
    private lateinit var btnGrant: View
    private lateinit var btnConnect: View
    private lateinit var btnLaunch: View
    private lateinit var btnStopAll: View
    private lateinit var displaysBox: LinearLayout
    private lateinit var tasksBox: LinearLayout
    private lateinit var diagBody: LinearLayout
    private lateinit var diagToggle: TextView
    private lateinit var logCard: LinearLayout
    private lateinit var logView: TextView

    private val refreshRunnable = Runnable { runOnUiThread { refresh() } }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("Permission: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
        runOnUiThread { refresh() }
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
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        scroll.addView(root)
        setContentView(scroll)

        // header
        root.addView(Ui.text(this, "Hider Lab", 30f, Ui.TEXT, bold = true))
        Ui.add(root, Ui.text(this, "Keep apps running, out of sight.", 14f, Ui.MUTED), top = 2)

        // status
        val status = Ui.card(this)
        chipsRow = LinearLayout(this)
        chipsRow.orientation = LinearLayout.HORIZONTAL
        status.addView(chipsRow)
        helperView = Ui.text(this, "", 13f, Ui.MUTED)
        Ui.add(status, helperView, top = 10)
        btnGrant = Ui.button(this, "Grant Shizuku permission", Ui.Kind.FILLED) { grant() }
        Ui.add(status, btnGrant, top = 12)
        btnConnect = Ui.button(this, "Connect service", Ui.Kind.FILLED) { Vd.connect(applicationContext, force = true) }
        Ui.add(status, btnConnect, top = 12)
        Ui.add(root, status, top = 20)

        // launch
        btnLaunch = Ui.button(this, "＋  Launch app", Ui.Kind.FILLED) { pickApp() }
        Ui.add(root, btnLaunch, top = 14)

        // displays
        addSection(root, "Hidden displays", null)
        displaysBox = LinearLayout(this)
        displaysBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, displaysBox, top = 8)

        // apps
        addSection(root, "Running apps", Ui.button(this, "Refresh", Ui.Kind.TEXT) { refreshTasks() })
        tasksBox = LinearLayout(this)
        tasksBox.orientation = LinearLayout.VERTICAL
        Ui.add(root, tasksBox, top = 8)

        btnStopAll = Ui.button(this, "Stop all apps and destroy all displays", Ui.Kind.DANGER) {
            bg { Vd.stopAll(applicationContext) }
        }
        Ui.add(root, btnStopAll, top = 16)

        // log (only visible once something was logged)
        logCard = Ui.card(this)
        logView = Ui.text(this, "", 12f, Ui.MUTED)
        logView.typeface = android.graphics.Typeface.MONOSPACE
        logView.setTextIsSelectable(true)
        logCard.addView(logView)
        Ui.add(logCard, Ui.button(this, "Clear", Ui.Kind.TEXT) {
            logView.text = ""
            logCard.visibility = View.GONE
        }, top = 6, w = ViewGroup.LayoutParams.WRAP_CONTENT)
        logCard.visibility = View.GONE
        Ui.add(root, logCard, top = 20)

        // diagnostics (collapsed)
        val diagHeader = LinearLayout(this)
        diagHeader.orientation = LinearLayout.HORIZONTAL
        diagHeader.gravity = Gravity.CENTER_VERTICAL
        diagHeader.setPadding(0, dp(8), 0, dp(8))
        diagHeader.isClickable = true
        diagHeader.addView(Ui.text(this, "Diagnostics", 13f, Ui.MUTED, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        diagToggle = Ui.text(this, "Show", 13f, Ui.ACCENT)
        diagHeader.addView(diagToggle)
        diagBody = LinearLayout(this)
        diagBody.orientation = LinearLayout.VERTICAL
        diagBody.visibility = View.GONE
        diagHeader.setOnClickListener {
            val open = diagBody.visibility != View.VISIBLE
            diagBody.visibility = if (open) View.VISIBLE else View.GONE
            diagToggle.text = if (open) "Hide" else "Show"
        }
        Ui.add(root, diagHeader, top = 20)
        Ui.add(root, diagBody, top = 4)
        fun diag(label: String, action: () -> Unit) {
            Ui.add(diagBody, Ui.button(this, label, Ui.Kind.TONAL) { action() }, top = 8)
        }
        diag("Heartbeat test on first hidden display") { heartbeatOn(Vd.displays.firstOrNull()?.id ?: -1) }
        diag("Heartbeat control on main display") { heartbeatOn(0) }
        diag("Check heartbeat result") { log(analyze()) }
        diag("Reset heartbeat file") { File(filesDir, "hb.txt").delete(); log("Heartbeat file deleted") }
        diag("Show where apps are") {
            bg { log(Vd.exec("dumpsys activity activities | grep -E 'Display #|topResumedActivity'")) }
        }
        diag("Show Hiber log for Hider Lab") {
            bg { log(Vd.exec("logcat -d | grep -i Usf_Hiber | grep -i com.hiderlab | tail -15")) }
        }

        val version = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "" }
        val v = Ui.text(this, "Hider Lab $version", 11f, Ui.MUTED)
        v.gravity = Gravity.CENTER
        Ui.add(root, v, top = 24)

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
        Ui.add(parent, row, top = 28)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Vd.listeners.remove(refreshRunnable)
        Vd.logSink = null
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun log(s: String) = runOnUiThread {
        if (::logView.isInitialized) {
            logView.text = "$s\n\n${logView.text}".take(8000)
            logCard.visibility = View.VISIBLE
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
            Triple("Shizuku", if (ping) Ui.OK else Ui.BAD, 0),
            Triple("Permission", if (perm) Ui.OK else if (ping) Ui.WARN else Ui.BAD, 1),
            Triple("Service", if (connected) Ui.OK else if (perm) Ui.WARN else Ui.BAD, 2)
        )
        for ((label, color, _) in chips) {
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.rightMargin = dp(8)
            chipsRow.addView(Ui.chip(this, color, label), lp)
        }

        helperView.text = when {
            !ping -> "Shizuku isn't running. Start it from wireless debugging, then come back."
            !perm -> "Allow Hider Lab in Shizuku to continue."
            !connected -> "Connecting to the service…"
            disp.isEmpty() -> "Ready. Launch an app and a hidden display is created for it."
            else -> "Ready. ${disp.size} hidden display${if (disp.size == 1) "" else "s"} running."
        }

        btnGrant.visibility = if (ping && !perm) View.VISIBLE else View.GONE
        btnConnect.visibility = if (perm && !connected) View.VISIBLE else View.GONE
        btnLaunch.visibility = if (connected) View.VISIBLE else View.GONE
        btnStopAll.visibility = if (connected && disp.isNotEmpty()) View.VISIBLE else View.GONE
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
            val card = Ui.card(this)
            val top = LinearLayout(this)
            top.orientation = LinearLayout.HORIZONTAL
            top.gravity = Gravity.CENTER_VERTICAL
            top.addView(PhoneIcon(this, d.landscape, Ui.ACCENT))
            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            col.addView(Ui.text(this, "Display #${d.id}", 16f, Ui.TEXT, bold = true))
            col.addView(Ui.text(this, "${if (d.landscape) "Landscape" else "Portrait"} · ${d.w}×${d.h}", 12f, Ui.MUTED))
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(8)
            top.addView(col, cp)
            card.addView(top)

            val btns = LinearLayout(this)
            btns.orientation = LinearLayout.HORIZONTAL
            btns.gravity = Gravity.CENTER_VERTICAL
            btns.addView(Ui.button(this, "Open viewer", Ui.Kind.TONAL) { openViewer(d.id) },
                LinearLayout.LayoutParams(0, -2, 1f))
            val dl = LinearLayout.LayoutParams(-2, -2)
            dl.leftMargin = dp(8)
            btns.addView(Ui.button(this, "Destroy", Ui.Kind.DANGER_TEXT) {
                bg { Vd.destroyDisplay(applicationContext, d.id) }
            }, dl)
            Ui.add(card, btns, top = 10)
            Ui.add(displaysBox, card, top = 10)
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

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    private val icons = HashMap<String, Drawable?>()

    private fun appIcon(pkg: String): Drawable? = icons.getOrPut(pkg) {
        try { packageManager.getApplicationIcon(pkg) } catch (_: Throwable) { null }
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
            row.setPadding(dp(14), dp(10), dp(8), dp(10))
            row.background = Ui.surface(this, clickable = true)
            row.setOnClickListener { openViewer(r.display.id) }

            val icon = ImageView(this)
            icon.setImageDrawable(appIcon(r.pkg))
            row.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))

            val col = LinearLayout(this)
            col.orientation = LinearLayout.VERTICAL
            val name = Ui.text(this, appLabel(r.pkg), 15f, Ui.TEXT, bold = true)
            name.isSingleLine = true
            name.ellipsize = TextUtils.TruncateAt.END
            col.addView(name)
            col.addView(Ui.text(this, "Display #${r.display.id} · ${if (r.display.landscape) "Landscape" else "Portrait"}", 12f, Ui.MUTED))
            val cp = LinearLayout.LayoutParams(0, -2, 1f)
            cp.leftMargin = dp(12)
            row.addView(col, cp)

            row.addView(Ui.button(this, "Stop", Ui.Kind.DANGER_TEXT) {
                bg {
                    log(Vd.exec("am force-stop ${r.pkg}"))
                    Thread.sleep(800)
                    runOnUiThread { refreshTasks() }
                }
            })
            Ui.add(tasksBox, row, top = 10)
        }
    }

    // ---------------------------------------------------------------- launching

    private data class App(val label: String, val pkg: String)

    private fun pickApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { App(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.pkg }
            .filter { it.pkg != packageName }
            .sortedBy { it.label.lowercase() }
        val shown = ArrayList(apps)

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
                    (row.getChildAt(0) as ImageView).setImageDrawable(appIcon(a.pkg))
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

            search.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val q = s.toString().trim().lowercase()
                    shown.clear()
                    shown.addAll(apps.filter { it.label.lowercase().contains(q) })
                    adapter.notifyDataSetChanged()
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        }
    }

    /** Reads the orientation an app declares in its manifest: true = landscape, false = portrait, null = unknown. */
    private fun declaredLandscape(pkg: String): Boolean? = try {
        val comp = packageManager.getLaunchIntentForPackage(pkg)?.component
        if (comp == null) null else when (packageManager.getActivityInfo(comp, 0).screenOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT -> false
            else -> null
        }
    } catch (_: Throwable) { null }

    private fun askOrientation(a: App) {
        val suggest = declaredLandscape(a.pkg)
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
            Ui.add(body, Ui.button(this, "Cancel", Ui.Kind.TEXT) { dlg.dismiss() }, top = 8)
        }
    }

    private fun launchApp(pkg: String, landscape: Boolean) {
        bg {
            val id = Vd.ensureDisplay(applicationContext, landscape)
            if (id >= 0) {
                log("Launch $pkg on #$id:\n" + Vd.launch(applicationContext, pkg, id))
                Thread.sleep(1500)
                runOnUiThread { refresh() }
            }
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
