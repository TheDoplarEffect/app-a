package com.example.appa

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator

/**
 * Motion system. All durations come from [MotionTokens] (via AppTheme), all
 * easing is defined here in one place — components never hard-code timing.
 *
 * Language follows Material 3 Expressive: enters use fast-out-slow-in
 * (decelerate), exits accelerate, emphasis uses a gentle overshoot.
 */

val FastOutSlowIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)   // standard enter
val LinearOutSlowIn = PathInterpolator(0f, 0f, 0.2f, 1f)    // emphasized enter
val FastOutLinearIn = PathInterpolator(0.4f, 0f, 1f, 1f)    // exit
val EmphasizedOvershoot = OvershootInterpolator(1.6f)       // pop

private fun View.motion() = AppTheme.current.motion

/** Fade + rise entrance. */
fun View.slideUpIn(delayMs: Long = 0, durationMs: Long = -1): View {
    val m = motion()
    alpha = 0f
    translationY = dp(24).toFloat()
    visibility = View.VISIBLE
    animate().alpha(1f).translationY(0f)
        .setStartDelay(delayMs)
        .setDuration(if (durationMs >= 0) durationMs else m.mediumMs)
        .setInterpolator(FastOutSlowIn)
        .start()
    return this
}

/** Simple fade entrance. */
fun View.fadeIn(delayMs: Long = 0, durationMs: Long = -1): View {
    val m = motion()
    alpha = 0f
    visibility = View.VISIBLE
    animate().alpha(1f)
        .setStartDelay(delayMs)
        .setDuration(if (durationMs >= 0) durationMs else m.shortMs)
        .setInterpolator(FastOutSlowIn)
        .start()
    return this
}

/** Pop entrance (scale + fade) for hero elements. */
fun View.popIn(delayMs: Long = 0): View {
    val m = motion()
    alpha = 0f
    scaleX = 0.85f
    scaleY = 0.85f
    visibility = View.VISIBLE
    animate().alpha(1f).scaleX(1f).scaleY(1f)
        .setStartDelay(delayMs)
        .setDuration(m.longMs)
        .setInterpolator(EmphasizedOvershoot)
        .start()
    return this
}

/** Staggered entrance for all children of a container (list/grid). */
fun ViewGroup.staggerIn(stepMs: Long = 45): ViewGroup {
    for (i in 0 until childCount) {
        val c = getChildAt(i)
        c.alpha = 0f
        c.translationY = dp(16).toFloat()
        c.animate().alpha(1f).translationY(0f)
            .setStartDelay(i * stepMs)
            .setDuration(motion().mediumMs)
            .setInterpolator(FastOutSlowIn)
            .start()
    }
    return this
}

/** Press feedback: scale down slightly while touched (Material ripple substitute). */
fun View.withPressScale(): View {
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.94f).scaleY(0.94f)
                .setDuration(motion().instantMs * 2).setInterpolator(FastOutSlowIn).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f)
                .setDuration(motion().shortMs).setInterpolator(EmphasizedOvershoot).start()
        }
        false // don't consume; click still fires
    }
    return this
}

/** Crossfade out an old view and in a new one (tab switches, refreshes). */
fun crossfadeOut(view: View, then: () -> Unit = {}) {
    view.animate().alpha(0f).setDuration(view.motion().shortMs).setInterpolator(FastOutLinearIn)
        .withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
            then()
        }.start()
}

// ------------------------------------------------------- activity transitions

/** Start an activity with a bottom-sheet-style rise transition (uses res/anim). */
fun Activity.openRising(intent: Intent) {
    startActivity(intent)
    overridePendingTransition(R.anim.slide_up_in, R.anim.fade_out_fast)
}

fun <T : Activity> Activity.openRisingTo(cls: Class<T>, extraKey: String? = null, extraVal: String? = null) {
    val i = Intent(this, cls)
    if (extraKey != null && extraVal != null) i.putExtra(extraKey, extraVal)
    openRising(i)
}

/** Finish with the matching dismiss transition. */
fun Activity.finishDropping() {
    finish()
    overridePendingTransition(R.anim.fade_in_fast, R.anim.slide_down_out)
}
