package com.hiderlab.vd

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import java.io.File

/** Writes a timestamp every second. A gap in the file means the process was frozen. */
class HeartbeatActivity : Activity() {

    companion object {
        @Volatile private var started = false
        @Volatile var count = 0L

        fun ensure(dir: File) {
            synchronized(this) {
                if (started) return
                started = true
            }
            Thread {
                val f = File(dir, "hb.txt")
                while (true) {
                    try {
                        f.appendText("${System.currentTimeMillis()}\n")
                        count++
                    } catch (_: Throwable) {}
                    try { Thread.sleep(1000) } catch (_: InterruptedException) {}
                }
            }.apply { isDaemon = true; start() }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tv: TextView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = TextView(this).apply { textSize = 18f; setPadding(48, 160, 48, 48) }
        setContentView(tv)
        ensure(filesDir)
        val tick = object : Runnable {
            override fun run() {
                val d = windowManager.defaultDisplay.displayId
                tv.text = "Heartbeat running\nOn display: $d\nBeats written: $count"
                ui.postDelayed(this, 1000)
            }
        }
        ui.post(tick)
    }
}
