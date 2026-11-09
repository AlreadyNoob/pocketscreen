package com.hiderlab.vd

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    @Volatile private var service: IVdService? = null
    @Volatile private var displayId = -1

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IVdService.Stub.asInterface(binder)
            log("Service connected")
            bg { displayId = service?.currentDisplay() ?: -1; runOnUiThread { refresh() } }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            log("Service disconnected")
            runOnUiThread { refresh() }
        }
    }

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, result ->
        log("Permission result: ${if (result == PackageManager.PERMISSION_GRANTED) "granted" else "denied"}")
        runOnUiThread { refresh() }
    }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val scroll = ScrollView(this).apply { addView(root); fitsSystemWindows = true }
        setContentView(scroll)

        statusView = TextView(this).apply { textSize = 14f }
        root.addView(statusView)

        fun add(label: String, action: () -> Unit) {
            root.addView(Button(this@MainActivity).apply {
                text = label
                isAllCaps = false
                setOnClickListener { action() }
            })
        }

        add("1. Grant Shizuku permission") { grant() }
        add("2. Connect service") { connect() }
        add("3. Create hidden display") { createDisplay() }
        add("4. Test: heartbeat on HIDDEN display") { launchOn(displayId, "$packageName/.HeartbeatActivity") }
        add("   Control: heartbeat on MAIN display") { launchOn(0, "$packageName/.HeartbeatActivity") }
        add("5. Check heartbeat result") { log(analyze()) }
        add("   Reset heartbeat file") { File(filesDir, "hb.txt").delete(); log("Heartbeat file deleted") }
        add("Launch another app on hidden display") { pickApp() }
        add("Show where apps are") {
            runCmd("dumpsys activity activities | grep -E 'Display #|topResumedActivity|ResumedActivity'")
        }
        add("Show Hiber log for this app") {
            runCmd("logcat -d | grep -i Usf_Hiber | grep -i com.hiderlab | tail -15")
        }
        add("Destroy hidden display") { releaseDisplay() }

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(logView)

        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun log(s: String) = runOnUiThread {
        logView.text = "$s\n\n${logView.text}".take(8000)
    }

    private fun bg(block: () -> Unit) {
        Thread {
            try { block() } catch (t: Throwable) { log("ERR: $t") }
        }.start()
    }

    private fun refresh() {
        val ping = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val perm = ping && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        statusView.text = "Shizuku: ${if (ping) "running" else "NOT running"}\n" +
            "Permission: $perm\nService: ${service != null}\n" +
            "Hidden display: ${if (displayId >= 0) "#$displayId" else "none"}"
    }

    private fun svc(): IVdService? {
        val s = service
        if (s == null) log("Not connected. Do steps 1 and 2 first.")
        return s
    }

    private fun grant() {
        if (!Shizuku.pingBinder()) { log("Shizuku isn't running. Start it first."); return }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) { log("Already granted"); return }
        Shizuku.requestPermission(100)
    }

    @Suppress("DEPRECATION")
    private fun connect() {
        if (!Shizuku.pingBinder()) { log("Shizuku isn't running."); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) { log("Grant permission first."); return }
        val code = packageManager.getPackageInfo(packageName, 0).versionCode
        val args = Shizuku.UserServiceArgs(ComponentName(packageName, VdService::class.java.name))
            .daemon(true)
            .processNameSuffix("vd")
            .version(code)
        Shizuku.bindUserService(args, conn)
        log("Connecting...")
    }

    private fun createDisplay() {
        val s = svc() ?: return
        bg {
            val r = s.createDisplay(720, 1280, 320)
            log("createDisplay: $r")
            if (r.startsWith("OK:")) {
                displayId = r.removePrefix("OK:").substringBefore(' ').toIntOrNull() ?: -1
            }
            runOnUiThread { refresh() }
        }
    }

    private fun releaseDisplay() {
        val s = svc() ?: return
        bg {
            log("release: ${s.releaseDisplay()}")
            displayId = -1
            runOnUiThread { refresh() }
        }
    }

    private fun runCmd(cmd: String) {
        val s = svc() ?: return
        bg { log(s.exec(cmd)) }
    }

    private fun launchOn(display: Int, component: String) {
        if (display < 0) { log("No hidden display yet. Do step 3."); return }
        val s = svc() ?: return
        bg { log("am start on $display:\n" + s.exec("am start --display $display -n $component")) }
    }

    private fun pickApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0).sortedBy { it.loadLabel(pm).toString().lowercase() }
        val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Launch on hidden display")
            .setItems(labels) { _, which ->
                val ai = apps[which].activityInfo
                launchOn(displayId, ComponentName(ai.packageName, ai.name).flattenToShortString())
            }
            .show()
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
