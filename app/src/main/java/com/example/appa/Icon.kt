package com.example.appa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Hand-drawn vector icons (24dp grid, stroked paths) so the app needs no
 * bitmap assets and icons stay crisp at any density. Tint via [setColor] —
 * components pass theme tokens (textSecondary / accentText / textPrimary).
 */
enum class IconKind { SERIES, MOVIES, AUDIOBOOKS, MENU, INBOX, GEAR }

class IconView(ctx: Context, val kind: IconKind, sizeDp: Int = 24, color: Int = AppTheme.current.colors.textPrimary) : View(ctx) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val p = Path()

    init {
        setColor(color)
        minimumWidth = ctx.dp(sizeDp)
        minimumHeight = ctx.dp(sizeDp)
    }

    fun setColor(c: Int) {
        stroke.color = c
        fill.color = c
        invalidate()
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = resolveSize(minimumWidth, wSpec)
        val h = resolveSize(minimumHeight, hSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(c: Canvas) {
        val s = width / 24f // scale 24dp design grid to actual size
        c.save()
        c.scale(s, s)
        when (kind) {
            IconKind.SERIES -> { // monitor + play
                p.reset(); p.addRoundRect(3f, 5f, 21f, 16f, 2f, 2f, Path.Direction.CW); c.drawPath(p, stroke)
                p.reset(); p.moveTo(9f, 17f); p.lineTo(15f, 17f); p.lineTo(12f, 20f); p.close(); c.drawPath(p, stroke)
                p.reset(); p.moveTo(10.5f, 8.5f); p.lineTo(15f, 10.5f); p.lineTo(10.5f, 12.5f); p.close(); c.drawPath(p, fill)
            }
            IconKind.MOVIES -> { // clapperboard
                p.reset(); p.addRoundRect(3f, 9f, 21f, 20f, 2f, 2f, Path.Direction.CW); c.drawPath(p, stroke)
                p.reset(); p.moveTo(3f, 9f); p.lineTo(5f, 4f); p.lineTo(21f, 4f); p.lineTo(19f, 9f); p.close(); c.drawPath(p, stroke)
                p.reset(); p.moveTo(8.5f, 4.6f); p.lineTo(6.8f, 8.6f); c.drawPath(p, stroke)
                p.reset(); p.moveTo(13.5f, 4.6f); p.lineTo(11.8f, 8.6f); c.drawPath(p, stroke)
                p.reset(); p.moveTo(18.5f, 4.6f); p.lineTo(16.8f, 8.6f); c.drawPath(p, stroke)
            }
            IconKind.AUDIOBOOKS -> { // headphones
                p.reset(); p.moveTo(5f, 16f); p.arcTo(android.graphics.RectF(5f, 7f, 19f, 17f), 180f, 180f, false); c.drawPath(p, stroke)
                p.reset(); p.addRoundRect(3.5f, 14f, 7.5f, 20f, 2f, 2f, Path.Direction.CW); c.drawPath(p, stroke)
                p.reset(); p.addRoundRect(16.5f, 14f, 20.5f, 20f, 2f, 2f, Path.Direction.CW); c.drawPath(p, stroke)
            }
            IconKind.MENU -> { // hamburger
                for (y in floatArrayOf(6f, 12f, 18f)) {
                    p.reset(); p.moveTo(4f, y); p.lineTo(20f, y); c.drawPath(p, stroke)
                }
            }
            IconKind.INBOX -> { // tray
                p.reset()
                p.moveTo(3f, 13f); p.lineTo(6.5f, 5f); p.lineTo(17.5f, 5f); p.lineTo(21f, 13f)
                p.lineTo(21f, 18f); p.arcTo(21f, 20f, 19f, 20f, 0f); p.lineTo(5f, 20f); p.arcTo(3f, 20f, 3f, 18f, 0f); p.close()
                c.drawPath(p, stroke)
                p.reset(); p.moveTo(3f, 13f); p.lineTo(10f, 13f); p.lineTo(12f, 16f); p.lineTo(14f, 16f); p.lineTo(16f, 13f); p.lineTo(21f, 13f); c.drawPath(p, stroke)
            }
            IconKind.GEAR -> { // cog: ring + hub + 8 teeth
                p.reset(); c.drawCircle(12f, 12f, 6.5f, stroke)
                p.reset(); c.drawCircle(12f, 12f, 2.4f, fill)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0).toFloat()
                    val cos = kotlin.math.cos(a); val sin = kotlin.math.sin(a)
                    p.reset(); p.moveTo(12f + 8.2f * cos, 12f + 8.2f * sin); p.lineTo(12f + 10.4f * cos, 12f + 10.4f * sin)
                    c.drawPath(p, stroke)
                }
            }
        }
        c.restore()
    }
}
