package com.example.appa

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.widget.OverScroller

/**
 * Minimal horizontal pager (no androidx): drag pages with your finger, fling
 * snaps to the nearest page. Each tab is one full-size child page, so tab
 * contents (and scroll positions) are preserved while swiping — that's the
 * tab caching. Page math lives in pure [PagerMath].
 */
class SwipePager(ctx: Context) : ViewGroup(ctx) {

    private val scroller = OverScroller(ctx, FastOutSlowIn)
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var lastX = 0f
    private var velocityTracker: android.view.VelocityTracker? = null

    var pageCount: Int = 0
    var current: Int = 0
        private set
    var onPageSelected: ((Int) -> Unit)? = null

    /** Programmatically go to a page (used by the bottom nav). */
    fun goToPage(i: Int, animate: Boolean = true) {
        val target = i.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
        current = target
        val dx = target * width - scrollX
        if (animate && dx != 0 && !context.reduceMotion()) {
            scroller.startScroll(scrollX, 0, dx, 0, AppTheme.current.motion.mediumMs.toInt())
            invalidate()
        } else {
            scrollTo(target * width, 0)
        }
        onPageSelected?.invoke(target)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        for (i in 0 until childCount) {
            getChildAt(i).layout(i * w, 0, (i + 1) * w, h)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                dragging = false
                scroller.forceFinished(true)
                velocityTracker = android.view.VelocityTracker.obtain()
                velocityTracker?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!dragging && kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    dragging = true
                    // claim the gesture; re-send the down so children don't misbehave
                    requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) releaseDrag(ev)
                dragging = false
                velocityTracker?.recycle(); velocityTracker = null
            }
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x
                scroller.forceFinished(true)
                if (velocityTracker == null) velocityTracker = android.view.VelocityTracker.obtain()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val x = ev.x
                val dx = x - lastX
                lastX = x
                scrollTo(PagerMath.clampScroll(scrollX - dx.toInt(), width, pageCount), 0)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (ev.actionMasked == MotionEvent.ACTION_UP) releaseDrag(ev) else goToPage(current)
                velocityTracker?.recycle(); velocityTracker = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun releaseDrag(ev: MotionEvent) {
        velocityTracker?.addMovement(ev)
        velocityTracker?.computeCurrentVelocity(1000)
        val vx = velocityTracker?.xVelocity ?: 0f
        val target = PagerMath.targetPage(current, (ev.x - downX).toInt(), vx, width, pageCount)
        goToPage(target)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidate()
        }
    }

    override fun shouldDelayChildPressedState(): Boolean = false
}
