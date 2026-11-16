package com.example.powerlocks

import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
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
    var bg = Color.parseColor("#0A120E")
    var card = Color.parseColor("#10231A")
    var card2 = Color.parseColor("#17301F")
    var chipFill = Color.parseColor("#12231B")
    val border = Color.parseColor("#1F4A38")
    val selFill = Color.parseColor("#17694B")
    val primary = Color.parseColor("#1FCB85")
    val mint = Color.parseColor("#A8F0D1")
    val text = Color.parseColor("#F2FBF6")
    val textSoft = Color.parseColor("#D5E8DE")
    val muted = Color.parseColor("#8FA89B")
    val danger = Color.parseColor("#FF6B6B")
    val warn = Color.parseColor("#FFB84D")
    val onPrimary = Color.parseColor("#062B1D")

    fun load(ctx: Context) {
        val amoled = Store.settings(ctx).getInt("theme", 0) == 1
        if (amoled) {
            bg = Color.BLACK
            card = Color.parseColor("#0C1812")
            card2 = Color.parseColor("#132A1D")
            chipFill = Color.parseColor("#0E1B14")
        } else {
            bg = Color.parseColor("#0A120E")
            card = Color.parseColor("#10231A")
            card2 = Color.parseColor("#17301F")
            chipFill = Color.parseColor("#12231B")
        }
    }
}

fun tint(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0x00FFFFFF)

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
    v.setTextColor(if (selected) C.text else C.textSoft)
    v.background = withRipple(
        rounded(
            if (selected) C.selFill else C.chipFill,
            dp(18).toFloat(),
            if (selected) C.primary else C.border,
            if (selected) dp(2) else dp(1)
        )
    )
}

fun Context.chip(label: String, selected: Boolean): TextView {
    val v = tv(label, 15f, C.textSoft, 1)
    v.gravity = Gravity.CENTER
    v.setPadding(dp(6), dp(14), dp(6), dp(14))
    styleChip(v, selected)
    return v
}

fun Context.heading(t: String): TextView = tv(t, 22f, C.text, 1)

fun Context.bigButton(kind: Ic, label: String, fill: Int, fg: Int, stroke: Int = 0): LinearLayout {
    val b = LinearLayout(this)
    b.orientation = LinearLayout.HORIZONTAL
    b.gravity = Gravity.CENTER
    b.background = withRipple(rounded(fill, dp(32).toFloat(), stroke, if (stroke != 0) dp(1) else 0))
    b.addView(IconView(this, kind, fg), lp(dp(24), dp(24), r = dp(10)))
    val t = tv(label.uppercase(), 16f, fg, 1)
    t.letterSpacing = 0.04f
    b.addView(t)
    b.isClickable = true
    return b
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

enum class Ic { HOME, CAL, BARS, GEAR, SHIELD, LOCK, CLOCK, CHECK }

/** Line icons drawn with Canvas (no image files needed). */
object Icons {
    fun draw(cv: Canvas, kind: Ic, cx: Float, cy: Float, s: Float, p: Paint) {
        val l = cx - s / 2f
        val t = cy - s / 2f
        p.style = Paint.Style.STROKE
        p.strokeWidth = s * 0.085f
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val path = Path()
        fun x(u: Float): Float = l + s * u
        fun y(u: Float): Float = t + s * u
        when (kind) {
            Ic.HOME -> {
                path.moveTo(x(0.14f), y(0.50f))
                path.lineTo(x(0.50f), y(0.18f))
                path.lineTo(x(0.86f), y(0.50f))
                path.moveTo(x(0.24f), y(0.44f))
                path.lineTo(x(0.24f), y(0.82f))
                path.lineTo(x(0.76f), y(0.82f))
                path.lineTo(x(0.76f), y(0.44f))
                path.moveTo(x(0.42f), y(0.82f))
                path.lineTo(x(0.42f), y(0.60f))
                path.lineTo(x(0.58f), y(0.60f))
                path.lineTo(x(0.58f), y(0.82f))
                cv.drawPath(path, p)
            }
            Ic.CAL -> {
                cv.drawRoundRect(RectF(x(0.16f), y(0.22f), x(0.84f), y(0.84f)), s * 0.1f, s * 0.1f, p)
                cv.drawLine(x(0.16f), y(0.42f), x(0.84f), y(0.42f), p)
                cv.drawLine(x(0.34f), y(0.12f), x(0.34f), y(0.30f), p)
                cv.drawLine(x(0.66f), y(0.12f), x(0.66f), y(0.30f), p)
                cv.drawLine(x(0.30f), y(0.60f), x(0.42f), y(0.60f), p)
                cv.drawLine(x(0.58f), y(0.60f), x(0.70f), y(0.60f), p)
                cv.drawLine(x(0.30f), y(0.74f), x(0.42f), y(0.74f), p)
            }
            Ic.BARS -> {
                p.strokeWidth = s * 0.15f
                cv.drawLine(x(0.25f), y(0.80f), x(0.25f), y(0.55f), p)
                cv.drawLine(x(0.50f), y(0.80f), x(0.50f), y(0.25f), p)
                cv.drawLine(x(0.75f), y(0.80f), x(0.75f), y(0.42f), p)
            }
            Ic.GEAR -> {
                cv.drawCircle(cx, cy, s * 0.14f, p)
                cv.drawCircle(cx, cy, s * 0.30f, p)
                p.strokeWidth = s * 0.12f
                for (i in 0 until 8) {
                    val a = Math.toRadians(45.0 * i)
                    val ca = Math.cos(a).toFloat()
                    val sa = Math.sin(a).toFloat()
                    cv.drawLine(cx + ca * s * 0.32f, cy + sa * s * 0.32f, cx + ca * s * 0.42f, cy + sa * s * 0.42f, p)
                }
            }
            Ic.SHIELD -> {
                path.moveTo(x(0.50f), y(0.10f))
                path.lineTo(x(0.82f), y(0.22f))
                path.lineTo(x(0.82f), y(0.48f))
                path.cubicTo(x(0.82f), y(0.70f), x(0.66f), y(0.82f), x(0.50f), y(0.90f))
                path.cubicTo(x(0.34f), y(0.82f), x(0.18f), y(0.70f), x(0.18f), y(0.48f))
                path.lineTo(x(0.18f), y(0.22f))
                path.close()
                path.moveTo(x(0.36f), y(0.50f))
                path.lineTo(x(0.46f), y(0.60f))
                path.lineTo(x(0.66f), y(0.38f))
                cv.drawPath(path, p)
            }
            Ic.LOCK -> {
                cv.drawRoundRect(RectF(x(0.22f), y(0.46f), x(0.78f), y(0.86f)), s * 0.08f, s * 0.08f, p)
                path.moveTo(x(0.34f), y(0.46f))
                path.lineTo(x(0.34f), y(0.34f))
                path.arcTo(RectF(x(0.34f), y(0.14f), x(0.66f), y(0.54f)), 180f, 180f)
                path.lineTo(x(0.66f), y(0.46f))
                cv.drawPath(path, p)
                p.style = Paint.Style.FILL
                cv.drawCircle(x(0.50f), y(0.64f), s * 0.05f, p)
                p.style = Paint.Style.STROKE
            }
            Ic.CLOCK -> {
                cv.drawCircle(cx, cy, s * 0.36f, p)
                cv.drawLine(cx, cy, cx, y(0.28f), p)
                cv.drawLine(cx, cy, x(0.66f), y(0.58f), p)
            }
            Ic.CHECK -> {
                cv.drawCircle(cx, cy, s * 0.36f, p)
                path.moveTo(x(0.34f), y(0.52f))
                path.lineTo(x(0.46f), y(0.64f))
                path.lineTo(x(0.68f), y(0.38f))
                cv.drawPath(path, p)
            }
        }
    }
}

class IconView(ctx: Context, private val kind: Ic, color: Int) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var color: Int = color
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = color
        val s = Math.min(width, height).toFloat() * 0.86f
        Icons.draw(canvas, kind, width / 2f, height / 2f, s, paint)
    }
}

/** Large circular status ring: outline + icon + "OFF" when idle, countdown arc when active. */
class RingView(ctx: Context) : View(ctx) {
    var main: String = "OFF"
    var sub: String = ""
    var active = false
    var mainColor = C.primary
    var icon: Ic = Ic.SHIELD
    private var shown = 0f
    private var target = -1f
    private var anim: ValueAnimator? = null
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val inner = Paint(Paint.ANTI_ALIAS_FLAG)
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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
        val cx = w / 2f
        val cy = h / 2f
        val pad = w * 0.07f
        rect.set(pad, pad, w - pad, h - pad)

        if (active) {
            val stroke = w * 0.055f
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
        } else {
            inner.color = tint(C.bg, 150)
            canvas.drawCircle(cx, cy, (w - 2f * pad) / 2f, inner)
            track.strokeWidth = w * 0.016f
            track.color = C.primary
            canvas.drawOval(rect, track)
        }

        iconPaint.color = C.primary
        Icons.draw(canvas, icon, cx, cy - w * 0.20f, w * 0.21f, iconPaint)

        tMain.color = mainColor
        if (active) {
            tMain.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            tMain.textSize = w * 0.135f
            canvas.drawText(main, cx, cy + w * 0.10f, tMain)
        } else {
            tMain.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            tMain.textSize = w * 0.20f
            canvas.drawText(main, cx, cy + w * 0.15f, tMain)
        }
        if (sub.isNotEmpty()) {
            tSub.color = C.muted
            tSub.textSize = w * 0.058f
            canvas.drawText(sub, cx, cy + w * 0.22f, tSub)
        }
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

