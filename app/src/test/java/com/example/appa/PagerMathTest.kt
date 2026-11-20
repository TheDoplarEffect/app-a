package com.example.appa

import org.junit.Assert.assertEquals
import org.junit.Test

class PagerMathTest {

    private val w = 1000
    private val n = 3

    @Test
    fun `small drag snaps back to current page`() {
        assertEquals(1, PagerMath.targetPage(1, 200, 0f, w, n))   // 0.2 page
        assertEquals(1, PagerMath.targetPage(1, -200, 0f, w, n))
    }

    @Test
    fun `half page drag moves one page`() {
        assertEquals(0, PagerMath.targetPage(1, 600, 0f, w, n))   // dragged right half page
        assertEquals(2, PagerMath.targetPage(1, -600, 0f, w, n))  // dragged left half page
    }

    @Test
    fun `fling overrides small drag`() {
        assertEquals(2, PagerMath.targetPage(1, 100, -2000f, w, n)) // fling left
        assertEquals(0, PagerMath.targetPage(1, -100, 2000f, w, n)) // fling right
    }

    @Test
    fun `slow velocity below threshold is ignored`() {
        assertEquals(1, PagerMath.targetPage(1, 100, -700f, w, n))
    }

    @Test
    fun `clamped at edges`() {
        assertEquals(0, PagerMath.targetPage(0, 900, 0f, w, n))
        assertEquals(2, PagerMath.targetPage(2, -900, 0f, w, n))
        assertEquals(0, PagerMath.targetPage(0, 500, 5000f, w, n))
        assertEquals(2, PagerMath.targetPage(2, -500, -5000f, w, n))
    }

    @Test
    fun `clampScroll stays within pages`() {
        assertEquals(0, PagerMath.clampScroll(-50, w, n))
        assertEquals(2000, PagerMath.clampScroll(2500, w, n))
        assertEquals(1000, PagerMath.clampScroll(1000, w, n))
    }

    @Test
    fun `degenerate inputs are safe`() {
        assertEquals(0, PagerMath.targetPage(0, 100, 0f, 0, n))
        assertEquals(0, PagerMath.targetPage(0, 100, 0f, w, 0))
    }
}
