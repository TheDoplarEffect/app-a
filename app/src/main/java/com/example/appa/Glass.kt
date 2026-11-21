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

/** Full liquid-glass layer list: gradient border + inset translucent fill + sheen. */
fun glassLayered(context: Context, radiusDp: Int, strong: Boolean = false, t: DesignTokens = AppTheme.current): Drawable {
    val r = context.dp(radiusDp).toFloat()
    val darkBg = luminance(t.colors.background) < 0.5
    // border color comes from the token, brightened for the top edge (no dead ternary)
    val borderHi = scaledAlpha(t.colors.glassBorder, if (darkBg) 1.6f else 1.0f)
    val borderLo = scaledAlpha(t.colors.glassBorder, if (darkBg) 0.4f else 0.6f)
    val border = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(borderHi, borderLo)).apply {
        cornerRadius = r
    }
    val base = if (strong) t.colors.surfaceGlassStrong else t.colors.surfaceGlass
    val fillTop = withAlpha(if (darkBg) 0xFFFFFFFF.toInt() else 0xFF000000.toInt(), ((base ushr 24) + if (strong) 26 else 16) / 255f)
    val fill = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(fillTop, base)).apply {
        cornerRadius = r - context.dp(1)
    }
    val insetFill = InsetDrawable(fill, context.dp(1))
    val sheenColor = if (darkBg) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
    val sheen = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(withAlpha(sheenColor, 0.10f), withAlpha(sheenColor, 0f), withAlpha(sheenColor, 0f))
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
