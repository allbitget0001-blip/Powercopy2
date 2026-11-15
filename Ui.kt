package com.example.powerlocks

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Emerald Dark palette. */
object C {
    var bg = Color.parseColor("#101713")
    var card = Color.parseColor("#1A2620")
    var card2 = Color.parseColor("#24352C")
    val primary = Color.parseColor("#16C784")
    val mint = Color.parseColor("#A8F0D1")
    val text = Color.parseColor("#F2FBF6")
    val muted = Color.parseColor("#8FA89B")
    val danger = Color.parseColor("#FF6B6B")
    val warn = Color.parseColor("#FFB84D")
    val onPrimary = Color.parseColor("#062B1D")

    fun load(ctx: Context) {
        val amoled = Store.settings(ctx).getInt("theme", 0) == 1
        if (amoled) {
            bg = Color.BLACK
            card = Color.parseColor("#0F1A14")
            card2 = Color.parseColor("#1B2B22")
        } else {
            bg = Color.parseColor("#101713")
            card = Color.parseColor("#1A2620")
            card2 = Color.parseColor("#24352C")
        }
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun lp(w: Int, h: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
    val p = LinearLayout.LayoutParams(w, h, weight)
    p.setMargins(l, t, r, b)
    return p
}

fun rounded(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable {
    val d = GradientDrawable()
    d.setColor(color)
    d.cornerRadius = radius
    if (strokeWidth > 0) d.setStroke(strokeWidth, strokeColor)
    return d
}

fun withRipple(bg: Drawable): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)

/** style: 0 regular, 1 medium, 2 light */
fun Context.tv(t: String, size: Float = 14f, color: Int = C.text, style: Int = 0): TextView {
    val v = TextView(this)
    v.text = t
    v.textSize = size
    v.setTextColor(color)
    v.typeface = when (style) {
        1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        2 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }
    return v
}

fun Context.card(pad: Int = 16): LinearLayout {
    val v = LinearLayout(this)
    v.orientation = LinearLayout.VERTICAL
    v.background = rounded(C.card, dp(20).toFloat())
    v.setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    return v
}

fun Context.section(t: String): TextView {
    val v = tv(t.uppercase(), 12f, C.primary, 1)
    v.letterSpacing = 0.08f
    return v
}

fun Context.primaryBtn(label: String): TextView {
    val v = tv(label, 16f, C.onPrimary, 1)
    v.gravity = Gravity.CENTER
    val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(C.primary, C.mint))
    g.cornerRadius = dp(16).toFloat()
    v.background = withRipple(g)
    return v
}

fun Context.outlineBtn(label: String, color: Int): TextView {
    val v = tv(label, 15f, color, 1)
    v.gravity = Gravity.CENTER
    v.background = withRipple(rounded(Color.TRANSPARENT, dp(16).toFloat(), color, dp(1)))
    return v
}

fun Context.styleChip(v: TextView, selected: Boolean) {
    v.setTextColor(if (selected) C.onPrimary else C.muted)
    v.background = withRipple(rounded(if (selected) C.primary else C.card2, dp(14).toFloat()))
}

fun Context.chip(label: String, selected: Boolean): TextView {
    val v = tv(label, 14f, C.muted, 1)
    v.gravity = Gravity.CENTER
    v.setPadding(dp(8), dp(12), dp(8), dp(12))
    styleChip(v, selected)
    return v
}

fun Context.toggle(checked: Boolean, onChange: (Boolean) -> Unit): Switch {
    val s = Switch(this)
    s.isChecked = checked
    val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
    s.thumbTintList = ColorStateList(states, intArrayOf(C.primary, Color.parseColor("#6B7F74")))
    s.trackTintList = ColorStateList(states, intArrayOf(Color.parseColor("#2E6B50"), Color.parseColor("#2A3A32")))
    s.setOnCheckedChangeListener { _, v -> onChange(v) }
    return s
}

fun Context.row(title: String, sub: String? = null, trailing: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = Gravity.CENTER_VERTICAL
    r.setPadding(0, dp(10), 0, dp(10))
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.addView(tv(title, 15f, C.text, 1))
    if (sub != null) col.addView(tv(sub, 12f, C.muted))
    r.addView(col, lp(0, WRAP, weight = 1f))
    if (trailing != null) r.addView(trailing)
    if (onClick != null) {
        r.setOnClickListener { onClick() }
        r.background = withRipple(ColorDrawable(Color.TRANSPARENT))
    }
    return r
}

fun Context.edit(hint: String, number: Boolean): EditText {
    val e = EditText(this)
    e.hint = hint
    e.setHintTextColor(C.muted)
    e.setTextColor(C.text)
    e.textSize = 16f
    e.gravity = Gravity.CENTER
    if (number) e.inputType = InputType.TYPE_CLASS_NUMBER
    e.background = rounded(C.card2, dp(12).toFloat())
    e.setPadding(dp(14), dp(12), dp(14), dp(12))
    return e
}

fun Context.dlg(): AlertDialog.Builder =
    AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

fun Context.page(): Pair<ScrollView, LinearLayout> {
    val sv = ScrollView(this)
    sv.isVerticalScrollBarEnabled = false
    val col = LinearLayout(this)
    col.orientation = LinearLayout.VERTICAL
    col.setPadding(dp(20), dp(16), dp(20), dp(24))
    sv.addView(col, ViewGroup.LayoutParams(MATCH, WRAP))
    return Pair(sv, col)
}

/** Large circular countdown. */
class RingView(ctx: Context) : View(ctx) {
    var main: String = "00:00:00"
    var sub: String = ""
    private var shown = 0f
    private var target = -1f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tMain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tSub = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        track.style = Paint.Style.STROKE
        arc.style = Paint.Style.STROKE
        arc.strokeCap = Paint.Cap.ROUND
        glow.style = Paint.Style.STROKE
        glow.strokeCap = Paint.Cap.ROUND
        tMain.textAlign = Paint.Align.CENTER
        tMain.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        tSub.textAlign = Paint.Align.CENTER
        tSub.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val shader = SweepGradient(w / 2f, h / 2f, intArrayOf(C.primary, C.mint, C.primary), null)
        val m = Matrix()
        m.setRotate(-90f, w / 2f, h / 2f)
        shader.setLocalMatrix(m)
        arc.shader = shader
        glow.shader = shader
    }

    fun animateTo(p: Float) {
        if (Math.abs(p - target) < 0.0005f) return
        target = p
        anim?.cancel()
        val a = ValueAnimator.ofFloat(shown, p)
        a.duration = 600
        a.addUpdateListener {
            shown = it.animatedValue as Float
            invalidate()
        }
        a.start()
        anim = a
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = w * 0.05f
        val pad = stroke * 1.6f
        rect.set(pad, pad, w - pad, h - pad)
        track.strokeWidth = stroke
        track.color = C.card2
        canvas.drawOval(rect, track)
        if (shown > 0.002f) {
            glow.strokeWidth = stroke * 1.9f
            glow.alpha = 50
            canvas.drawArc(rect, -90f, 360f * shown, false, glow)
            arc.strokeWidth = stroke
            canvas.drawArc(rect, -90f, 360f * shown, false, arc)
        }
        tMain.color = C.text
        tMain.textSize = w * 0.15f
        canvas.drawText(main, w / 2f, h / 2f + w * 0.03f, tMain)
        tSub.color = C.muted
        tSub.textSize = w * 0.058f
        canvas.drawText(sub, w / 2f, h / 2f + w * 0.14f, tSub)
    }
}

/** Seven-day bar chart for the statistics screen. */
class BarChartView(ctx: Context) : View(ctx) {
    var values: List<Long> = emptyList()
    var labels: List<String> = emptyList()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        txt.textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val labelH = h * 0.18f
        val top = h * 0.05f
        val chartH = h - labelH - top
        var max = 1L
        for (v in values) if (v > max) max = v
        val slot = w / n
        val bw = slot * 0.5f
        txt.textSize = h * 0.11f
        for (i in 0 until n) {
            val cx = slot * i + slot / 2f
            bar.color = C.card2
            rect.set(cx - bw / 2f, top, cx + bw / 2f, top + chartH)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, bar)
            val frac = values[i].toFloat() / max.toFloat()
            if (frac > 0f) {
                var bh = chartH * frac
                if (bh < bw) bh = bw
                bar.color = if (i == n - 1) C.mint else C.primary
                rect.set(cx - bw / 2f, top + chartH - bh, cx + bw / 2f, top + chartH)
                canvas.drawRoundRect(rect, bw / 2f, bw / 2f, bar)
            }
            txt.color = if (i == n - 1) C.mint else C.muted
            val label = if (i < labels.size) labels[i] else ""
            canvas.drawText(label, cx, h - labelH * 0.25f, txt)
        }
    }
}
