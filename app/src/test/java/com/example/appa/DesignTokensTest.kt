package com.example.appa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design-system invariants: WCAG contrast, monotonic scales, color math and
 * theme swappability. Pure JVM — no android imports in Tokens.kt.
 */
class DesignTokensTest {

    private val themes = listOf(CrystalGlassDark, PorcelainLight)

    @Test
    fun `primary text on background meets WCAG AAA (7 or more)`() {
        for (t in themes) {
            val r = contrastRatio(t.colors.textPrimary, t.colors.background)
            assertTrue("${t.name} primary contrast $r < 7", r >= 7.0)
        }
    }

    @Test
    fun `secondary text on background meets WCAG AA (4_5 or more)`() {
        for (t in themes) {
            val r = contrastRatio(t.colors.textSecondary, t.colors.background)
            assertTrue("${t.name} secondary contrast $r < 4.5", r >= 4.5)
        }
    }

    @Test
    fun `text on accent meets WCAG AA`() {
        for (t in themes) {
            val r = contrastRatio(t.colors.textOnAccent, t.colors.accentSolid)
            assertTrue("${t.name} onAccent contrast $r < 4.5", r >= 4.5)
        }
    }

    @Test
    fun `spacing scale is 4dp-based and strictly increasing`() {
        for (t in themes) {
            val s = with(t.space) { listOf(xs, sm, md, lg, xl, xxl) }
            s.forEach { assertEquals("spacing $it not multiple of 4", 0, it % 4) }
            for (i in 1 until s.size) assertTrue("not increasing at $i", s[i] > s[i - 1])
        }
    }

    @Test
    fun `type scale is strictly decreasing display to label`() {
        for (t in themes) {
            val s = with(t.type) { listOf(displaySp, headlineSp, titleSp, bodySp, labelSp) }
            for (i in 1 until s.size) assertTrue("type not decreasing at $i", s[i] < s[i - 1])
        }
    }

    @Test
    fun `radii are positive, pill fully rounded, sheet at least card at least poster`() {
        for (t in themes) {
            val r = t.radius
            assertTrue(r.pill >= 999)
            assertTrue(r.sheet >= r.card && r.card >= r.poster && r.poster > 0)
        }
    }

    @Test
    fun `motion durations are ordered`() {
        for (t in themes) {
            val m = t.motion
            assertTrue(m.instantMs < m.shortMs && m.shortMs < m.mediumMs && m.mediumMs < m.longMs)
            assertTrue(m.instantMs > 0)
        }
    }

    @Test
    fun `withAlpha replaces alpha channel`() {
        assertEquals(0x80112233.toInt(), withAlpha(0xFF112233.toInt(), 0.5f))
        assertEquals(0x00112233.toInt(), withAlpha(0xFF112233.toInt(), 0f))
        assertEquals(0xFF112233.toInt(), withAlpha(0x00112233.toInt(), 1f))
        // clamps out-of-range alpha
        assertEquals(0xFF112233.toInt(), withAlpha(0x00112233.toInt(), 5f))
    }

    @Test
    fun `contrastRatio is symmetric and white-on-black is 21`() {
        assertEquals(21.0, contrastRatio(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.05)
        val a = contrastRatio(0xFF818CF8.toInt(), 0xFF0A0C14.toInt())
        val b = contrastRatio(0xFF0A0C14.toInt(), 0xFF818CF8.toInt())
        assertEquals(a, b, 1e-9)
        assertEquals(1.0, contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
    }

    @Test
    fun `app theme is swappable`() {
        val before = AppTheme.current
        try {
            AppTheme.use(PorcelainLight)
            assertEquals(PorcelainLight, AppTheme.current)
            AppTheme.use(CrystalGlassDark)
            assertEquals(CrystalGlassDark, AppTheme.current)
        } finally {
            AppTheme.use(before)
        }
        AppTheme.reset()
        assertEquals(CrystalGlassDark, AppTheme.current)
    }

    @Test
    fun `glass surfaces are translucent (alpha below 255) in both themes`() {
        for (t in themes) {
            assertTrue("surfaceGlass should be translucent", (t.colors.surfaceGlass ushr 24) < 255)
            assertTrue("glass border should be subtle", (t.colors.glassBorder ushr 24) < 255)
        }
    }

    @Test
    fun `aurora blobs are distinct hues per theme`() {
        for (t in themes) {
            val set = setOf(t.colors.aurora1, t.colors.aurora2, t.colors.aurora3)
            assertEquals(3, set.size)
        }
    }
}
