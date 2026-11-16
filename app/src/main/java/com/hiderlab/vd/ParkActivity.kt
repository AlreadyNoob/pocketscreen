package com.hiderlab.vd

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View

/**
 * An empty black screen that is started on a hidden display while the phone's screen is off. Being
 * opaque and on top, it makes Android stop the app underneath (onPause/onStop), the same as when you
 * switch to another app. Finishing it brings the app straight back. It lives in this app's process,
 * so if PocketScreen itself dies the park goes with it and the app comes back.
 */
class ParkActivity : Activity() {
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { finish() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val v = View(this)
        v.setBackgroundColor(Color.BLACK)
        setContentView(v)
        instances.add(this)
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON))
    }

    override fun onDestroy() {
        instances.remove(this)
        try { unregisterReceiver(screenOn) } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        private val instances = java.util.concurrent.CopyOnWriteArrayList<ParkActivity>()
        fun finishAll() { instances.forEach { try { it.finish() } catch (_: Throwable) {} } }
        fun isParked(): Boolean = instances.isNotEmpty()
    }
}
