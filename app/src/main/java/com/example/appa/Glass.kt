package com.example.appa

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * "Liquid glass" surfaces: a translucent gradient fill + a gradient hairline
 * border (light on top, fading down) + a diagonal specular sheen. Over the
 * (pre-blurred) aurora backdrop this reads as frosted glass. The border color
 * adapts to the theme (white-ish on dark, black-ish on light) via the tested
 * [luminance] helper. All values from tokens.
 */

fun glassDrawable(context: Context, radiusDp: Int, strong: Boolean = false, t: DesignTokens = AppTheme.current): GradientDrawable {
    val c = t.colors
    return GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.dp(radiusDp).toFloat()
        setColor(if (strong) c.surfaceGlassStrong else c.surfaceGlass)
        setStroke(context.dp(1), c.glassBorder)
    }
}

/** Full liquid-glass layer list: gradient border + inset translucent fill + sheen. */
fun glassLayered(context: Context, radiusDp: Int, strong: Boolean = false, t: DesignTokens = AppTheme.current): Drawable {
    val r = context.dp(radiusDp).toFloat()
    val darkBg = luminance(t.colors.background) < 0.5
    val borderHi = if (darkBg) 0x40FFFFFF.toInt() else 0x30000000.toInt()  // bright top edge
    val borderLo = if (darkBg) 0x10FFFFFF.toInt() else 0x0D000000.toInt()  // fades down
    val border = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(borderHi, borderLo)).apply {
        cornerRadius = r
    }
    val base = if (strong) t.colors.surfaceGlassStrong else t.colors.surfaceGlass
    val fillTop = withAlpha(if (darkBg) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt(), ((base ushr 24) + if (strong) 26 else 16) / 255f)
    val fillBottom = base
    val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fillTop, fillBottom)).apply {
        cornerRadius = r - context.dp(1)
    }
    val insetFill = InsetDrawable(fill, context.dp(1))
    val sheen = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(0x1AFFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)
    ).apply { cornerRadius = r }
    return LayerDrawable(arrayOf(border, insetFill, sheen))
}

/** Apply a liquid-glass background to any view. */
fun View.glass(radiusDp: Int, strong: Boolean = false, t: DesignTokens = AppTheme.current) {
    background = glassLayered(context, radiusDp, strong, t)
}

/** Accent gradient drawable (buttons, active nav pill). */
fun Context.accentGradient(radiusDp: Int, t: DesignTokens = AppTheme.current): GradientDrawable {
    val r = dp(radiusDp).toFloat()
    return GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(t.colors.accentStart, t.colors.accentEnd)
    ).apply { cornerRadius = r }
}

/** Scrim gradient for the bottom of posters (text legibility). */
fun Context.posterScrim(heightDp: Int, t: DesignTokens = AppTheme.current): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(t.colors.scrimBottom, t.colors.scrimTop)
    )
