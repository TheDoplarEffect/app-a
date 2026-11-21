package com.example.appa

/**
 * Design tokens — the ONLY place raw design values live. Pure Kotlin (no
 * android imports) so the whole design system is unit-testable on the JVM and
 * swappable at runtime: components read [AppTheme.current], so swapping
 * [AppTheme.use] with another [DesignTokens] re-skins every component.
 *
 * Structure follows atomic design: tokens (atoms' raw values) -> Components.kt
 * (atoms) -> screens (organisms). To change the design system later, edit or
 * replace the token sets here — components never hard-code values.
 */

// ------------------------------------------------------------------ colors

/** ARGB color tokens. Surfaces are translucent ("crystal glass") over the aurora background. */
data class ColorTokens(
    val background: Int,            // screen base
    val aurora1: Int, val aurora2: Int, val aurora3: Int, // animated backdrop blobs
    val surfaceGlass: Int,          // translucent card fill (alpha included)
    val surfaceGlassStrong: Int,    // app bar / nav bar
    val glassBorder: Int,           // 1dp hairline stroke
    val textPrimary: Int,
    val textSecondary: Int,
    val textOnAccent: Int,
    val accentStart: Int,           // gradient start (buttons, active pill)
    val accentEnd: Int,
    val accentSolid: Int,           // solid accent fill (white text on it must pass)
    val accentText: Int,            // accent as TEXT on glass surfaces (must pass vs background)
    val success: Int, val warning: Int, val error: Int, val info: Int,
    val scrimTop: Int,              // poster scrim gradient (transparent)
    val scrimBottom: Int,           // poster scrim gradient (opaque-ish)
    val chipScrim: Int              // rating-chip background on posters
)

// ------------------------------------------------------------------ type

/** Type scale in sp. display > headline > title > body > label. */
data class TypeTokens(
    val displaySp: Float,
    val headlineSp: Float,
    val titleSp: Float,
    val bodySp: Float,
    val labelSp: Float
)

// ------------------------------------------------------------------ space

/** 4dp-based spacing scale, strictly increasing. */
data class SpaceTokens(
    val xs: Int, val sm: Int, val md: Int, val lg: Int, val xl: Int, val xxl: Int
)

// ------------------------------------------------------------------ radius

data class RadiusTokens(
    val poster: Int,   // poster cards
    val card: Int,     // glass cards
    val button: Int,   // buttons / fields
    val sheet: Int,    // bottom sheets / hero
    val pill: Int,     // chips, badges (fully rounded)
    val thumb: Int,    // small thumbnails (request rows)
    val navBar: Int    // bottom navigation bar
)

// ------------------------------------------------------------------ motion

data class MotionTokens(
    val instantMs: Long,
    val shortMs: Long,
    val mediumMs: Long,
    val longMs: Long
)

// ------------------------------------------------------------------ elevation

data class ElevationTokens(
    val level1Dp: Float, val level2Dp: Float, val level3Dp: Float,
    val shadowColor: Int
)

// ------------------------------------------------------------------ the tokens object

data class DesignTokens(
    val name: String,
    val colors: ColorTokens,
    val type: TypeTokens,
    val space: SpaceTokens,
    val radius: RadiusTokens,
    val motion: MotionTokens,
    val elevation: ElevationTokens
)

/** "Crystal Glass" dark theme — translucent surfaces over an animated aurora backdrop. */
val CrystalGlassDark = DesignTokens(
    name = "crystal-glass-dark",
    colors = ColorTokens(
        background = 0xFF0A0C14.toInt(),
        aurora1 = 0xFF7C3AED.toInt(),   // violet
        aurora2 = 0xFF22D3EE.toInt(),   // cyan
        aurora3 = 0xFFEC4899.toInt(),   // magenta
        surfaceGlass = 0x8C0A0C14.toInt(),        // 55% bg-tinted glass — aurora still shows through
        surfaceGlassStrong = 0xB30A0C14.toInt(),  // 70% for app bar / nav (keeps text contrast on blobs)
        glassBorder = 0x2EFFFFFF.toInt(),         // hairline light stroke
        textPrimary = 0xFFF2F5FF.toInt(),
        textSecondary = 0xFFC7CEDD.toInt(),
        textOnAccent = 0xFFFFFFFF.toInt(),
        accentStart = 0xFF4F46E5.toInt(),  // indigo-600: white label passes WCAG AA on the whole gradient
        accentEnd = 0xFF9333EA.toInt(),    // purple-600
        accentSolid = 0xFF6D5AE0.toInt(),  // dark enough for white text (WCAG AA, 5:1)
        accentText = 0xFFA5B4FC.toInt(),   // indigo-300: accent-as-text on dark glass (WCAG AA)
        success = 0xFF34D399.toInt(),
        warning = 0xFFFBBF24.toInt(),
        error = 0xFFFCA5A5.toInt(),        // light enough to pass on the 22%-tinted dark chip
        info = 0xFFBAE6FD.toInt(),   // bright enough to keep 3:1 over the brightest aurora blob
        scrimTop = 0x00000000,
        scrimBottom = 0xD9000000.toInt(),
        chipScrim = 0xB3000000.toInt()
    ),
    type = TypeTokens(displaySp = 28f, headlineSp = 22f, titleSp = 17f, bodySp = 14f, labelSp = 11f),
    space = SpaceTokens(xs = 4, sm = 8, md = 12, lg = 16, xl = 24, xxl = 32),
    radius = RadiusTokens(poster = 20, card = 24, button = 16, sheet = 32, pill = 999, thumb = 12, navBar = 28),
    motion = MotionTokens(instantMs = 80, shortMs = 160, mediumMs = 300, longMs = 500),
    elevation = ElevationTokens(level1Dp = 4f, level2Dp = 12f, level3Dp = 24f, shadowColor = 0x66000000.toInt())
)

/** Light theme — proves the system is swappable (same structure, different values). */
val PorcelainLight = DesignTokens(
    name = "porcelain-light",
    colors = ColorTokens(
        background = 0xFFF4F6FB.toInt(),
        aurora1 = 0xFFC7D2FE.toInt(),
        aurora2 = 0xFFBAE6FD.toInt(),
        aurora3 = 0xFFFBCFE8.toInt(),
        surfaceGlass = 0xB3FFFFFF.toInt(),        // ~70% white
        surfaceGlassStrong = 0xD9FFFFFF.toInt(),
        glassBorder = 0x1A000000.toInt(),
        textPrimary = 0xFF101828.toInt(),
        textSecondary = 0xFF475467.toInt(),
        textOnAccent = 0xFFFFFFFF.toInt(),
        accentStart = 0xFF4F46E5.toInt(),  // white label passes on the whole gradient
        accentEnd = 0xFF9333EA.toInt(),
        accentSolid = 0xFF5B4BD6.toInt(),  // dark enough for white text (WCAG AA)
        accentText = 0xFF4F46E5.toInt(),   // accent-as-text on light glass (WCAG AA)
        success = 0xFF024E36.toInt(),   // pass WCAG AA on the tinted pill over glass
        warning = 0xFF78350F.toInt(),
        error = 0xFF9F1239.toInt(),
        info = 0xFF064E75.toInt(),
        scrimTop = 0x00000000,
        scrimBottom = 0xD9000000.toInt(),
        chipScrim = 0xB3000000.toInt()
    ),
    type = CrystalGlassDark.type,
    space = CrystalGlassDark.space,
    radius = CrystalGlassDark.radius,
    motion = CrystalGlassDark.motion,
    elevation = ElevationTokens(4f, 12f, 24f, 0x33000000.toInt())
)

/** The active design system. Swap at runtime with [use] to re-skin the app. */
object AppTheme {
    @Volatile var current: DesignTokens = CrystalGlassDark
        private set

    fun use(tokens: DesignTokens) { current = tokens }
    fun reset() { current = CrystalGlassDark }
}

// ------------------------------------------------------------------ pure color math (testable)

/** Replace the alpha channel of an ARGB color. alpha in 0..1, rounded to nearest. */
fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (color and 0x00FFFFFF) or (a shl 24)
}

/** Scale the existing alpha channel by [factor] (may exceed 1, clamps at 255). Keeps the RGB from the token. */
fun scaledAlpha(color: Int, factor: Float): Int {
    val a = ((color ushr 24) * factor).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

private fun srgbToLinear(c: Int): Double {
    val v = c / 255.0
    return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
}

/** Relative luminance per WCAG 2.x (0..1). */
fun luminance(color: Int): Double {
    val r = srgbToLinear((color shr 16) and 0xFF)
    val g = srgbToLinear((color shr 8) and 0xFF)
    val b = srgbToLinear(color and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG contrast ratio between two colors (1..21). */
fun contrastRatio(c1: Int, c2: Int): Double {
    val l1 = luminance(c1)
    val l2 = luminance(c2)
    val hi = maxOf(l1, l2)
    val lo = minOf(l1, l2)
    return (hi + 0.05) / (lo + 0.05)
}

/** Alpha-composite [fg] (ARGB with alpha) over opaque [bg]. */
fun compositeOver(fg: Int, bg: Int): Int {
    val a = (fg ushr 24) / 255f
    val r = ((fg shr 16) and 0xFF) * a + ((bg shr 16) and 0xFF) * (1 - a)
    val g = ((fg shr 8) and 0xFF) * a + ((bg shr 8) and 0xFF) * (1 - a)
    val b = (fg and 0xFF) * a + (bg and 0xFF) * (1 - a)
    return 0xFF000000.toInt() or ((r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt())
}

/**
 * Poster ink by the WORST gradient endpoint (WCAG AA on both ends).
 * Returns (ink, needsScrim): mid-luminance pairs where neither ink passes get
 * white ink plus a translucent scrim behind the text (see GradientPoster).
 */
fun posterInk(c1: Int, c2: Int): Pair<Int, Boolean> {
    val l1 = luminance(c1); val l2 = luminance(c2)
    val minL = minOf(l1, l2); val maxL = maxOf(l1, l2)
    val white = 0xFFFFFFFF.toInt(); val dark = 0xFF101828.toInt()
    return when {
        maxL <= 0.1833 -> white to false
        minL >= 0.2223 -> dark to false
        else -> white to true
    }
}
