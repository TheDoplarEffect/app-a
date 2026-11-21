package com.example.appa

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Animated SPACE backdrop: the screen base color, three slowly drifting nebula
 * blobs (low-res offscreen bitmap, upscaled bilinearly) plus a seeded starfield
 * with twinkling stars drawn at full resolution — the stars shining through the
 * translucent glass surfaces make the liquid-glass effect visible.
 * Colors come from the active theme's tokens.
 */
class AuroraBackground(context: Context) : View(context) {

    private data class Star(val fx: Float, val fy: Float, val rDp: Float, val phase: Float, val speed: Float, val bright: Float)

    /** Seeded once — the sky is identical on every launch and rotation. */
    private val stars: List<Star> = run {
        val rnd = java.util.Random(1337)
        List(170) {
            Star(rnd.nextFloat(), rnd.nextFloat(), 0.5f + rnd.nextFloat() * 1.3f,
                rnd.nextFloat() * 6.283f, 0.25f + rnd.nextFloat() * 0.9f, 0.30f + rnd.nextFloat() * 0.70f)
        }
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isAntiAlias = true
    }
    private var bmp: Bitmap? = null
    private var blobCanvas: Canvas? = null
    private var anim: ValueAnimator? = null
    private var t = 0f // 0..1 animation phase

    private fun tokens() = AppTheme.current.colors

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            // 1/8 scale + bilinear upscale + (API 31+) real blur = soft frosted backdrop
            val bw = (w / 8).coerceAtLeast(48)
            val bh = (h / 8).coerceAtLeast(48)
            val old = bmp
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            if (old != null && !old.isRecycled) old.recycle()
            blobCanvas = Canvas(bmp!!)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (context.reduceMotion()) {
            t = 0.37f
            invalidate() // one static frame, no animation loop
            return
        }
        if (anim == null) {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 24_000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                addUpdateListener {
                    t = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        anim = null
        bmp?.let { if (!it.isRecycled) it.recycle() } // no bitmap leak across activity re-creation
        bmp = null
        blobCanvas = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val c = tokens()
        canvas.drawColor(c.background)
        val b = bmp ?: return
        val bc = blobCanvas ?: return
        bc.drawColor(c.background)

        val w = b.width.toFloat()
        val h = b.height.toFloat()
        // three blobs orbiting slowly; phase t drives position
        drawBlob(bc, w, h, c.aurora1, 0.22f + 0.16f * Math.sin(2 * Math.PI * t).toFloat(), 0.20f + 0.10f * Math.cos(2 * Math.PI * t).toFloat(), 0.55f)
        drawBlob(bc, w, h, c.aurora2, 0.80f - 0.14f * Math.cos(2 * Math.PI * t + 1.3).toFloat(), 0.72f + 0.12f * Math.sin(2 * Math.PI * t + 0.7).toFloat(), 0.60f)
        drawBlob(bc, w, h, c.aurora3, 0.55f + 0.18f * Math.sin(2 * Math.PI * t + 2.6).toFloat(), 0.42f - 0.12f * Math.cos(2 * Math.PI * t + 2.0).toFloat(), 0.50f)

        // upscale low-res bitmap smoothly over the whole view
        canvas.drawBitmap(b, null, android.graphics.Rect(0, 0, width, height), blobPaint.apply {
            isFilterBitmap = true
            shader = null
        })

        // starfield on top, twinkling with the animation phase (static under reduced motion)
        val tw = t
        val density = resources.displayMetrics.density
        for (s in stars) {
            val twinkle = 0.55f + 0.45f * kotlin.math.sin(6.283f * (tw * s.speed + s.phase))
            val a = (s.bright * twinkle).coerceIn(0f, 1f)
            starPaint.color = withAlpha(0xFFE9EDFF.toInt(), a)
            canvas.drawCircle(s.fx * width, s.fy * height, s.rDp * density, starPaint)
        }
    }

    private fun drawBlob(canvas: Canvas, w: Float, h: Float, color: Int, fx: Float, fy: Float, rFrac: Float) {
        val r = maxOf(w, h) * rFrac
        val alpha = 52 // subtle — glass surfaces sit on top
        blobPaint.shader = RadialGradient(w * fx, h * fy, r, withAlpha(color, alpha / 255f), withAlpha(color, 0f), Shader.TileMode.CLAMP)
        blobPaint.alpha = 255
        // no per-paint blur in the SDK stub: the 1/8-res bitmap + bilinear upscale
        // already renders the blobs as soft, frosted patches
        canvas.drawRect(0f, 0f, w, h, blobPaint)
        blobPaint.shader = null
    }

    /** Static fallback for tests/previews. */
}

/** Helper: a root container with the aurora backdrop behind content. */
fun android.widget.FrameLayout.withAurora(): android.widget.FrameLayout {
    val bg = AuroraBackground(context)
    addView(bg, android.widget.FrameLayout.LayoutParams(
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
    ))
    return this
}
