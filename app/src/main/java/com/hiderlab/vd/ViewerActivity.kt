package com.hiderlab.vd

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Live view of one hidden display (pass its id as the "display" extra). Touches go to the
 * display, the phone's keyboard (or a physical one) types straight into the focused app.
 * Back gestures are swallowed; the arrow in the top bar is the only way out.
 */
class ViewerActivity : Activity(), SurfaceHolder.Callback, InputSink {

    private lateinit var sv: InputSurfaceView
    private lateinit var box: FrameLayout
    private lateinit var titleView: TextView
    private lateinit var subView: TextView
    private lateinit var kbIcon: IconView
    private var displayId = -1
    private var dw = 720
    private var dh = 1280
    private var landscape = false
    private var rot = 0
    private var touches = 0
    private var kbVisible = false
    private val ui = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            Thread {
                Vd.refreshInfo()
                val gone = Vd.display(displayId) == null
                val r = try { Vd.service?.rotation(displayId) ?: 0 } catch (_: Throwable) { 0 }
                ui.post {
                    if (gone) { leave(); return@post }
                    rot = r
                    val base = (if (landscape) "Landscape" else "Portrait") + " · ${dw}×${dh}" +
                        (if (rot != 0) " · rotated ${rot * 90}°" else "")
                    if (Vd.inputError.isEmpty()) {
                        subView.setTextColor(Ui.MUTED)
                        subView.text = base
                    } else {
                        subView.setTextColor(Ui.BAD)
                        subView.text = "Input error: ${Vd.inputError}"
                    }
                }
            }.start()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        displayId = intent.getIntExtra("display", -1)
        val d = Vd.display(displayId)
        if (Vd.service == null || d == null) {
            Toast.makeText(this, "That hidden display is gone.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        dw = d.w
        dh = d.h
        landscape = d.landscape

        // Match the phone's orientation to the display so it fills the screen.
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))

        // Swallow every kind of back gesture / button.
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { }
            )
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        Ui.applyInsets(root, ime = true) { visible ->
            kbVisible = visible
            if (::kbIcon.isInitialized) kbIcon.tint = if (visible) Ui.ACCENT else Ui.TEXT
        }

        // ---- top bar: back arrow, title, keyboard toggle ----
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ui.BG)

        val back = IconView(this, IconView.BACK)
        back.contentDescription = "Back to main menu"
        back.setOnClickListener { leave() }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titleView = Ui.text(this, "Display #$displayId", 15f, Ui.TEXT, bold = true)
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        subView = Ui.text(this, "", 11f, Ui.MUTED)
        subView.isSingleLine = true
        subView.ellipsize = TextUtils.TruncateAt.END
        titles.addView(titleView)
        titles.addView(subView)
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        kbIcon = IconView(this, IconView.KEYBOARD)
        kbIcon.contentDescription = "Toggle keyboard"
        kbIcon.setOnClickListener { toggleKeyboard() }
        bar.addView(kbIcon, LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)))
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)))

        // ---- the display itself ----
        box = FrameLayout(this)
        sv = InputSurfaceView(this)
        sv.sink = this
        box.addView(sv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
        sv.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> updateGestureExclusion(v) }

        sv.holder.setFixedSize(dw, dh)
        sv.holder.addCallback(this)
        sv.setOnTouchListener { v, e -> onDisplayTouch(v, e) }

        setContentView(root)
        immersive()
        sv.requestFocus()
        loadAppName()
    }

    // ---- touch ----

    private fun onDisplayTouch(v: View, e: MotionEvent): Boolean {
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> MotionEvent.ACTION_DOWN
            MotionEvent.ACTION_MOVE -> MotionEvent.ACTION_MOVE
            MotionEvent.ACTION_UP -> MotionEvent.ACTION_UP
            MotionEvent.ACTION_CANCEL -> MotionEvent.ACTION_CANCEL
            else -> return true
        }
        val bx = (e.x * dw / v.width).coerceIn(0f, (dw - 1).toFloat())
        val by = (e.y * dh / v.height).coerceIn(0f, (dh - 1).toFloat())
        // buffer coordinates -> the display's rotated (logical) coordinates
        val x: Float
        val y: Float
        when (rot) {
            1 -> { x = by; y = dw - 1 - bx }
            3 -> { x = dh - 1 - by; y = bx }
            2 -> { x = dw - 1 - bx; y = dh - 1 - by }
            else -> { x = bx; y = by }
        }
        touches++
        try { Vd.service?.touch(displayId, action, x, y) } catch (_: Throwable) {}
        return true
    }

    // ---- keyboard ----

    override fun text(s: String) {
        try { Vd.service?.typeText(displayId, s) } catch (_: Throwable) {}
    }

    override fun key(action: Int, code: Int, meta: Int) {
        try { Vd.service?.keyEvent(displayId, action, code, meta) } catch (_: Throwable) {}
    }

    private fun toggleKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        if (kbVisible) {
            imm.hideSoftInputFromWindow(sv.windowToken, 0)
            if (Build.VERSION.SDK_INT < 30) setKb(false)
        } else {
            sv.requestFocus()
            imm.showSoftInput(sv, 0)
            if (Build.VERSION.SDK_INT < 30) setKb(true)
        }
    }

    private fun setKb(on: Boolean) {
        kbVisible = on
        kbIcon.tint = if (on) Ui.ACCENT else Ui.TEXT
    }

    // ---- leaving: only via the arrow ----

    private fun leave() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Back is disabled here on purpose.
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    // ---- full screen + edge gestures ----

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val c = window.insetsController
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars())
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    /** Asks Android not to start the system back gesture from the display's left and right edges. */
    private fun updateGestureExclusion(v: View) {
        if (Build.VERSION.SDK_INT < 29) return
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return
        val edge = Ui.dp(this, 36)
        val strip = minOf(h, Ui.dp(this, 200))
        val top = (h - strip) / 2
        v.systemGestureExclusionRects = listOf(
            Rect(0, top, edge, top + strip),
            Rect(w - edge, top, w, top + strip)
        )
    }

    // ---- lifecycle ----

    override fun onResume() {
        super.onResume()
        if (::subView.isInitialized) ui.post(poll)
    }

    override fun onPause() {
        ui.removeCallbacks(poll)
        super.onPause()
    }

    private fun loadAppName() {
        val id = displayId
        Thread {
            val names = Vd.tasks(id).map { p ->
                try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(p.second, 0)).toString()
                } catch (_: Throwable) { p.second }
            }.distinct()
            if (names.isNotEmpty()) ui.post { titleView.text = names.joinToString(", ") }
        }.start()
    }

    private fun fit() {
        val cw = box.width
        val ch = box.height
        if (cw <= 0 || ch <= 0) return
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

    override fun surfaceCreated(holder: SurfaceHolder) {
        try {
            val r = Vd.service?.setSurface(displayId, holder.surface)
            if (r != null && r != "OK") {
                Vd.log("setSurface: $r")
                Toast.makeText(this, "Viewer failed, see log on main screen", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) { Vd.log("setSurface: $t") }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try { Vd.service?.clearSurface(displayId) } catch (_: Throwable) {}
    }
}
