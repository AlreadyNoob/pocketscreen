package com.hiderlab.vd

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.Toast

/**
 * Live view of the hidden display. While this screen is open the display draws into the
 * SurfaceView; when it closes the display goes back to its invisible surface.
 * Touches are injected into the hidden display, keys and text go to the focused app there.
 */
class ViewerActivity : Activity(), SurfaceHolder.Callback {

    private lateinit var sv: SurfaceView
    private var dw = 720
    private var dh = 1280
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Vd.service == null || Vd.displayId < 0) {
            Toast.makeText(this, "No hidden display. Create one first.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        dw = Vd.width
        dh = Vd.height

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(Color.BLACK)
        }
        val box = FrameLayout(this)
        sv = SurfaceView(this)
        box.addView(sv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))

        box.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val cw = r - l
            val ch = b - t
            if (cw <= 0 || ch <= 0) return@addOnLayoutChangeListener
            val sc = minOf(cw.toFloat() / dw, ch.toFloat() / dh)
            val nw = (dw * sc).toInt()
            val nh = (dh * sc).toInt()
            val lp = sv.layoutParams
            if (lp.width != nw || lp.height != nh) {
                lp.width = nw
                lp.height = nh
                sv.layoutParams = lp
            }
        }

        sv.holder.setFixedSize(dw, dh)
        sv.holder.addCallback(this)
        sv.setOnTouchListener { v, e ->
            val action = when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
                MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
                MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
                MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
                else -> return@setOnTouchListener true
            }
            val x = (e.x * dw / v.width).coerceIn(0f, (dw - 1).toFloat())
            val y = (e.y * dh / v.height).coerceIn(0f, (dh - 1).toFloat())
            try { Vd.service?.touch(action, x, y) } catch (_: Throwable) {}
            true
        }

        // Key row
        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun key(label: String, code: Int, meta: Int = 0) {
            keys.addView(Button(this).apply {
                text = label
                isAllCaps = false
                setOnClickListener { try { Vd.service?.key(code, meta) } catch (_: Throwable) {} }
            })
        }
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        key("Back", KeyEvent.KEYCODE_BACK)
        key("Home", KeyEvent.KEYCODE_HOME)
        key("Recents", KeyEvent.KEYCODE_APP_SWITCH)
        key("Esc", KeyEvent.KEYCODE_ESCAPE)
        key("Tab", KeyEvent.KEYCODE_TAB)
        key("Ctrl+C", KeyEvent.KEYCODE_C, ctrl)
        key("Ctrl+D", KeyEvent.KEYCODE_D, ctrl)
        key("↑", KeyEvent.KEYCODE_DPAD_UP)
        key("↓", KeyEvent.KEYCODE_DPAD_DOWN)
        key("←", KeyEvent.KEYCODE_DPAD_LEFT)
        key("→", KeyEvent.KEYCODE_DPAD_RIGHT)
        key("Del", KeyEvent.KEYCODE_DEL)
        key("Enter", KeyEvent.KEYCODE_ENTER)
        root.addView(HorizontalScrollView(this).apply { addView(keys) })

        // Text row
        input = EditText(this).apply {
            hint = "Type here, then Send"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            setSingleLine(true)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Send"; isAllCaps = false
            setOnClickListener { sendText(false) }
        })
        row.addView(Button(this).apply {
            text = "Send + Enter"; isAllCaps = false
            setOnClickListener { sendText(true) }
        })
        root.addView(row)

        setContentView(root)
    }

    private fun sendText(enter: Boolean) {
        val t = input.text.toString()
        input.setText("")
        val d = Vd.displayId
        Thread {
            if (t.isNotEmpty()) {
                val esc = t.replace("'", "'\\''").replace(" ", "%s")
                Vd.exec("input -d $d text '$esc'")
            }
            if (enter) try { Vd.service?.key(KeyEvent.KEYCODE_ENTER, 0) } catch (_: Throwable) {}
        }.start()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            val r = Vd.service?.setSurface(holder.surface)
            if (r != null && r != "OK") {
                Vd.log("setSurface: $r")
                Toast.makeText(this, "Viewer failed, see log on main screen", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) { Vd.log("setSurface: $t") }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try { Vd.service?.clearSurface() } catch (_: Throwable) {}
    }
}
