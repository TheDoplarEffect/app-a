package com.example.appa

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View

/**
 * "Crystal glass" surface: a translucent fill (theme token) with a 1dp
 * hairline light border and a faint top-edge highlight — reads as frosted
 * glass over the animated aurora background. All values from tokens.
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

/** Glass fill with a subtle vertical sheen on top (two layers). */
fun glassLayered(context: Context, radiusDp: Int, strong: Boolean = false, t: DesignTokens = AppTheme.current): Drawable {
    val base = glassDrawable(context, radiusDp, strong, t)
    val r = context.dp(radiusDp).toFloat()
    val sheen = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(0x14FFFFFF.toInt(), 0x00FFFFFF, 0x00FFFFFF)).apply { cornerRadius = r }
    return LayerDrawable(arrayOf(base, sheen))
}

/** Apply a glass background to any view. */
fun View.glass(radiusDp: Int, strong: Boolean = false, t: DesignTokens = AppTheme.current) {
    background = glassLayered(context, radiusDp, strong, t)
}

/** Accent gradient drawable (buttons, active nav pill). */
fun Context.accentGradient(radiusDp: Int, t: DesignTokens = AppTheme.current): GradientDrawable {
    val r = dp(radiusDp).toFloat()
    return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(t.colors.accentStart, t.colors.accentEnd)).apply { cornerRadius = r }
}

/** Scrim gradient for the bottom of posters (text legibility). */
fun Context.posterScrim(heightDp: Int, t: DesignTokens = AppTheme.current): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
        intArrayOf(t.colors.scrimBottom, t.colors.scrimTop))
