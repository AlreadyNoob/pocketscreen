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
 * Back (an edge swipe recognised here, or the back key) goes to the app or is ignored, per Settings;
 * the arrow in the top bar is the way out.
 */
class ViewerActivity : Activity(), SurfaceHolder.Callback, InputSink {

    private lateinit var sv: InputSurfaceView
    private var suppressKb = false
    private var backControls = true
    private var lastBack = 0L
    private var barsShown = false
    // Edge swipe -> Back, done here because the system's own back swipe is excluded (see updateGestureExclusion).
    private var edgeTracking = false
    private var edgeFired = false
    private var edgeStartX = 0f
    private var edgeStartY = 0f
    private var edgeFromLeft = false
    private var pkgOnDisplay: String? = null
    private var lastSub = ""
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
                    // Only touch the view when something changed: re-setting the same text still
                    // triggers a layout pass, which shows up as a hitch once a second.
                    val err = Vd.inputError.isNotEmpty()
                    val txt = if (err) "Input error: ${Vd.inputError}" else base
                    if (txt != lastSub) {
                        lastSub = txt
                        subView.setTextColor(if (err) Ui.BAD else Ui.MUTED)
                        subView.text = txt
                    }
                }
            }.start()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applyTheme(this)
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

        backControls = AppSettings.backGesture(this)
        // Back gestures go to the app on the display (or are swallowed if turned off in Settings).
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                OnBackInvokedCallback { sendBack() }
            )
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.BLACK)
        // The keyboard overlays the display instead of resizing it; the display is panned instead.
        Ui.applyInsets(root, ime = false, onIme = { visible, height ->
            val wasVisible = kbVisible
            kbVisible = visible
            imeHeight = height
            // Only on the shown -> hidden change, so a keyboard that is still opening isn't cancelled.
            if (wasVisible && !visible && ::sv.isInitialized && suppressKb) setEditor(false)
            if (::kbIcon.isInitialized) kbIcon.tint = if (visible) Ui.ACCENT else Ui.TEXT
            if (::sv.isInitialized) updatePan()
        }, onInsets = { ins ->
            // For the debug log only: when do the system bars show up?
            if (Build.VERSION.SDK_INT >= 30) {
                val shown = ins.isVisible(WindowInsets.Type.statusBars()) || ins.isVisible(WindowInsets.Type.navigationBars())
                if (shown != barsShown) { barsShown = shown; Debug.write("viewer: system bars ${if (shown) "shown" else "hidden"}") }
            }
        })

        // ---- top bar: back arrow, title, keyboard toggle ----
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(Ui.BG)

        fun barButton(ic: IconView): IconView {
            val r = Ui.dpf(this, Ui.R_FIELD.toFloat() - 2f)
            ic.background = Ui.withRipple(Ui.shape(Ui.SURFACE2, r), r)
            return ic
        }
        val back = barButton(IconView(this, IconView.BACK, 36))
        back.contentDescription = "Back to main menu"
        back.setOnClickListener { leave() }
        val bp = LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36))
        bp.leftMargin = Ui.dp(this, 8)
        bp.rightMargin = Ui.dp(this, 4)
        bar.addView(back, bp)

        val titles = LinearLayout(this)
        titles.orientation = LinearLayout.VERTICAL
        titleView = Ui.text(this, "Display #$displayId", 15f, Ui.TEXT, bold = true)
        titleView.isSingleLine = true
        titleView.ellipsize = TextUtils.TruncateAt.END
        subView = Ui.text(this, "", 12f, Ui.MUTED)
        subView.fontFeatureSettings = "tnum"
        subView.isSingleLine = true
        subView.ellipsize = TextUtils.TruncateAt.END
        titles.addView(titleView)
        titles.addView(subView)
        val tp = LinearLayout.LayoutParams(0, -2, 1f)
        tp.leftMargin = Ui.dp(this, 6)
        bar.addView(titles, tp)

        kbIcon = barButton(IconView(this, IconView.KEYBOARD, 36))
        kbIcon.contentDescription = "Toggle keyboard"
        kbIcon.setOnClickListener { toggleKeyboard() }
        val kp = LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 36))
        kp.leftMargin = Ui.dp(this, 4)
        kp.rightMargin = Ui.dp(this, 8)
        bar.addView(kbIcon, kp)
        root.addView(bar, LinearLayout.LayoutParams(-1, Ui.dp(this, 48)))
        // hairline between the bar and the display
        val rule = View(this)
        rule.setBackgroundColor(Ui.hair())
        root.addView(rule, LinearLayout.LayoutParams(-1, 1))

        // ---- the display itself ----
        box = FrameLayout(this)
        sv = InputSurfaceView(this)
        sv.sink = this
        suppressKb = AppSettings.suppressKeyboard(this)
        if (suppressKb) sv.editorAllowed = false
        box.addView(sv, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        root.addView(box, LinearLayout.LayoutParams(-1, 0, 1f))
        // The exclusion goes on the full-width box, so it covers the real screen edges even when the
        // display is letterboxed inside it.
        box.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit(); updatePan() }
        window.decorView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateGestureExclusion() }

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
        val masked = e.actionMasked
        val action = when (masked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> masked
            else -> return true
        }
        // Every finger goes to the display, so multi-touch works.
        val n = e.pointerCount
        val ids = IntArray(n)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            ids[i] = e.getPointerId(i)
            val bx = (e.getX(i) * dw / v.width).coerceIn(0f, (dw - 1).toFloat())
            val by = (e.getY(i) * dh / v.height).coerceIn(0f, (dh - 1).toFloat())
            // buffer coordinates -> the display's rotated (logical) coordinates
            when (rot) {
                1 -> { xs[i] = by; ys[i] = dw - 1 - bx }
                3 -> { xs[i] = dh - 1 - by; ys[i] = bx }
                2 -> { xs[i] = dw - 1 - bx; ys[i] = dh - 1 - by }
                else -> { xs[i] = bx; ys[i] = by }
            }
        }
        touches++
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP) {
            lastTapFrac = (e.y / v.height).coerceIn(0f, 1f)
            if (kbVisible) updatePan()
        }
        try { Vd.service?.touchPointers(displayId, action, e.actionIndex, ids, xs, ys) } catch (_: Throwable) {}
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
        sendBack()
    }

    /** Back never closes the viewer; it is sent to the app on the display, or ignored if turned off. */
    private fun sendBack() {
        if (!backControls) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBack < 200) return // the same gesture can arrive through two paths
        lastBack = now
        try {
            Vd.service?.key(displayId, KeyEvent.KEYCODE_BACK, 0)
        } catch (_: Throwable) {}
    }

    /**
     * Back from a swipe that starts at the left or right screen edge. The edges are excluded from
     * the system gestures, so Android never sees this swipe and has no reason to reveal the bars;
     * the app gets the touches and recognises the swipe itself. Nothing is swallowed up front: the
     * touches still reach the display until the swipe turns out to be a back swipe, at which point
     * the display's touch is cancelled and Back is sent.
     */
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (!backControls) return super.dispatchTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val w = resources.displayMetrics.widthPixels
                val zone = Ui.dp(this, 32)
                edgeFired = false
                edgeTracking = e.rawX <= zone || e.rawX >= w - zone
                edgeFromLeft = e.rawX <= zone
                edgeStartX = e.rawX
                edgeStartY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> if (edgeTracking && !edgeFired && e.pointerCount == 1) {
                val dx = if (edgeFromLeft) e.rawX - edgeStartX else edgeStartX - e.rawX
                val dy = Math.abs(e.rawY - edgeStartY)
                if (dx > Ui.dp(this, 40) && dx > dy) {
                    edgeFired = true
                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    sendBack()
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> edgeTracking = false
        }
        val swallow = edgeFired
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            edgeTracking = false
            edgeFired = false
        }
        return if (swallow) true else super.dispatchTouchEvent(e)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) sendBack()
            return true
        }
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
        Debug.write("viewer: window focus ${if (hasFocus) "gained" else "lost"}")
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

    /**
     * Excludes the full height of both side edges of the window from the system gestures. While the
     * system bars are hidden (sticky immersive) Android lifts its usual 200dp-per-edge cap, so the
     * system back swipe never starts here and has nothing to reveal the bars with. The bottom home
     * zone cannot be excluded; swiping up from it is the one way to bring the bars back.
     */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val v = window.decorView
        val w = v.width
        val h = v.height
        if (w <= 0 || h <= 0) return
        val edge = Ui.dp(this, 48)
        v.systemGestureExclusionRects = listOf(
            Rect(0, 0, edge, h),
            Rect(w - edge, 0, w, h)
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
            val pkg = Vd.tasks(id).firstOrNull()?.second
            if (names.isNotEmpty()) ui.post { titleView.text = names.joinToString(", ") }
            // Keyboard and back-gesture choices can be set per app.
            if (pkg != null) ui.post { applyPerApp(pkg) }
            if (pkg != null && Debug.enabled(this)) {
                // top-app = all cores, foreground = fewer; useful when the app stutters
                Debug.write("viewer: $pkg scheduling:\n" + Vd.exec(
                    "for p in \$(pidof $pkg); do echo pid=\$p cpuset=\$(cat /proc/\$p/cpuset) oom_adj=\$(cat /proc/\$p/oom_score_adj); done"))
            }
        }.start()
    }

    private fun applyPerApp(pkg: String) {
        pkgOnDisplay = pkg
        backControls = AppSettings.effectiveBackGesture(this, pkg)
        suppressKb = AppSettings.effectiveSuppressKeyboard(this, pkg)
        if (::sv.isInitialized) {
            sv.editorAllowed = !suppressKb || kbVisible
        }
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
        // Without this the phone's screen can drop to a lower rate while it shows the viewer, which
        // would hide the display's higher refresh rate.
        requestPanelRate(holder)
        try {
            val r = Vd.service?.setSurface(displayId, holder.surface)
            if (r != null && r != "OK") {
                Vd.log("setSurface: $r")
                Toast.makeText(this, "Viewer failed, see log on main screen", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) { Vd.log("setSurface: $t") }
    }

    /**
     * The hidden display is paced by the phone's own screen, so it can only run as fast as the
     * screen does. While the viewer is open, ask the screen for the display's rate: a frame-rate
     * vote on the viewer's surface, plus the matching screen mode for the window.
     */
    private fun requestPanelRate(holder: SurfaceHolder) {
        val hz = Vd.display(displayId)?.fps ?: 0
        if (hz <= 0) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                holder.surface.setFrameRate(hz.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    android.view.Surface.CHANGE_FRAME_RATE_ALWAYS)
            } else if (Build.VERSION.SDK_INT >= 30) {
                holder.surface.setFrameRate(hz.toFloat(), android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
        } catch (t: Throwable) { Debug.write("viewer: setFrameRate failed: $t") }
        try {
            @Suppress("DEPRECATION")
            val disp = if (Build.VERSION.SDK_INT >= 30) display else windowManager.defaultDisplay
            if (disp != null) {
                val cur = disp.mode
                val same = disp.supportedModes.filter {
                    it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
                }
                // The slowest mode that is an exact multiple of the wanted rate, else the slowest that reaches it.
                val best = same.filter { Math.round(it.refreshRate) >= hz && Math.round(it.refreshRate) % hz == 0 }
                    .minByOrNull { it.refreshRate }
                    ?: same.filter { it.refreshRate >= hz - 0.5f }.minByOrNull { it.refreshRate }
                    ?: same.maxByOrNull { it.refreshRate }
                if (best != null) {
                    val lp = window.attributes
                    lp.preferredDisplayModeId = best.modeId
                    lp.preferredRefreshRate = best.refreshRate
                    window.attributes = lp
                    Debug.write("viewer: display wants $hz Hz, asked the screen for ${best.refreshRate} Hz (mode ${best.modeId}); modes: " +
                        same.joinToString { "${it.refreshRate}" })
                }
                ui.postDelayed({ Debug.write("viewer: screen now runs at ${disp.refreshRate} Hz") }, 1500)
            }
        } catch (t: Throwable) { Debug.write("viewer: screen mode request failed: $t") }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        try { Vd.service?.clearSurface(displayId) } catch (_: Throwable) {}
    }
}
