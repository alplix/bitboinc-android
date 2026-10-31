package org.athena.bitboinc

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

fun Context.dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun rounded(fill: Int, radiusPx: Float, stroke: Int = 0, strokePx: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill); cornerRadius = radiusPx
        if (strokePx > 0) setStroke(strokePx, stroke)
    }

fun Context.label(p: Palette, text: CharSequence, sp: Float = 14f, color: Int = p.text, bold: Boolean = false, muted: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(if (muted) p.muted else color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
        setLineSpacing(0f, 1.15f)
    }

fun Context.card(p: Palette, pad: Int = 14): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    background = rounded(p.card, dp(16).toFloat(), p.line, dp(1))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) }
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun weight(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
fun weightFill(w: Float = 1f) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, w)
fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

/** Thin horizontal progress bar. */
class BarView(ctx: Context, private val p: Palette, private val color: Int = p.accent) : View(ctx) {
    var value = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(6))
    override fun onDraw(c: Canvas) {
        val r = height / 2f
        paint.color = p.line; c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        paint.color = color; c.drawRoundRect(0f, 0f, width * value, height.toFloat(), r, r, paint)
    }
}

/** Circular progress ring with two centred text lines. */
class RingView(ctx: Context, private val p: Palette) : View(ctx) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var big = ""
        set(v) { field = v; invalidate() }
    var small = ""
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    override fun onMeasure(w: Int, h: Int) { val s = context.dp(150); setMeasuredDimension(s, s) }
    override fun onDraw(c: Canvas) {
        val sw = context.dp(10).toFloat()
        val r = RectF(sw, sw, width - sw, height - sw)
        paint.strokeWidth = sw; paint.color = p.line; c.drawArc(r, 0f, 360f, false, paint)
        paint.color = p.accent; c.drawArc(r, -90f, 360f * fraction, false, paint)
        tp.color = p.text; tp.textSize = context.dp(24).toFloat(); tp.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        c.drawText(big, width / 2f, height / 2f + context.dp(2), tp)
        tp.color = p.muted; tp.textSize = context.dp(12).toFloat(); tp.typeface = Typeface.DEFAULT
        c.drawText(small, width / 2f, height / 2f + context.dp(20), tp)
    }
}

/** Line chart of the last samples. */
class SparkView(ctx: Context, private val color: Int) : View(ctx) {
    var data: List<Float> = emptyList()
        set(v) { field = v; invalidate() }
    var max = 100f
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = ctx.dp(2).toFloat(); strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(56))
    override fun onDraw(c: Canvas) {
        val n = data.size
        if (n < 2) return
        val path = Path(); val area = Path()
        val dx = width.toFloat() / (n - 1)
        for (i in 0 until n) {
            val y = height - (data[i] / max).coerceIn(0f, 1f) * (height - 4) - 2
            if (i == 0) { path.moveTo(0f, y); area.moveTo(0f, height.toFloat()); area.lineTo(0f, y) } else { path.lineTo(i * dx, y); area.lineTo(i * dx, y) }
        }
        area.lineTo(width.toFloat(), height.toFloat()); area.close()
        fill.color = (color and 0x00FFFFFF) or 0x30000000; c.drawPath(area, fill)
        line.color = color; c.drawPath(path, line)
    }
}

/** Vertical bars (e.g. hours per weekday); the last bar is the accent. */
class BarsView(ctx: Context, private val p: Palette) : View(ctx) {
    var data: List<Float> = emptyList()
        set(v) { field = v; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), context.dp(64))
    override fun onDraw(c: Canvas) {
        val n = data.size
        if (n == 0) return
        val mx = (data.maxOrNull() ?: 1f).coerceAtLeast(0.001f)
        val gap = context.dp(6).toFloat(); val bw = (width - gap * (n - 1)) / n
        for (i in 0 until n) {
            val h = (data[i] / mx).coerceIn(0.04f, 1f) * (height - 2)
            paint.color = if (i == n - 1) p.accent else p.accent2
            paint.alpha = if (i == n - 1) 255 else 150
            c.drawRoundRect(i * (bw + gap), height - h, i * (bw + gap) + bw, height.toFloat(), 6f, 6f, paint)
        }
    }
}

/** Animated theme background: twinkling stars, falling sakura petals, drifting nebula, or plain colour. */
class SpaceBackground(ctx: Context, private var palette: Palette) : View(ctx) {
    private class Part(val x: Float, val y: Float, val s: Float, val v: Float, val ph: Float, val rot: Float)
    private val rnd = Random(7)
    private val parts = List(70) { Part(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat(), 0.02f + rnd.nextFloat() * 0.05f, rnd.nextFloat() * 6.28f, rnd.nextFloat() * 360f) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { t += 0.016f; invalidate() }
    }
    private fun sync() { if (isAttachedToWindow && palette.deco != Deco.NONE) { if (!anim.isStarted) anim.start() } else anim.cancel(); invalidate() }
    fun setPalette(p: Palette) { palette = p; sync() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); sync() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }
    override fun onDraw(c: Canvas) {
        c.drawColor(palette.bg)
        val w = width.toFloat(); val h = height.toFloat()
        val px = context.dp(1).toFloat()
        when (palette.deco) {
            Deco.STARS -> {
                paint.color = palette.accent2; paint.alpha = 26
                c.drawCircle(w * 0.85f, h * 0.1f, w * 0.5f, paint)
                for (p in parts) {
                    paint.color = Color.WHITE
                    paint.alpha = (90 + 150 * (0.5f + 0.5f * sin(t * 1.4f + p.ph))).toInt().coerceIn(0, 255)
                    c.drawCircle(p.x * w, p.y * h, p.s * px, paint)
                }
            }
            Deco.NEBULA -> {
                paint.color = palette.accent; paint.alpha = 34
                c.drawCircle(w * (0.2f + 0.05f * sin(t * 0.3f)), h * 0.25f, w * 0.55f, paint)
                paint.color = palette.accent2; paint.alpha = 34
                c.drawCircle(w * (0.85f + 0.04f * cos(t * 0.25f)), h * 0.7f, w * 0.5f, paint)
                for (p in parts.take(40)) {
                    paint.color = Color.WHITE
                    paint.alpha = (70 + 120 * (0.5f + 0.5f * sin(t + p.ph))).toInt().coerceIn(0, 255)
                    c.drawCircle(p.x * w, p.y * h, p.s * px, paint)
                }
            }
            Deco.SAKURA -> {
                for (p in parts.take(26)) {
                    val y = ((p.y + t * p.v) % 1.1f) * h
                    val x = (p.x + 0.04f * sin(t * 0.8f + p.ph)) * w
                    paint.color = Color.parseColor("#F4B4D3"); paint.alpha = 150
                    c.save(); c.translate(x, y); c.rotate(p.rot + t * 400f * p.v)
                    val s = px * 5f * (0.8f + p.s)
                    c.drawOval(-s, -s * 0.6f, s, s * 0.6f, paint)
                    c.restore()
                }
            }
            Deco.NONE -> {}
        }
    }
}
