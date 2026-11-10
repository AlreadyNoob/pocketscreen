package com.hiderlab.vd

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Outline
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Small theme toolkit (light, dark, Android 12+ system colors) so the screens need no XML or libraries. */
object Ui {
    // Palette: set by applyTheme(). Read these at view-build time, not at class-load time.
    var DARK = true
    var BG = 0
    var SURFACE = 0
    var SURFACE2 = 0
    var OUTLINE = 0
    var TEXT = 0
    var MUTED = 0
    var ACCENT = 0
    var ON_ACCENT = 0
    var ACCENT_CONT = 0
    var ON_ACCENT_CONT = 0
    var OK = 0
    var WARN = 0
    var BAD = 0
    private var DANGER_BG = 0
    private var DANGER_FG = 0
    private var themeKey = ""

    init { setPalette(true, false, null) }

    enum class Kind { FILLED, TONAL, TEXT, DANGER, DANGER_TEXT }

    private fun isNight(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun sysColor(ctx: Context, id: Int): Int = ctx.getColor(id)

    /** Picks light/dark from the system and, on Android 12+, wallpaper colors when [dynamic] is on. */
    fun applyTheme(ctx: Context) {
        val night = isNight(ctx)
        val dyn = Build.VERSION.SDK_INT >= 31 && AppSettings.dynamicColor(ctx)
        try { setPalette(night, dyn, if (dyn) ctx else null) }
        catch (_: Throwable) { setPalette(night, false, null) } // system colors missing on this device
        themeKey = key(ctx)
    }

    private fun key(ctx: Context): String {
        val dyn = Build.VERSION.SDK_INT >= 31 && AppSettings.dynamicColor(ctx)
        val acc = if (dyn) try { sysColor(ctx, android.R.color.system_accent1_600) } catch (_: Throwable) { 0 } else 0
        return "${isNight(ctx)}|$dyn|$acc"
    }

    /** True when the system theme or wallpaper colors changed since [applyTheme]. */
    fun themeChanged(ctx: Context): Boolean = key(ctx) != themeKey

    private fun setPalette(night: Boolean, dynamic: Boolean, ctx: Context?) {
        DARK = night
        if (night) {
            BG = Color.parseColor("#0A0C0F"); SURFACE = Color.parseColor("#12161B"); SURFACE2 = Color.parseColor("#1B2128")
            OUTLINE = Color.parseColor("#29313A"); TEXT = Color.parseColor("#EEF2F6"); MUTED = Color.parseColor("#8E99A6")
            ACCENT = Color.parseColor("#5EE6C8"); ON_ACCENT = Color.parseColor("#00251D")
            ACCENT_CONT = Color.parseColor("#14403A"); ON_ACCENT_CONT = Color.parseColor("#BDF7EA")
            OK = Color.parseColor("#3DD68C"); WARN = Color.parseColor("#F2B84B"); BAD = Color.parseColor("#FF6B6B")
            DANGER_BG = Color.parseColor("#3B1B1F"); DANGER_FG = Color.parseColor("#FFB3B0")
        } else {
            BG = Color.parseColor("#F5F6F8"); SURFACE = Color.parseColor("#FFFFFF"); SURFACE2 = Color.parseColor("#ECEFF3")
            OUTLINE = Color.parseColor("#DDE2E8"); TEXT = Color.parseColor("#12171C"); MUTED = Color.parseColor("#596370")
            ACCENT = Color.parseColor("#0B7D6A"); ON_ACCENT = Color.parseColor("#FFFFFF")
            ACCENT_CONT = Color.parseColor("#D3F2EA"); ON_ACCENT_CONT = Color.parseColor("#04382F")
            OK = Color.parseColor("#15803D"); WARN = Color.parseColor("#B45309"); BAD = Color.parseColor("#C62828")
            DANGER_BG = Color.parseColor("#FCE4E2"); DANGER_FG = Color.parseColor("#B3261E")
        }
        if (dynamic && ctx != null && Build.VERSION.SDK_INT >= 31) {
            fun c(id: Int): Int = sysColor(ctx, id)
            if (night) {
                ACCENT = c(android.R.color.system_accent1_200); ON_ACCENT = c(android.R.color.system_accent1_800)
                ACCENT_CONT = c(android.R.color.system_accent1_700); ON_ACCENT_CONT = c(android.R.color.system_accent1_100)
                BG = c(android.R.color.system_neutral1_900); SURFACE = c(android.R.color.system_neutral1_800); SURFACE2 = c(android.R.color.system_neutral2_700)
                OUTLINE = c(android.R.color.system_neutral2_600); TEXT = c(android.R.color.system_neutral1_100); MUTED = c(android.R.color.system_neutral2_300)
            } else {
                ACCENT = c(android.R.color.system_accent1_600); ON_ACCENT = c(android.R.color.system_accent1_0)
                ACCENT_CONT = c(android.R.color.system_accent1_100); ON_ACCENT_CONT = c(android.R.color.system_accent1_900)
                BG = c(android.R.color.system_neutral1_50); SURFACE = c(android.R.color.system_neutral1_0); SURFACE2 = c(android.R.color.system_neutral2_100)
                OUTLINE = c(android.R.color.system_neutral2_200); TEXT = c(android.R.color.system_neutral1_900); MUTED = c(android.R.color.system_neutral2_600)
            }
        }
    }

    /** Status/navigation bar icons that stay readable on the current background. */
    @Suppress("DEPRECATION")
    fun styleSystemBars(a: Activity) {
        try { styleBars(a) } catch (_: Throwable) {}
    }

    @Suppress("DEPRECATION")
    private fun styleBars(a: Activity) {
        val w = a.window
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (DARK) 0 else mask, mask)
        } else {
            var f = w.decorView.systemUiVisibility
            f = if (DARK) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                f = if (DARK) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            w.decorView.systemUiVisibility = f
        }
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun shape(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.setCornerRadius(radius)
        if (strokeW > 0) g.setStroke(strokeW, stroke)
        return g
    }

    private fun rippleColor(): Int = (TEXT and 0x00FFFFFF) or 0x22000000

    fun withRipple(base: Drawable?, radius: Float): Drawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.setCornerRadius(radius)
        return RippleDrawable(ColorStateList.valueOf(rippleColor()), base, mask)
    }

    fun withRippleRadii(base: Drawable?, radii: FloatArray): Drawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.cornerRadii = radii
        return RippleDrawable(ColorStateList.valueOf(rippleColor()), base, mask)
    }

    fun rippleCircle(): Drawable {
        val mask = GradientDrawable()
        mask.setShape(GradientDrawable.OVAL)
        mask.setColor(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(rippleColor()), null, mask)
    }

    /** Gentle scale-down while a view is pressed. Never consumes the touch, so clicks and ripples still work. */
    @Suppress("ClickableViewAccessibility")
    fun pressable(v: View, scale: Float = 0.97f) {
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }

    /** Hairline used on cards and groups so surfaces read as crisp layers on dark backgrounds. */
    fun hair(): Int = (OUTLINE and 0x00FFFFFF) or 0xB0000000.toInt()

    fun surface(ctx: Context, radius: Int = 20, clickable: Boolean = false): Drawable {
        val r = dpf(ctx, radius.toFloat())
        val base = shape(SURFACE, r, hair(), dp(ctx, 1))
        return if (clickable) withRipple(base, r) else base
    }

    /**
     * Puts [rows] into [parent] as one grouped list: large outer corners, small corners between rows,
     * thin gaps. Call after the click listeners are set so clickable rows get a ripple and press feedback.
     */
    fun group(parent: LinearLayout, rows: List<View>, top: Int = 0) {
        val ctx = parent.context
        val big = dpf(ctx, 20f)
        val small = dpf(ctx, 6f)
        for ((i, v) in rows.withIndex()) {
            val t = if (i == 0) big else small
            val b = if (i == rows.size - 1) big else small
            val radii = floatArrayOf(t, t, t, t, b, b, b, b)
            val base = GradientDrawable()
            base.setColor(SURFACE)
            base.cornerRadii = radii
            base.setStroke(dp(ctx, 1), hair())
            if (v.isClickable) {
                v.background = withRippleRadii(base, radii)
                pressable(v, 0.985f)
            } else v.background = base
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(ctx, if (i == 0) top else 3)
            parent.addView(v, lp)
        }
    }

    /** Small accent-colored heading above a group. */
    fun sectionLabel(ctx: Context, title: String): TextView {
        val t = text(ctx, title.uppercase(), 11.5f, MUTED, bold = true)
        t.letterSpacing = 0.1f
        t.setPadding(dp(ctx, 6), 0, dp(ctx, 6), 0)
        return t
    }

    /** Small rounded label, for counts and per-app facts. */
    fun pill(ctx: Context, label: String, fg: Int = MUTED, bg: Int = SURFACE2, sp: Float = 12f): TextView {
        val t = text(ctx, label, sp, fg, bold = true)
        t.setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        t.background = shape(bg, dpf(ctx, 12f))
        t.fontFeatureSettings = "tnum"
        return t
    }

    /** Small filled circle, e.g. a status light. */
    fun dot(ctx: Context, color: Int, sizeDp: Int = 8): View {
        val d = View(ctx)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(color)
        d.background = g
        d.minimumWidth = dp(ctx, sizeDp)
        d.minimumHeight = dp(ctx, sizeDp)
        return d
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        if (sp >= 20f) t.letterSpacing = -0.015f // large titles sit tighter
        return t
    }

    /** Styles an EditText as a filled field that gets an accent outline while focused. */
    fun styleInput(ctx: Context, e: EditText) {
        e.setTextColor(TEXT)
        e.setHintTextColor(MUTED)
        e.textSize = 16f
        e.setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 14))
        val r = dpf(ctx, 14f)
        val sl = StateListDrawable()
        sl.addState(intArrayOf(android.R.attr.state_focused), shape(SURFACE2, r, ACCENT, dp(ctx, 2)))
        sl.addState(intArrayOf(), shape(SURFACE2, r, hair(), dp(ctx, 1)))
        e.background = sl
        if (Build.VERSION.SDK_INT >= 29) {
            val cur = GradientDrawable()
            cur.setColor(ACCENT)
            cur.setSize(dp(ctx, 2), dp(ctx, 22))
            e.textCursorDrawable = cur
        }
    }

    fun button(ctx: Context, label: String, kind: Kind = Kind.TONAL, onClick: () -> Unit): TextView {
        val bg: Int
        val fg: Int
        when (kind) {
            Kind.FILLED -> { bg = ACCENT; fg = ON_ACCENT }
            Kind.TONAL -> { bg = SURFACE2; fg = TEXT }
            Kind.TEXT -> { bg = Color.TRANSPARENT; fg = ACCENT }
            Kind.DANGER -> { bg = DANGER_BG; fg = DANGER_FG }
            Kind.DANGER_TEXT -> { bg = Color.TRANSPARENT; fg = DANGER_FG }
        }
        val t = TextView(ctx)
        t.text = label
        t.setTextColor(fg)
        t.textSize = 15f
        t.letterSpacing = 0.01f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(ctx, 52)
        t.setPadding(dp(ctx, 22), 0, dp(ctx, 22), 0)
        val outlined = kind == Kind.TONAL
        t.background = withRipple(
            if (outlined) shape(bg, dpf(ctx, 26f), hair(), dp(ctx, 1)) else shape(bg, dpf(ctx, 26f)),
            dpf(ctx, 26f)
        )
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        pressable(t)
        return t
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, 20), dp(ctx, 18), dp(ctx, 20), dp(ctx, 18))
        c.background = surface(ctx)
        return c
    }

    fun chip(ctx: Context, dot: Int, label: String): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(ctx, 12), dp(ctx, 7), dp(ctx, 14), dp(ctx, 7))
        c.background = shape(SURFACE, dpf(ctx, 18f), hair(), dp(ctx, 1))
        val d = View(ctx)
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(dot)
        d.background = g
        val lp = LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8))
        lp.rightMargin = dp(ctx, 7)
        c.addView(d, lp)
        c.addView(text(ctx, label, 12f, TEXT, bold = true))
        return c
    }

    /** Extended floating action button: icon + label, pill shaped, with a shadow. */
    fun fab(ctx: Context, label: String, onClick: () -> Unit): LinearLayout {
        val f = LinearLayout(ctx)
        f.orientation = LinearLayout.HORIZONTAL
        f.gravity = Gravity.CENTER_VERTICAL
        f.setPadding(dp(ctx, 20), 0, dp(ctx, 24), 0)
        f.minimumHeight = dp(ctx, 60)
        val r = dpf(ctx, 30f)
        f.background = withRipple(shape(ACCENT, r), r)
        f.isClickable = true
        f.isFocusable = true
        f.elevation = dpf(ctx, 8f)
        if (Build.VERSION.SDK_INT >= 28) {
            f.outlineAmbientShadowColor = ACCENT
            f.outlineSpotShadowColor = ACCENT
        }
        f.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.height / 2f)
            }
        }
        val ic = IconView(ctx, IconView.PLUS, 24, false)
        ic.tint = ON_ACCENT
        f.addView(ic, LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 24)))
        val t = text(ctx, label, 16f, ON_ACCENT, bold = true)
        val tl = LinearLayout.LayoutParams(-2, -2)
        tl.leftMargin = dp(ctx, 12)
        f.addView(t, tl)
        f.setOnClickListener { onClick() }
        pressable(f)
        return f
    }

    /** Fades and lifts [views] in one after another. Used once when a screen first appears. */
    fun reveal(views: List<View>, stepMs: Long = 45L) {
        for ((i, v) in views.withIndex()) {
            v.alpha = 0f
            v.translationY = dpf(v.context, 14f)
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L + i * stepMs).setDuration(320)
                .setInterpolator(DecelerateInterpolator(1.6f)).start()
        }
    }

    fun add(parent: LinearLayout, v: View, top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT,
            h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
        val lp = LinearLayout.LayoutParams(w, h, weight)
        lp.topMargin = dp(parent.context, top)
        parent.addView(v, lp)
        return v
    }

    /** Pads [v] by the system bars / cutout (and optionally the keyboard) so content is never covered. */
    fun applyInsets(v: View, ime: Boolean = false, onIme: ((visible: Boolean, height: Int) -> Unit)? = null,
                    onInsets: ((WindowInsets) -> Unit)? = null) {
        v.setOnApplyWindowInsetsListener { view, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                var types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                if (ime) types = types or WindowInsets.Type.ime()
                val i = ins.getInsets(types)
                view.setPadding(i.left, i.top, i.right, i.bottom)
                onIme?.invoke(ins.isVisible(WindowInsets.Type.ime()), ins.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                view.setPadding(
                    ins.systemWindowInsetLeft, ins.systemWindowInsetTop,
                    ins.systemWindowInsetRight, ins.systemWindowInsetBottom
                )
            }
            onInsets?.invoke(ins)
            ins
        }
    }

    class MenuItem(val label: String, val danger: Boolean = false, val action: (() -> Unit)? = null)

    /** A rounded dropdown menu under [anchor]. An item with no action is shown as a muted label. */
    fun menu(a: Activity, anchor: View, items: List<MenuItem>) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(a, 8), 0, dp(a, 8))
        box.background = shape(SURFACE2, dpf(a, 18f), hair(), dp(a, 1))
        box.clipToOutline = true
        box.elevation = dpf(a, 12f)
        val pw = PopupWindow(box, dp(a, 264), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        for (item in items) {
            val act = item.action
            val t = TextView(a)
            t.text = item.label
            t.gravity = Gravity.CENTER_VERTICAL
            t.minHeight = dp(a, 52)
            t.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), dp(a, 8))
            if (act == null) {
                t.textSize = 12f
                t.setTextColor(MUTED)
            } else {
                t.textSize = 15.5f
                t.setTextColor(if (item.danger) DANGER_FG else TEXT)
                t.background = withRipple(null, 0f)
                t.isClickable = true
                t.isFocusable = true
                t.setOnClickListener { pw.dismiss(); act() }
            }
            box.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        box.alpha = 0f
        box.translationY = -dpf(a, 10f)
        pw.showAsDropDown(anchor, 0, 0, Gravity.END)
        box.animate().alpha(1f).translationY(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    /** A bottom sheet. [heightFrac] of 0 wraps the content; otherwise it is a fraction of the screen height. */
    fun sheet(a: Activity, heightFrac: Float = 0f, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = dpf(a, 28f)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(SURFACE)
        g.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
        g.setStroke(dp(a, 1), hair())
        body.background = g
        body.setPadding(dp(a, 22), dp(a, 12), dp(a, 22), dp(a, 28))

        val handle = View(a)
        handle.background = shape(OUTLINE, dpf(a, 3f))
        val hp = LinearLayout.LayoutParams(dp(a, 36), dp(a, 4))
        hp.gravity = Gravity.CENTER_HORIZONTAL
        hp.bottomMargin = dp(a, 16)
        body.addView(handle, hp)

        build(body, d)
        d.setContentView(body)
        val w = d.window
        if (w != null) {
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setGravity(Gravity.BOTTOM)
            val h = if (heightFrac > 0f) (a.resources.displayMetrics.heightPixels * heightFrac).toInt()
            else ViewGroup.LayoutParams.WRAP_CONTENT
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.setDimAmount(0.62f)
        }
        // Slide up into place.
        body.translationY = dpf(a, 480f)
        d.show()
        body.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator(1.8f)).start()
        return d
    }
}

/** Line icons drawn with a Canvas, so there are no drawable resources to ship. */
class IconView(ctx: Context, private val kind: Int, private val sizeDp: Int = 48, interactive: Boolean = true) : View(ctx) {
    companion object {
        const val BACK = 0
        const val KEYBOARD = 1
        const val MORE = 2
        const val PLUS = 3
        const val CHECK = 4
        const val CHEVRON = 5
        const val SETTINGS = 6
        const val VOLUME = 7
        const val MUTED = 8
    }

    var tint: Int = Ui.TEXT
        set(v) { field = v; invalidate() }

    /** Small dot in the corner, used to flag unread log output. */
    var badge: Boolean = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        p.strokeWidth = Ui.dpf(ctx, 2.2f)
        if (interactive) {
            background = Ui.rippleCircle()
            isClickable = true
            isFocusable = true
        }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = Ui.dp(context, sizeDp)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        drawIcon(c)
        if (badge) {
            p.style = Paint.Style.FILL
            p.color = Ui.ACCENT
            c.drawCircle(width * 0.68f, height * 0.32f, Ui.dpf(context, 4.5f), p)
        }
    }

    private fun drawIcon(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        p.color = tint
        p.style = Paint.Style.STROKE
        if (kind == BACK) {
            val s = minOf(width, height) * 0.24f
            c.drawLine(cx - s, cy, cx + s, cy, p)
            val path = Path()
            path.moveTo(cx - s + s * 0.9f, cy - s * 0.9f)
            path.lineTo(cx - s, cy)
            path.lineTo(cx - s + s * 0.9f, cy + s * 0.9f)
            c.drawPath(path, p)
        } else if (kind == PLUS) {
            val s = minOf(width, height) * 0.32f
            c.drawLine(cx - s, cy, cx + s, cy, p)
            c.drawLine(cx, cy - s, cx, cy + s, p)
        } else if (kind == CHECK) {
            val s = minOf(width, height) * 0.28f
            val path = Path()
            path.moveTo(cx - s, cy + s * 0.05f)
            path.lineTo(cx - s * 0.3f, cy + s * 0.75f)
            path.lineTo(cx + s, cy - s * 0.7f)
            c.drawPath(path, p)
        } else if (kind == CHEVRON) {
            val s = minOf(width, height) * 0.2f
            val path = Path()
            path.moveTo(cx - s * 0.5f, cy - s)
            path.lineTo(cx + s * 0.5f, cy)
            path.lineTo(cx - s * 0.5f, cy + s)
            c.drawPath(path, p)
        } else if (kind == MORE) {
            val gap = minOf(width, height) * 0.16f
            val r = Ui.dpf(context, 2.4f)
            p.style = Paint.Style.FILL
            c.drawCircle(cx, cy - gap, r, p)
            c.drawCircle(cx, cy, r, p)
            c.drawCircle(cx, cy + gap, r, p)
        } else if (kind == SETTINGS) {
            // Gear: 8 rounded teeth around a ring, with a round hole. 24-unit grid, centred.
            val u = minOf(width, height) / 48f * 1.1f
            val rOut = 10f * u
            val rValley = 7.7f * u
            val gear = Path()
            val toothHalf = 9.0
            val baseHalf = 13.0
            val valley = RectF(cx - rValley, cy - rValley, cx + rValley, cy + rValley)
            val crest = RectF(cx - rOut, cy - rOut, cx + rOut, cy + rOut)
            gear.moveTo(
                cx + rValley * Math.cos(Math.toRadians(-45.0 + baseHalf)).toFloat(),
                cy + rValley * Math.sin(Math.toRadians(-45.0 + baseHalf)).toFloat()
            )
            for (k in 0 until 8) {
                val a = 45.0 * k
                gear.arcTo(valley, (a - 45.0 + baseHalf).toFloat(), (45.0 - 2 * baseHalf).toFloat())
                gear.lineTo(
                    cx + rOut * Math.cos(Math.toRadians(a - toothHalf)).toFloat(),
                    cy + rOut * Math.sin(Math.toRadians(a - toothHalf)).toFloat()
                )
                gear.arcTo(crest, (a - toothHalf).toFloat(), (2 * toothHalf).toFloat())
            }
            gear.close()
            c.drawPath(gear, p)
            c.drawCircle(cx, cy, 3.2f * u, p)
        } else if (kind == VOLUME || kind == MUTED) {
            // Speaker + waves (or X). Same footprint for both so the icon never jumps when toggled.
            val u = minOf(width, height) / 48f * 1.1f
            fun gx(x: Float) = cx + (x - 12f) * u
            fun gy(y: Float) = cy + (y - 12f) * u
            val body = Path()
            body.moveTo(gx(11f), gy(5f))
            body.lineTo(gx(6.5f), gy(9f))
            body.lineTo(gx(3f), gy(9f))
            body.lineTo(gx(3f), gy(15f))
            body.lineTo(gx(6.5f), gy(15f))
            body.lineTo(gx(11f), gy(19f))
            body.close()
            c.drawPath(body, p)
            if (kind == VOLUME) {
                for (r in floatArrayOf(4.6f, 8.6f)) {
                    c.drawArc(RectF(gx(11f - r), gy(12f - r), gx(11f + r), gy(12f + r)), -45f, 90f, false, p)
                }
            } else {
                c.drawLine(gx(14.8f), gy(8.8f), gx(21f), gy(15.2f), p)
                c.drawLine(gx(14.8f), gy(15.2f), gx(21f), gy(8.8f), p)
            }
        } else {
            val s = minOf(width, height) * 0.2f
            val w = s * 1.7f
            val h = s * 1.15f
            c.drawRoundRect(RectF(cx - w, cy - h, cx + w, cy + h), s * 0.35f, s * 0.35f, p)
            p.style = Paint.Style.FILL
            val r = p.strokeWidth * 0.55f
            for (i in -2..2) c.drawCircle(cx + i * s * 0.55f, cy - h * 0.4f, r, p)
            for (i in -1..1) c.drawCircle(cx + i * s * 0.55f, cy + h * 0.05f, r, p)
            p.style = Paint.Style.STROKE
            c.drawLine(cx - s * 0.9f, cy + h * 0.5f, cx + s * 0.9f, cy + h * 0.5f, p)
        }
    }
}

/** Outline of a phone, upright or on its side. */
class PhoneIcon(ctx: Context, private val landscape: Boolean, private val tint: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(ctx, 2.2f)
        p.color = tint
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = Ui.dp(context, 56)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val long = Ui.dpf(context, 44f)
        val short = Ui.dpf(context, 26f)
        val w = if (landscape) long else short
        val h = if (landscape) short else long
        val cx = width / 2f
        val cy = height / 2f
        val r = Ui.dpf(context, 6f)
        c.drawRoundRect(RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2), r, r, p)
    }
}


/** A Material-style switch: pill track with a thumb that slides and grows when on. */
class SwitchView(ctx: Context) : View(ctx) {
    var isOn: Boolean = false
        private set
    private var progress = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()

    fun set(on: Boolean, animate: Boolean = true) {
        if (on == isOn && animate) return
        isOn = on
        if (animate && Build.VERSION.SDK_INT >= 27) performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
        val to = if (on) 1f else 0f
        if (!animate) { progress = to; invalidate(); return }
        val va = ValueAnimator.ofFloat(progress, to)
        va.duration = 200
        va.addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        va.start()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(Ui.dp(context, 50), Ui.dp(context, 30))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val sw = Ui.dpf(context, 2f)
        // track
        p.style = Paint.Style.FILL
        p.color = argb.evaluate(progress, Ui.SURFACE2, Ui.ACCENT) as Int
        c.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, p)
        if (progress < 1f) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = (Ui.MUTED and 0x00FFFFFF) or (((1f - progress) * 255).toInt() shl 24)
            c.drawRoundRect(RectF(sw / 2, sw / 2, w - sw / 2, h - sw / 2), h / 2, h / 2, p)
        }
        // thumb
        val rOff = Ui.dpf(context, 7f)
        val rOn = Ui.dpf(context, 11f)
        val r = rOff + (rOn - rOff) * progress
        val pad = h / 2
        val cx = pad + (w - 2 * pad) * progress
        p.style = Paint.Style.FILL
        p.color = argb.evaluate(progress, Ui.MUTED, Ui.ON_ACCENT) as Int
        c.drawCircle(cx, h / 2, r, p)
    }
}


/** The PocketScreen mark: a phone outline holding a smaller "hidden" screen, on a tinted tile. */
class LogoView(ctx: Context, private val sizeDp: Int = 44) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = Ui.dp(context, sizeDp)
        setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = Ui.ACCENT_CONT
        c.drawRoundRect(RectF(0f, 0f, w, h), w * 0.3f, w * 0.3f, p)
        val cx = w / 2f
        val cy = h / 2f
        val pw = w * 0.26f
        val ph = h * 0.36f
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(context, 2f)
        p.color = Ui.ACCENT
        c.drawRoundRect(RectF(cx - pw, cy - ph, cx + pw, cy + ph), pw * 0.4f, pw * 0.4f, p)
        p.style = Paint.Style.FILL
        p.color = (Ui.ACCENT and 0x00FFFFFF) or 0x66000000
        val iw = pw * 0.56f
        val ih = ph * 0.58f
        c.drawRoundRect(RectF(cx - iw, cy - ih * 0.7f, cx + iw, cy + ih * 1.1f), iw * 0.3f, iw * 0.3f, p)
    }
}
