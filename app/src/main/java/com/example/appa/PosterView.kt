package com.example.appa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View

/**
 * Placeholder poster: gradient background (per-title colors) with the title,
 * type label and year drawn on canvas. Stands in for real poster images until
 * TMDB is connected.
 */
class PosterView(context: Context, val media: Media) : View(context) {

    private val c1 = Color.parseColor(media.color1)
    private val c2 = Color.parseColor(media.color2)
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 217; textAlign = Paint.Align.CENTER
    }
    private val yearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; alpha = 230; textAlign = Paint.Align.CENTER
    }
    private val bgPaint = Paint()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w * 3 / 2) // 2:3 poster aspect
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        bgPaint.shader = LinearGradient(0f, 0f, w, h, c1, c2, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val deco = Paint().apply { color = Color.WHITE; alpha = 20 }
        canvas.drawCircle(w * 0.84f, h * 0.14f, w * 0.33f, deco)
        val deco2 = Paint().apply { color = Color.BLACK; alpha = 36 }
        canvas.drawCircle(w * 0.14f, h * 0.88f, w * 0.42f, deco2)

        labelPaint.textSize = w * 0.05f
        canvas.drawText(media.type.label.uppercase(), w / 2f, h * 0.12f, labelPaint)

        val lines = wrapTitle(media.title, 14)
        val fs = when (lines.size) {
            1 -> w * 0.115f
            2 -> w * 0.10f
            else -> w * 0.08f
        }
        titlePaint.textSize = fs
        val lh = fs * 1.15f
        val startY = h * 0.5f - (lines.size - 1) * lh / 2f + fs * 0.35f
        lines.forEachIndexed { i, line -> canvas.drawText(line, w / 2f, startY + i * lh, titlePaint) }

        yearPaint.textSize = w * 0.06f
        canvas.drawText(media.year.toString(), w / 2f, h * 0.94f, yearPaint)
    }

    private fun wrapTitle(title: String, maxLen: Int): List<String> {
        val words = title.split(" ").filter { it.isNotBlank() }
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in words) {
            val candidate = if (cur.isEmpty()) word else "$cur $word"
            if (candidate.length <= maxLen) cur = candidate
            else { if (cur.isNotEmpty()) lines.add(cur); cur = word }
        }
        if (cur.isNotEmpty()) lines.add(cur)
        return if (lines.size > 3) lines.take(2) + (lines[2].removeSuffix("…") + "…") else lines
    }
}
