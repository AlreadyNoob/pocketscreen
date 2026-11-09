package com.hiderlab.vd

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var displaysBox: LinearLayout
    private lateinit var tasksBox: LinearLayout
    private lateinit var btnGrant: Button
    private lateinit var btnConnect: Button
    private lateinit var btnLaunch: Button
    private lateinit var btnStopAll: Button

    private val refreshRunnable = Runnable { runOnUiThread { refresh() } }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("Permission: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
        runOnUiThread { refresh() }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(parent: LinearLayout, label: String, action: () -> Unit): Button {
        val b = Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
        }
        parent.addView(b)
        return b
    }

    private fun header(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(root); fitsSystemWindows = true })

        statusView = TextView(this).apply { textSize = 14f }
        root.addView(statusView)

        btnGrant = button(root, "1. Grant Shizuku permission") { grant() }
        btnConnect = button(root, "2. Connect service") { Vd.connect(applicationContext, force = true) }
        root.addView(CheckBox(this).apply {
            text = "Match main screen size (more battery, sharper preview)"
            isChecked = getSharedPreferences("hider", MODE_PRIVATE).getBoolean("match", false)
            setOnCheckedChangeListener { _, on ->
                getSharedPreferences("hider", MODE_PRIVATE).edit().putBoolean("match", on).apply()
            }
        })
        btnLaunch = button(root, "Launch app on a hidden display…") { pickApp() }

        header(root, "Hidden displays")
        displaysBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(displaysBox)

        header(root, "Apps on hidden displays")
        tasksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(tasksBox)
        button(root, "Refresh list") { refreshTasks() }
        btnStopAll = button(root, "Stop all apps and destroy all displays") { bg { Vd.stopAll(applicationContext) } }

        header(root, "Diagnostics")
        button(root, "Heartbeat test on first hidden display") { heartbeatOn(Vd.displays.firstOrNull()?.id ?: -1) }
        button(root, "Heartbeat control on main display") { heartbeatOn(0) }
        button(root, "Check heartbeat result") { log(analyze()) }
        button(root, "Reset heartbeat file") { File(filesDir, "hb.txt").delete(); log("Heartbeat file deleted") }
        button(root, "Show where apps are") {
            bg { log(Vd.exec("dumpsys activity activities | grep -E 'Display #|topResumedActivity'")) }
        }
        button(root, "Show Hiber log for Hider Lab") {
            bg { log(Vd.exec("logcat -d | grep -i Usf_Hiber | grep -i com.hiderlab | tail -15")) }
        }

        logView = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(logView)

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
        if (::logView.isInitialized) logView.text = "$s\n\n${logView.text}".take(8000)
    }

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (t: Throwable) { log("ERR: $t") }
        }.start()
    }

    private fun refresh() {
        val ping = Vd.shizukuRunning()
        val perm = Vd.permitted()
        if (perm && Vd.service == null) Vd.connect(applicationContext)
        val connected = Vd.service != null
        val disp = Vd.displays

        statusView.text = "Shizuku: ${if (ping) "running" else "NOT running (start it via wireless debugging)"}\n" +
            "Permission: ${if (perm) "granted" else "not granted"}\n" +
            "Service: ${if (connected) "connected" else "not connected"}\n" +
            "Hidden displays: ${disp.size}"

        fun vis(b: Button, show: Boolean) { b.visibility = if (show) View.VISIBLE else View.GONE }
        vis(btnGrant, ping && !perm)
        vis(btnConnect, perm && !connected)
        vis(btnLaunch, connected)
        vis(btnStopAll, connected && disp.isNotEmpty())
        renderDisplays(disp)
        refreshTasks()
    }

    private fun grant() {
        if (!Vd.shizukuRunning()) { log("Shizuku isn't running. Start it first."); return }
        Shizuku.requestPermission(100)
    }

    // ---- displays ----

    private fun renderDisplays(list: List<Vd.Disp>) {
        displaysBox.removeAllViews()
        if (list.isEmpty()) {
            displaysBox.addView(TextView(this).apply { text = "(none yet, one is created when you launch an app)" })
            return
        }
        for (d in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply { text = d.label }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "Viewer"; isAllCaps = false
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, ViewerActivity::class.java).putExtra("display", d.id))
                }
            })
            row.addView(Button(this).apply {
                text = "Destroy"; isAllCaps = false
                setOnClickListener { bg { Vd.destroyDisplay(applicationContext, d.id) } }
            })
            displaysBox.addView(row)
        }
    }

    // ---- app list ----

    private data class Row(val display: Vd.Disp, val taskId: Int, val pkg: String)

    private fun refreshTasks() {
        val disp = Vd.displays
        if (disp.isEmpty() || Vd.service == null) {
            tasksBox.removeAllViews()
            tasksBox.addView(TextView(this).apply { text = "(no hidden displays)" })
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

    private fun renderTasks(list: List<Row>) {
        tasksBox.removeAllViews()
        if (list.isEmpty()) {
            tasksBox.addView(TextView(this).apply { text = "(nothing running there yet)" })
            return
        }
        for (r in list) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(TextView(this).apply {
                text = "${appLabel(r.pkg)}\n#${r.display.id} ${if (r.display.landscape) "landscape" else "portrait"}"
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "Stop"; isAllCaps = false
                setOnClickListener {
                    bg { log(Vd.exec("am force-stop ${r.pkg}")); Thread.sleep(800); runOnUiThread { refreshTasks() } }
                }
            })
            row.addView(Button(this).apply {
                text = "To main"; isAllCaps = false
                setOnClickListener {
                    bg { log(Vd.toMain(applicationContext, r.taskId, r.pkg)); Thread.sleep(800); runOnUiThread { refreshTasks() } }
                }
            })
            tasksBox.addView(row)
        }
    }

    // ---- launching ----

    private fun pickApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first.lowercase() }
        var shown = apps
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown.map { it.first }.toMutableList())
        val list = ListView(this).apply { this.adapter = adapter }
        val search = EditText(this).apply { hint = "Search apps" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(search)
            addView(list)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s.toString().trim().lowercase()
                shown = apps.filter { it.first.lowercase().contains(q) }
                adapter.clear()
                adapter.addAll(shown.map { it.first })
            }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        })
        val dialog = AlertDialog.Builder(this).setTitle("Launch on hidden display").setView(box).show()
        list.setOnItemClickListener { _, _, pos, _ ->
            val (label, pkg) = shown[pos]
            dialog.dismiss()
            askOrientation(label, pkg)
        }
    }

    private fun askOrientation(label: String, pkg: String) {
        AlertDialog.Builder(this)
            .setTitle(label)
            .setMessage("Which hidden display should it run on?")
            .setPositiveButton("Portrait") { _, _ -> launchApp(pkg, false) }
            .setNegativeButton("Landscape") { _, _ -> launchApp(pkg, true) }
            .setNeutralButton("Cancel", null)
            .show()
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

    // ---- heartbeat diagnostics ----

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
