# Round 1 — UI / Theming Consistency Review (app-a)

**Lens:** hard-coded colors / dp / sp / radii / durations bypassing `AppTheme` tokens; visual consistency (paddings, radii classes, chip/pill usage, dark-theme contrast).
**Scope:** `app/src/main/java/com/example/appa/*.kt` (+ `MainActivity.java`), `app/src/main/res/values/themes.xml`, `app/src/main/res/anim/*.xml`. Read-only review — no app files touched.

## References consulted

- **Material Design 3 — Design tokens** (m3.material.io/foundations/design-tokens/overview): *"Design tokens are the building blocks of all UI elements. The same tokens are used in designs, tools, and code."* → raw values belong in one token layer; nothing downstream may restate them.
- **Material Design 3 — Color roles** (m3.material.io/styles/color/roles): color should be applied through **semantic roles** (e.g. `on-primary`, `surface`, `scrim`) — *"the connective tissue between elements of the UI and what color goes where"* — not raw hex at the component level. `Tokens.kt`'s `ColorTokens` is exactly this role layer; every raw `0x…` outside it violates the role model.
- **Material Design 3 — Color contrast / accessibility** (m3.material.io/foundations/designing/color-contrast): text needs **WCAG 4.5:1** (3:1 for large text), and non-text elements such as button/chip containers need **3:1** against their background. Contrast ratios below were computed with the same WCAG math as `Tokens.kt` (`contrastRatio`).
- **Atomic Design, Ch. 2 (Brad Frost, atomicdesign.bradfrost.com/chapter-2)**: atoms are the foundational building blocks whose *innate properties* (dimensions, font size) must stay consistent system-wide — in this codebase that means atoms read **all** values from `Tokens.kt` (`Components.kt` header says exactly this: *"components never hard-code values"*); screens (organisms) compose atoms and add no raw values.

The architecture itself (`Tokens.kt` → `Components.kt` → screens) is sound and mostly honored. Violations below.

---

## Findings

### HIGH

1. **Status pills put white text on bright status colors — contrast 1.7–2.7:1, fails WCAG AA (4.5:1) and M3's 3:1 for chip containers.**
   - `Components.kt:337` — `Chip(..., fg: Int = 0xFFFFFFFF.toInt(), ...)` hard-codes white as default foreground (should be a role token, not a hex literal).
   - `Components.kt:348-371` (`statusColorOf`/`StatusPill`) — `StatusPill` passes only `bg = statusColorOf(...)` and inherits the white default. Measured (dark theme): white on `warning 0xFFFBBF24` = **1.67:1**, on `success 0xFF34D399` = **1.92:1**, on `info 0xFF38BDF8` = **2.14:1**; light theme 3.19–4.10:1 — all fail. Used at `Components.kt:461` (poster badge) and `RequestsActivity.kt:119`.
   - Note the same file does it *right* elsewhere: `ModeBanner` (`Components.kt:578-581`) uses `withAlpha(color, 0.18f)` bg + colored fg — so chip styling is internally inconsistent.
   - **Fix:** in `StatusPill`, use the `ModeBanner` pattern — `Chip(ctx, label, withAlpha(statusColor, 0.18f), statusColor, t)` — or add `ColorTokens.statusBg`/`textOnStatus` roles (M3-style on-color per status role). Also change `Chip`'s default `fg` to `null` → `t.colors.textOnAccent` so the default can never silently mismatch the bg.

2. **`GradientPoster` hard-codes `Color.WHITE` for all painted text/decoration — bypasses tokens and breaks the light theme + contrast on light posters.**
   - `Components.kt:390` (`titlePaint.color = Color.WHITE`), `:393` (`withAlpha(Color.WHITE, 0.85f)` label), `:406` (`withAlpha(Color.WHITE, 0.08f)` deco circle) — the class even receives `t: DesignTokens` but never uses it for color.
   - Placeholder poster gradients include light pairs (e.g. `Catalog.kt` `#116329→#aeaefe`): white on `#AEAEFE` = **2.04:1** — fails even in the dark theme. Under `PorcelainLight` the swap does nothing for posters.
   - **Fix:** paint with `t.colors.textOnAccent` (title), `withAlpha(t.colors.textOnAccent, 0.85f)` (label), `withAlpha(t.colors.textOnAccent, 0.08f)` (deco). (Text size is intentionally proportional to view width in `onDraw` — that's canvas art, acceptable; only the colors are the violation.)

### MEDIUM

3. **Rating chip background `0xB3000000` hard-coded in three places instead of a color role.**
   - `Components.kt:455` (poster card), `DetailActivity.kt:79` and `:80` (hero overlay chips). It's a raw ARGB literal outside `Tokens.kt`; closest existing role is `colors.scrimBottom` (`0xD9000000`).
   - **Fix:** add `ColorTokens.chipScrim: Int` (e.g. `0xB3000000`) to `Tokens.kt` and use it in all three call sites; pass `fg = t.colors.textOnAccent` explicitly (white on `0xB3000000` measures 21:1, so keep light text, but via token).

4. **`Glass.kt` hard-codes its border/sheen colors despite the header comment "All values from tokens"; contains a dead theme branch.**
   - `Glass.kt:32-33` — `borderHi/borderLo` = `0x40FFFFFF/0x10FFFFFF` (dark) vs `0x30000000/0x0D000000` (light); `:46` sheen `0x1AFFFFFF → 0x00FFFFFF`; `:38` — `if (darkBg) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt()` is a no-op branch (light-theme fill identical to dark).
   - **Fix:** move these to `ColorTokens` as roles (`glassBorderHi`, `glassBorderLo`, `glassSheen`) per theme, and delete the dead ternary on `:38` (derive `fillTop` from `base` alpha only).

5. **`res/values/themes.xml` duplicates `CrystalGlassDark` tokens as raw hex, and the system-bar icon appearance is hard-coded for dark.**
   - `themes.xml:4` `windowBackground=#0A0C14` and `:5` `textColorPrimary=#F2F5FF` restate `colors.background`/`colors.textPrimary`; `:8` `windowLightStatusBar=false`. `Insets.kt:23` `setSystemBarsAppearance(0, APPEARANCE_LIGHT_STATUS_BARS or …)` forces dark-theme (white) icons. If `AppTheme.use(PorcelainLight)` ever runs, the window background and status-bar icons stay dark.
   - **Fix:** set `android:windowBackground` to `@android:color/transparent` (the aurora root paints `colors.background` anyway, so there is nothing to duplicate), drop `textColorPrimary` (every `AppText` sets its own color), and in `Insets.kt` compute `appearance` from `luminance(AppTheme.current.colors.background) < 0.5` (the helper already exists in `Tokens.kt`).

6. **`RequestsActivity` thumb uses a hard-coded radius `12` and size `44×64dp` that exist in no token scale.**
   - `RequestsActivity.kt:100` and `:105` — `roundedClip(12)`: `RadiusTokens` has poster 20 / card 24 / button 16 / sheet 32 / pill 999 — 12 is off-scale. `:102`, `:106` — `dp(44)`, `dp(64)` literals; `:116` `marginStart = dp(10)` (10 is off the 4dp `SpaceTokens` scale the `DesignTokensTest` enforces).
   - **Fix:** add `RadiusTokens.thumb` (or reuse `t.radius.button`) and use `roundedClip(t.radius.thumb)`; replace `dp(44)/dp(64)` with named dimension tokens (or `t.space`-derived values) and `dp(10)` → `t.space.sm` (8) or `t.space.md` (12).

7. **Off-scale spacing literals `dp(6)` / `dp(10)` in screens bypass the 4dp `SpaceTokens` scale.**
   - `DetailActivity.kt:81,119,127` (`marginStart = dp(6)`), `SettingsActivity.kt:119` (`marginEnd = dp(6)`), `RequestsActivity.kt:116` (`dp(10)`). The scale is xs 4 / sm 8 / md 12 / lg 16 (test asserts multiples of 4 — literals sidestep it), and chip-to-chip gaps are inconsistent (6 vs 8).
   - **Fix:** use `t.space.xs` (4) or `t.space.sm` (8) consistently for chip gaps — pick one class for "gap between chips" app-wide.

### LOW

8. **Hero metrics hard-coded in `DetailActivity`, and scrim height inconsistent with poster cards.**
   - `DetailActivity.kt:59` `dp(300)` hero height; `:71-72` `posterScrim(140, t)` + `dp(140)` — while `PosterCard` uses a 56dp scrim (`Components.kt:454`, `ctx.dp(56)`). Same visual element (bottom scrim), two hard-coded heights.
   - **Fix:** add dimension tokens (e.g. `SpaceTokens`-adjacent `heroHeightDp`, `scrimHeightDp`/`scrimHeightHeroDp`) in `Tokens.kt`; use them in both places.

9. **Hard-coded durations bypassing `MotionTokens`.**
   - `Components.kt:321` — search debounce `postDelayed(pending!!, 350)` (350 matches no token: short 160 / medium 300).
   - `AuroraBackground.kt:47` — ambient loop `duration = 24_000`; `:89` blob alpha `52` hard-coded.
   - `res/anim/slide_up_in.xml:4-5` (320/240ms), `slide_down_out.xml:4-5` (280/220), `fade_*.xml:3` (180) — XML can't read `AppTheme`, but the values drift from `MotionTokens` (short 160 / medium 300).
   - `MainScreen.kt:58,123` and `RequestsActivity.kt:68,71,75` — 5000/1000ms poll intervals (data polling, not motion — flagging only because they're magic durations; a named constant is enough).
   - **Fix:** add `MotionTokens.debounceMs = 350` and `MotionTokens.ambientMs = 24_000` (and an `auroraAlpha` token) and use them; align the anim XML to 160/300 where feasible.

10. **`Motion.kt` translation distances are literals that duplicate space tokens.**
    - `Motion.kt:34` `translationY = dp(24)` (= `space.xl`), `:77` `dp(16)` (= `space.lg`); `:92` `motion().instantMs * 2` is an off-scale derived duration.
    - **Fix:** read `AppTheme.current.space.xl` / `.lg` and add a named token (e.g. `pressMs`) instead of `instantMs * 2`.

11. **Elevation/alpha literals in atoms bypass `ElevationTokens`.**
    - `Components.kt:241` — nav pill `elevation = ctx.dp(3)` (token says `level1Dp = 4f`); `Components.kt:184` — disabled button `alpha = 0.45f` hard-coded.
    - **Fix:** `t.elevation.level1Dp`; add a `disabledAlpha` token (or reuse withAlpha pattern).

12. **`DetailActivity` re-implements the rounded-clip atom inline instead of calling it.**
    - `DetailActivity.kt:51-56` manually sets `clipToOutline` + `outlineProvider` with `t.radius.sheet`, duplicating `View.roundedClip` (`Components.kt:82-91`). Drift risk (two sources for the same radius application).
    - **Fix:** replace with `hero.roundedClip(t.radius.sheet)` after `glass(...)`.

13. **Atoms internally hard-code metrics where `SpaceTokens` already has the value (atomic-design violation: atoms must not hard-code).**
    - `Components.kt:143` `ctx.dp(40)` button size, `:151,166` `dp(2)` badge offset, `:181` `dp(20)/dp(12)` primary padding (12 = `space.md`, 20 off-scale), `:194` `dp(16)/dp(12)` (lg/md), `:205` `dp(14)/dp(10)` (both off-scale), `:234` `dp(6)` (off-scale), `:249` `dp(8)` (sm), `:251` `dp(48)` pill height, `:296` `dp(14)/dp(10)`, `:344` `dp(8)/dp(3)` chip padding (3 off-scale), `:376` `GlassCard` default `padDp = 16` (lg) OK but `:535` `MagnetRow` passes literal `12` (= `space.md`), `:499` `dp(32)` (= `space.xxl`), `:125` `dp(12)` (= md), `:131` `marginStartDp = 8` (= sm), `:442` `dp(150)` poster min-height, `:454` `dp(56)` scrim, `:457,461` `dp(6)`.
    - **Fix:** where a value matches the scale use `t.space.*`; add named tokens for the rest (e.g. `SpaceTokens.iconButton = 40`, `navPillHeight = 48`, `chipPadH = 8`, `chipPadV = 4`) in `Tokens.kt` so atoms stay value-free.

14. **Main grid structure hard-coded; skeleton height duplicated.**
    - `MainScreen.kt:127,133` `columnCount = 3; useDefaultMargins = true` (grid gutters come from Android default margins, not tokens); `:129` `Skeleton(activity, 0, 150, t)` duplicates the poster min-height literal `ctx.dp(150)` at `Components.kt:442`.
    - **Fix:** add a layout token (e.g. `gridColumns = 3`) and one shared `posterMinHeightDp` token used by both skeleton and `PosterCard`.

---

## Areas checked and clean

- **Type scale:** every `setTextSize` goes through `t.type.*` (`Components.kt:74,302,556,570`); no literal `sp` anywhere outside `Density.kt`, which is the single dp/sp conversion point. Clean.
- **Screen-level padding rhythm:** all four screens use `padSystemBars(extraTopDp/extraBottomDp = t.space.sm)` + horizontal `dp(t.space.lg)` (`MainScreen.kt:60-61`, `DetailActivity.kt:39-40`, `RequestsActivity.kt:31-32`, `SettingsActivity.kt:28-29`). Consistent.
- **Radius classes:** poster cards, hero, nav bar, buttons/fields, chips all use `t.radius.poster/sheet/card/button/pill` — the only outlier is the `roundedClip(12)` thumb (finding 6).
- **Text colors:** `AppText` maps styles to `textPrimary`/`textSecondary` roles by default (`Components.kt:76-79`); measured contrast on background and on glass-strong-over-background is ≥ 7:1 in both themes (matches `DesignTokensTest`). Clean.
- **Image loading placeholder** uses `AppTheme.current.colors.surfaceGlassStrong` (`ImageLoader.kt:31`) instead of a hard-coded fill. Clean.
- **Accent gradient / scrim** (`Glass.kt:56-70`) are fully token-driven (`accentStart/End`, `scrimTop/Bottom`). Clean.
- **Non-UI files** (`Clients.kt`, `Http.kt`, `LiveRepository.kt`, `SecureStore.kt`, `Config.kt`, `Catalog.kt` data) contain no theming values. Clean.
