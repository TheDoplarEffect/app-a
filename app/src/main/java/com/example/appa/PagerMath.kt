package com.example.appa

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Pure pager math for [SwipePager] — unit-testable without Android.
 */
object PagerMath {

    /**
     * Decide the target page after a swipe gesture.
     * @param current current page index
     * @param dx horizontal drag distance in px (positive = dragged right)
     * @param velocityX fling velocity px/s (negative = fling left = next page)
     * @param pageWidth width of one page in px
     * @param pageCount total pages
     */
    fun targetPage(current: Int, dx: Int, velocityX: Float, pageWidth: Int, pageCount: Int, flingThresholdPxS: Float = 800f): Int {
        if (pageCount <= 0) return 0
        if (pageWidth <= 0) return current.coerceIn(0, pageCount - 1)
        val dragPages = -dx.toFloat() / pageWidth          // drag right -> previous page
        val flingPages = if (abs(velocityX) > flingThresholdPxS) -sign(velocityX) else 0f
        val target = current + (dragPages + flingPages).roundToInt()
        return target.coerceIn(0, pageCount - 1)
    }

    /** Clamp a scroll offset to the valid page range (with a small rubber-band allowance). */
    fun clampScroll(scrollX: Int, pageWidth: Int, pageCount: Int): Int {
        val max = (pageCount - 1).coerceAtLeast(0) * pageWidth
        return scrollX.coerceIn(0, max)
    }
}
