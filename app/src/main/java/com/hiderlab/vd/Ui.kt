package com.hiderlab.vd

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** Small dark-theme toolkit so the screens don't need XML, themes or extra libraries. */
object Ui {
    val BG = Color.parseColor("#0D1117")
    val SURFACE = Color.parseColor("#161B22")
    val SURFACE2 = Color.parseColor("#21262D")
    val OUTLINE = Color.parseColor("#2D333B")
    val TEXT = Color.parseColor("#E6EDF3")
    val MUTED = Color.parseColor("#8B949E")
    val ACCENT = Color.parseColor("#7C9DFF")
    val ON_ACCENT = Color.parseColor("#0B1020")
    val OK = Color.parseColor("#3FB950")
    val WARN = Color.parseColor("#D29922")
    val BAD = Color.parseColor("#F85149")
    private val DANGER_BG = Color.parseColor("#3A1D1F")
    private val DANGER_FG = Color.parseColor("#FF8A84")

    enum class Kind { FILLED, TONAL, TEXT, DANGER, DANGER_TEXT }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun shape(color: Int, radius: Float, stroke: Int = 0, strokeW: Int = 0): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.setCornerRadius(radius)
        if (strokeW > 0) g.setStroke(strokeW, stroke)
        return g
    }

    fun withRipple(base: Drawable?, radius: Float): Drawable {
        val mask = GradientDrawable()
        mask.setColor(Color.WHITE)
        mask.setCornerRadius(radius)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), base, mask)
    }

    fun rippleCircle(): Drawable {
        val mask = GradientDrawable()
        mask.setShape(GradientDrawable.OVAL)
        mask.setColor(Color.WHITE)
        return RippleDrawable(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), null, mask)
    }

    fun surface(ctx: Context, radius: Int = 18, clickable: Boolean = false): Drawable {
        val r = dpf(ctx, radius.toFloat())
        val base = shape(SURFACE, r, OUTLINE, dp(ctx, 1))
        return if (clickable) withRipple(base, r) else base
    }

    fun text(ctx: Context, s: String, sp: Float, color: Int = TEXT, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        if (bold) t.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        return t
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
        t.textSize = 14f
        t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(ctx, 44)
        t.setPadding(dp(ctx, 18), 0, dp(ctx, 18), 0)
        t.background = withRipple(shape(bg, dpf(ctx, 22f)), dpf(ctx, 22f))
        t.isClickable = true
        t.isFocusable = true
        t.setOnClickListener { onClick() }
        return t
    }

    fun card(ctx: Context): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14))
        c.background = surface(ctx)
        return c
    }

    fun chip(ctx: Context, dot: Int, label: String): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.HORIZONTAL
        c.gravity = Gravity.CENTER_VERTICAL
        c.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 12), dp(ctx, 6))
        c.background = shape(SURFACE2, dpf(ctx, 16f))
        val d = View(ctx)
        val g = GradientDrawable()
        g.setShape(GradientDrawable.OVAL)
        g.setColor(dot)
        d.background = g
        val lp = LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8))
        lp.rightMargin = dp(ctx, 6)
        c.addView(d, lp)
        c.addView(text(ctx, label, 12f, TEXT))
        return c
    }

    fun add(parent: LinearLayout, v: View, top: Int = 0, w: Int = ViewGroup.LayoutParams.MATCH_PARENT,
            h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f): View {
        val lp = LinearLayout.LayoutParams(w, h, weight)
        lp.topMargin = dp(parent.context, top)
        parent.addView(v, lp)
        return v
    }

    /** Pads [v] by the system bars / cutout (and optionally the keyboard) so content is never covered. */
    fun applyInsets(v: View, ime: Boolean = false, onIme: ((visible: Boolean, height: Int) -> Unit)? = null) {
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
            ins
        }
    }

    class MenuItem(val label: String, val danger: Boolean = false, val action: (() -> Unit)? = null)

    /** A small dark dropdown menu under [anchor]. An item with no action is shown as a muted label. */
    fun menu(a: Activity, anchor: View, items: List<MenuItem>) {
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(a, 6), 0, dp(a, 6))
        box.background = shape(SURFACE2, dpf(a, 16f), OUTLINE, dp(a, 1))
        box.clipToOutline = true
        box.elevation = dpf(a, 8f)
        val pw = PopupWindow(box, dp(a, 244), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        var first = true
        for (item in items) {
            val act = item.action
            if (!first && act != null) {
                val line = View(a)
                line.setBackgroundColor(OUTLINE)
                box.addView(line, LinearLayout.LayoutParams(-1, dp(a, 1)))
            }
            first = false
            val t = TextView(a)
            t.text = item.label
            t.gravity = Gravity.CENTER_VERTICAL
            t.minHeight = dp(a, 52)
            t.setPadding(dp(a, 18), dp(a, 8), dp(a, 18), dp(a, 8))
            if (act == null) {
                t.textSize = 12f
                t.setTextColor(MUTED)
            } else {
                t.textSize = 15f
                t.setTextColor(if (item.danger) DANGER_FG else TEXT)
                t.background = withRipple(null, 0f)
                t.isClickable = true
                t.isFocusable = true
                t.setOnClickListener { pw.dismiss(); act() }
            }
            box.addView(t, LinearLayout.LayoutParams(-1, -2))
        }
        pw.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    /** A bottom sheet. [heightFrac] of 0 wraps the content; otherwise it is a fraction of the screen height. */
    fun sheet(a: Activity, heightFrac: Float = 0f, build: (LinearLayout, Dialog) -> Unit): Dialog {
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val r = dpf(a, 24f)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        val g = GradientDrawable()
        g.setColor(SURFACE)
        g.setCornerRadii(floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f))
        body.background = g
        body.setPadding(dp(a, 20), dp(a, 10), dp(a, 20), dp(a, 28))

        val handle = View(a)
        handle.background = shape(OUTLINE, dpf(a, 3f))
        val hp = LinearLayout.LayoutParams(dp(a, 36), dp(a, 4))
        hp.gravity = Gravity.CENTER_HORIZONTAL
        hp.bottomMargin = dp(a, 14)
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
            w.setDimAmount(0.6f)
        }
        d.show()
        return d
    }
}

/** Line icons drawn with a Canvas, so there are no drawable resources to ship. */
class IconView(ctx: Context, private val kind: Int) : View(ctx) {
    companion object {
        const val BACK = 0
        const val KEYBOARD = 1
        const val MORE = 2
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
        background = Ui.rippleCircle()
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = Ui.dp(context, 48)
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
        } else if (kind == MORE) {
            val gap = minOf(width, height) * 0.16f
            val r = Ui.dpf(context, 2.4f)
            p.style = Paint.Style.FILL
            c.drawCircle(cx, cy - gap, r, p)
            c.drawCircle(cx, cy, r, p)
            c.drawCircle(cx, cy + gap, r, p)
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
