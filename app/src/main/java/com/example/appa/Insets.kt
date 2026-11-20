package com.example.appa

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController

/**
 * Edge-to-edge handling. The app draws behind the status bar / navigation bar
 * / display cutout (pinhole camera), and each screen pads its content with
 * [View.padSystemBars] so nothing important hides under system UI — this is
 * what keeps the top-bar icons clear of the status bar and pinhole.
 */

/** Make the window edge-to-edge: transparent bars, theme window background, adaptive icon tint. */
fun Activity.edgeToEdge() {
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
    window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(AppTheme.current.colors.background))
    window.insetsController?.apply {
        systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // light icons when the theme background is dark (derived, not hard-coded)
        val lightIcons = luminance(AppTheme.current.colors.background) >= 0.5
        setSystemBarsAppearance(
            if (lightIcons) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS else 0,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        )
    }
}

/**
 * Pad this view by the system bars + display cutout on the chosen sides.
 * Original padding is preserved and the system-bar part is added on top.
 */
fun View.padSystemBars(
    top: Boolean = false,
    bottom: Boolean = false,
    start: Boolean = false,
    end: Boolean = false,
    extraTopDp: Int = 0,
    extraBottomDp: Int = 0
): View {
    val origL = paddingLeft; val origT = paddingTop; val origR = paddingRight; val origB = paddingBottom
    setOnApplyWindowInsetsListener { v, insets ->
        val sb = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        v.setPadding(
            origL + (if (start) sb.left else 0),
            origT + (if (top) sb.top + dp(extraTopDp) else 0),
            origR + (if (end) sb.right else 0),
            origB + (if (bottom) sb.bottom + dp(extraBottomDp) else 0)
        )
        insets
    }
    return this
}
