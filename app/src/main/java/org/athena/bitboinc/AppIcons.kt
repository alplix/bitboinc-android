package org.athena.bitboinc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/** Which science an app name belongs to (task app names look like "Kampylos (gravitational microlensing)"). */
fun scienceId(name: String): String {
    val n = name.lowercase()
    return appOrder.firstOrNull { n.contains(it) } ?: ""
}

/** A small hand-drawn emblem per science, in the palette's colours: a runaway star with its tail (Phygas), a comet (Nostos),
 *  a gravitational lens ring with a planet (Kampylos), a key (Keraunos), river waves (Potamos), a pyramid (Sphinx). */
class AppIconView(ctx: Context, private val p: Palette, private val id: String) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onMeasure(w: Int, h: Int) { val s = getDefaultSize(context.dp(40), w); setMeasuredDimension(s, s) }

    private fun star(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val path = Path()
        for (i in 0 until 10) {
            val a = Math.PI / 5 * i - Math.PI / 2
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = cx + (rr * cos(a)).toFloat(); val y = cy + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); fill.color = color; c.drawPath(path, fill)
    }

    override fun onDraw(c: Canvas) {
        val s = width.toFloat()
        fill.color = (p.accent and 0xFFFFFF) or 0x2E000000
        c.drawCircle(s / 2, s / 2, s / 2, fill)
        line.strokeWidth = s * 0.06f
        val a = p.accent; val b = p.accent2
        when (id) {
            "phygas" -> {
                for (i in 0 until 3) {                      // fading tail
                    line.color = (b and 0xFFFFFF) or ((200 - i * 60) shl 24)
                    c.drawLine(s * 0.3f - i * s * 0.01f, s * (0.7f - i * 0.0f) - s * 0.14f * i, s * 0.62f, s * 0.38f + s * 0.0f, line)
                }
                star(c, s * 0.66f, s * 0.34f, s * 0.2f, a)
                fill.color = Color.WHITE; fill.alpha = 200
                c.drawCircle(s * 0.24f, s * 0.26f, s * 0.025f, fill); c.drawCircle(s * 0.78f, s * 0.74f, s * 0.025f, fill)
            }
            "nostos" -> {
                line.color = (b and 0xFFFFFF) or 0xAA000000.toInt()
                for (k in -1..1) c.drawLine(s * 0.62f, s * 0.38f + k * s * 0.07f, s * 0.14f, s * 0.78f + k * s * 0.07f, line)
                fill.color = a; c.drawCircle(s * 0.66f, s * 0.34f, s * 0.14f, fill)
                fill.color = Color.WHITE; fill.alpha = 230; c.drawCircle(s * 0.62f, s * 0.3f, s * 0.045f, fill)
            }
            "kampylos" -> {
                line.color = a; line.strokeWidth = s * 0.07f
                c.drawArc(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.8f), 0f, 360f, false, line)
                fill.color = b; c.drawCircle(s * 0.5f, s * 0.5f, s * 0.09f, fill)            // lens star
                fill.color = Color.WHITE; fill.alpha = 240; c.drawCircle(s * 0.8f, s * 0.5f, s * 0.05f, fill)  // the planet's flash
            }
            "keraunos" -> {
                line.color = a; line.strokeWidth = s * 0.07f
                c.drawCircle(s * 0.34f, s * 0.5f, s * 0.13f, line)
                c.drawLine(s * 0.47f, s * 0.5f, s * 0.8f, s * 0.5f, line)
                c.drawLine(s * 0.7f, s * 0.5f, s * 0.7f, s * 0.62f, line); c.drawLine(s * 0.8f, s * 0.5f, s * 0.8f, s * 0.62f, line)
                fill.color = b; c.drawCircle(s * 0.34f, s * 0.5f, s * 0.04f, fill)
            }
            "potamos" -> {
                for (k in 0 until 3) {
                    val y = s * (0.34f + k * 0.16f)
                    val path = Path(); path.moveTo(s * 0.18f, y)
                    path.cubicTo(s * 0.32f, y - s * 0.12f, s * 0.42f, y + s * 0.12f, s * 0.5f, y)
                    path.cubicTo(s * 0.58f, y - s * 0.12f, s * 0.68f, y + s * 0.12f, s * 0.82f, y)
                    line.color = if (k == 1) b else a; c.drawPath(path, line)
                }
            }
            "sphinx" -> {
                val path = Path()
                path.moveTo(s * 0.5f, s * 0.2f); path.lineTo(s * 0.82f, s * 0.74f); path.lineTo(s * 0.18f, s * 0.74f); path.close()
                fill.color = a; fill.alpha = 230; c.drawPath(path, fill)
                fill.color = b; val half = Path()
                half.moveTo(s * 0.5f, s * 0.2f); half.lineTo(s * 0.82f, s * 0.74f); half.lineTo(s * 0.5f, s * 0.74f); half.close()
                fill.alpha = 255; c.drawPath(half, fill)
                fill.color = Color.WHITE; fill.alpha = 230; c.drawCircle(s * 0.5f, s * 0.52f, s * 0.04f, fill)
            }
            else -> { fill.color = a; c.drawCircle(s / 2, s / 2, s * 0.12f, fill) }
        }
    }
}
