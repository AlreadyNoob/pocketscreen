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
    private var suppressKb = false
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
    private var startedHidden = false
    private var kbToggledByUser = false
    private var imeHeight = 0
    private var lastTapFrac = 0.5f   // where on the display (0 top .. 1 bottom) you last touched
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
        // The keyboard overlays the display instead of resizing it; the display is panned instead.
        Ui.applyInsets(root, ime = false) { visible, height ->
            val wasVisible = kbVisible
            kbVisible = visible
            imeHeight = height
            // Only on the shown -> hidden change, so a keyboard that is still opening isn't cancelled.
            if (wasVisible && !visible && ::sv.isInitialized && suppressKb) setEditor(false)
            if (::kbIcon.isInitialized) kbIcon.tint = if (visible) Ui.ACCENT else Ui.TEXT
            if (::sv.isInitialized) updatePan()
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
        suppressKb = AppSettings.suppressKeyboard(this)
        if (suppressKb) sv.editorAllowed = false
        box.addView(sv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(); updatePan() }
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
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            lastTapFrac = (e.y / v.height).coerceIn(0f, 1f)
            if (kbVisible) updatePan()
        }
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

    /** With "suppress keyboard" on, the surface only counts as a text field while the user has the keyboard open. */
    private fun setEditor(on: Boolean) {
        if (!suppressKb || sv.editorAllowed == on) return
        sv.editorAllowed = on
        getSystemService(InputMethodManager::class.java).restartInput(sv)
    }

    private fun toggleKeyboard() {
        kbToggledByUser = true
        val imm = getSystemService(InputMethodManager::class.java)
        if (kbVisible) {
            imm.hideSoftInputFromWindow(sv.windowToken, 0)
            setEditor(false)
            if (Build.VERSION.SDK_INT < 30) setKb(false)
        } else {
            setEditor(true)
            sv.requestFocus()
            imm.showSoftInput(sv, 0)
            if (Build.VERSION.SDK_INT < 30) setKb(true)
        }
    }

    private fun setKb(on: Boolean) {
        kbVisible = on
        if (on && imeHeight == 0) imeHeight = (box.rootView.height * 0.4f).toInt() // API < 30 can't report it
        kbIcon.tint = if (on) Ui.ACCENT else Ui.TEXT
        updatePan()
    }

    /**
     * With the keyboard open, slides the display up just far enough that the spot you last
     * touched (normally the text field) stays above the keyboard. The display keeps its size.
     */
    private fun updatePan() {
        if (!::box.isInitialized || sv.height <= 0) return
        var shift = 0f
        if (kbVisible && imeHeight > 0) {
            val loc = IntArray(2)
            box.getLocationOnScreen(loc)
            val boxBottom = loc[1] + box.height
            val kbTop = box.rootView.height - imeHeight
            val covered = (boxBottom - kbTop).coerceAtLeast(0)          // part of the box under the keyboard
            if (covered > 0) {
                val tapY = sv.top + sv.height * lastTapFrac               // in box coordinates
                val visible = box.height - covered
                val margin = Ui.dp(this, 48)
                shift = (tapY - (visible - margin)).coerceIn(0f, covered.toFloat())
                // never slide the display's bottom edge above the keyboard
                val svBottomGap = (box.height - (sv.top + sv.height)).coerceAtLeast(0)
                shift = shift.coerceAtMost((covered - svBottomGap).coerceAtLeast(0).toFloat())
            }
        }
        sv.animate().translationY(-shift).setDuration(150).start()
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
        if (hasFocus) {
            immersive()
            if (!startedHidden) {
                // The display is a text editor so typing works, which makes Android want to pop the
                // keyboard up when the viewer opens. Keep it closed until the keyboard icon is tapped.
                startedHidden = true
                hideKeyboard()
                ui.postDelayed({ if (!kbToggledByUser) hideKeyboard() }, 250)
            }
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(sv.windowToken, 0)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.hide(WindowInsets.Type.ime())
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
