# Round 3 — Theming / Design-System Review (lens: theming)

Scope: `/home/dev/app-a/app/` only (Kotlin sources under `app/src/main/java/com/example/appa/`, `res/`). No prior review material was read; all findings are derived from the code plus the three references below.

References used:
- **WCAG 2.1 SC 1.4.3** (https://www.w3.org/TR/WCAG21/#contrast-minimum): text (under 18pt, or under 14pt bold) needs a contrast ratio ≥ **4.5:1**; large text ≥ 3:1. Almost all app text is 11–17sp, i.e. "normal" text → 4.5:1 applies. Relative luminance and ratio computed exactly per the WCAG formula (same math as `Tokens.kt` `luminance`/`contrastRatio`).
- **Material Design 3** (https://m3.material.io): one motion system (consistent durations/easing, `MotionTokens`), shape system (consistent corner radii per component role), color system with accessible contrast, 48dp minimum touch targets, elevation levels.
- **Atomic Design** (https://atomicdesign.bradfrost.com): atoms must not contain hard-coded values — all raw values live in tokens (this is also the explicit promise in `Tokens.kt:8-12`: "components never hard-code values").

Contrast numbers below were computed with the WCAG formula, including **alpha compositing** (`compositeOver`) of the aurora blobs (alpha 52/255 ≈ 0.20, `AuroraBackground.kt:94`) and the translucent glass layers (`Glass.kt:24-46`, which adds up to +26/255 white on strong glass) over the theme background — i.e. the backdrop that actually appears behind every translucent surface.

---

## Findings (ranked)

### 1. HIGH — Contrast is only validated against the flat background, but every surface is translucent glass over the animated aurora; real on-screen contrast fails WCAG 1.4.3
**Files:** `Tokens.kt:87-95` (surfaceGlass = 8% white, surfaceGlassStrong = 12% white), `Glass.kt:24-46` (glassLayered adds fillTop up to +26 alpha white and a 0.10 sheen), `AuroraBackground.kt:94-96` (blobs at alpha 52/255), `DesignTokensTest.kt:122-145` (tests composite tints only over the *flat* `background` token), instances: `Components.kt:213` (SecondaryButton accentText label), `Components.kt:400` (StatusPill), `Components.kt:64-68` (LABEL text = textSecondary), `DetailActivity.kt:172` (meta chips: textSecondary on surfaceGlassStrong), `MainScreen.kt:82-86` (ModeBanner).

**What is wrong:** `DesignTokensTest` proves 4.5:1+ only against the opaque `background` color. On screen, text sits on `compositeOver(glass, compositeOver(auroraBlob, background))`. Computed worst-but-realistic cases (single blob center, which is common since blobs are large radial gradients):
- Dark theme, **textSecondary on weak glass over 1 blob = 4.28:1** (fails 4.5); on strong glass = **2.96:1**; over 3 overlapping blobs = 2.59:1.
- Dark theme, **accentText on strong glass over 1 blob = 3.08:1** (fails) — this is the SecondaryButton/"See all" label.
- Dark theme, **status pill fg on its own 0.22 tint over glass-over-1-blob = 3.0–3.6:1** (success 3.27, warning 3.59, error 3.20, info 3.00 — all fail).
- Light theme, **success on its own 0.22 pill over glass = 4.32–4.42:1** (fails AA); success banner (0.18 tint over 3-blob backdrop) = 4.07:1.

**Why it matters:** WCAG 2.1 SC 1.4.3 requires 4.5:1 for the 11–14sp text used everywhere here. The token test gives false confidence because the tested backdrop never exists on screen — the glass design's entire point is translucency over the aurora. This is the single biggest theming defect: the design system validates contrast against the wrong surface.

**Fix:** In `Tokens.kt` add a computed `effectiveSurface` / `effectiveSurfaceStrong` per theme = `compositeOver(surfaceGlass(Strong), compositeOver(auroraWorstCase, background))` (auroraWorstCase = all three blobs at their center alpha composited). Extend `DesignTokensTest` to assert ≥4.5:1 (and ≥7:1 for `labelSp` text, SC 1.4.6) for textPrimary/textSecondary/accentText/status colors against these effective surfaces in **both** themes. Then retune: raise `surfaceGlass` alpha (e.g. 0x14→0x26) or lower blob alpha (52→~28), brighten dark `textSecondary` (0xFFAAB4CE → ~0xFFC7D0E4), darken light-theme `success` (~0xFF02543B), and keep white text only on `accentSolid`/opaque scrims.

### 2. HIGH — GradientPoster text-color switch has a luminance "dead zone", and the type label at the top is drawn with no scrim
**Files:** `Components.kt:424-428` (switch threshold `avg > 0.183`), `Components.kt:433` (labelPaint alpha 0.85), `Components.kt:451-452` (type label drawn at `h*0.12`), `Components.kt:456-461` (scrim rect only covers `h*0.30`–`h*0.85`).

**What is wrong:** The white/dark text switch at luminance 0.183 leaves a dead zone: for gradient average luminance L ∈ (0.183, 0.234), **neither** color reaches 4.5:1 — computed: at L=0.20, white gives 4.20:1 and dark text (0xFF101828) gives 4.23:1, both failing WCAG 1.4.3. Additionally the media-type label at the top of the poster (`h*0.12`) is drawn with alpha-0.85 text and **no scrim behind it** (the scrim only starts at 0.30h), so over a mid-luminance gradient it measures ~3.3:1.

**Why it matters:** Posters are generated from arbitrary `media.color1/color2` gradients (`Components.kt:420-421`), so mid-luminance posters are guaranteed to occur in the catalog; their titles/labels can fall below 4.5:1, violating SC 1.4.3, and M3's legibility-over-image guidance (scrim behind all overlaid text).

**Fix:** Compute both ratios and pick the better, then enforce a floor: clamp/verify `min(ratio(white, grad), ratio(dark, grad)) ≥ 4.5` by shifting the gradient's luminance out of the dead zone (or compositing the existing scrim into the computation and extending the scrim rect from `0f` to `h*0.85f` so the type label is covered too). Put the type label on a `chipScrim` pill like the rating chip (`Components.kt:509`) as a simpler alternative.

### 3. MEDIUM — Activity transitions bypass `MotionTokens` (hardcoded durations in `res/anim`)
**Files:** `Motion.kt:112-126` (`openRising`/`finishDropping` → `R.anim.slide_up_in` etc.), `res/anim/slide_up_in.xml` (320ms translate / 240ms alpha), `slide_down_out.xml` (280/220), `fade_in_fast.xml`/`fade_out_fast.xml` (180).

**What is wrong:** In-code animations use `MotionTokens` (shortMs=160, mediumMs=300, longMs=500), but the activity rise/drop transitions hardcode 320/280/240/220/180ms in XML. The same "sheet rises" gesture is 300ms in Compose-style code (`slideUpIn` default `mediumMs`) but 320ms when opening a screen.

**Why it matters:** M3 (m3.material.io motion) requires one consistent duration/easing system; mixed sources make motion feel arbitrary, and the XML values silently drift from the tokens (they already differ: 320 vs 300). Atomic Design: raw values belong in tokens only.

**Fix:** Add `enterMs`/`exitMs` (and reuse `FastOutSlowIn`/`AccelerateInterpolator`) to `MotionTokens`, generate or programmatically set the anim resources from them (or replace `overridePendingTransition` with `ViewPropertyAnimator` on the entering window decor using the tokens), and delete the hardcoded XML durations.

### 4. MEDIUM — Motion parameters hardcoded outside `MotionTokens` (ambient loop, debounce, stagger, travel distance, press scale)
**Files:** `AuroraBackground.kt:52` (`duration = 24_000`), `AuroraBackground.kt:94` (blob `alpha = 52`), `Components.kt:356` (search debounce `postDelayed(..., 350)`), `Motion.kt:43` (travel `dp(24)`), `Motion.kt:79` (travel `dp(16)`), `Motion.kt:75` + `DetailActivity.kt:289` + `RequestsActivity.kt:155` (stagger step 45 default vs 30 at call sites), `Motion.kt:94-95` (press scale 0.94), `DetailActivity.kt:77` (`delayMs = 80`).

**What is wrong:** The doc comment on `Motion.kt:14-17` promises "components never hard-code timing", but the ambient aurora loop (24s), the search debounce (350ms), stagger steps (45 vs 30 — inconsistent between main grid and the other two screens), entrance travel distances (24dp vs 16dp), press scale (0.94) and the hero delay (80ms) are all raw literals.

**Why it matters:** M3 motion guidance = tokens for all durations/distances; inconsistent stagger steps make the three screens animate differently, and none of these values respond to a `DesignTokens` swap, contradicting the swappable-theme design (`Tokens.kt:181-189`).

**Fix:** Extend `MotionTokens` with `ambientMs`, `debounceMs`, `staggerStepMs`, `enterTravelDp`, `pressScale`, `heroDelayMs` (and put blob alpha into `ColorTokens` as `auroraAlpha`), then read them at every site listed above; use one stagger step everywhere.

### 5. MEDIUM — Raw off-grid spacing bypasses `SpaceTokens` and breaks the system's own "4dp-based" invariant
**Files:** `Components.kt:152,168` (`dp(2)` badge margins), `Components.kt:372` (chip padding `py = dp(3)`), `Components.kt:183,206` (button padding `dp(20)`/`dp(14)`), `Components.kt:251` (nav padding `dp(6)`), `Components.kt:511,515` (`dp(6)` poster overlay margins), `SettingsActivity.kt:58` (`marginTopDp = 2`), `SettingsActivity.kt:146` (`marginEnd = dp(6)`), `RequestsActivity.kt:126` (`marginTopDp = 2`), `RequestsActivity.kt:127` (`marginStart = dp(10)`), `DetailActivity.kt:144,174` (`dp(6)`), `MainScreen.kt:297,306,309` (raw `dp(8)/dp(4)/dp(16)`).

**What is wrong:** `DesignTokensTest.kt:44-51` asserts the space scale is 4dp-based and strictly increasing, and `Tokens.kt:38-41` promises a 4dp scale — yet ~15 call sites pass raw 2/3/6/10/14/20dp values straight to `dp()`, bypassing `t.space.*` entirely.

**Why it matters:** Atomic Design (values live only in tokens) + the design system's own tested invariant; off-grid values also don't scale if the spacing scale is ever retuned, and 2/3/6dp rhythms are inconsistent between screens (e.g. Settings error label at 2dp vs Requests subtitle at 2dp vs card gaps at 8dp).

**Fix:** Replace all raw values with `t.space.xs/sm/md/lg` (drop the 2dp/3dp/6dp cases up to the next grid step), add the few genuinely-needed paddings (button h-pad, chip v-pad) to `SpaceTokens`, and add a unit/lint check that flags `dp(<int literal>)` outside `Density.kt`.

### 6. MEDIUM — Token values duplicated as raw literals in atoms/screens, so a theme swap re-skins color/type/radius but not these
**Files:** `Components.kt:406` (`GlassCard` default `padDp = 16`), `Components.kt:600` (`MagnetRow` `padDp = 12`), `Components.kt:560` (`EmptyState` `dp(32)`), `Components.kt:495` (poster `minimumHeight = dp(150)`), `Components.kt:508` (poster scrim `dp(56)`), `DetailActivity.kt:119` (hero `dp(300)`), `DetailActivity.kt:135` (hero scrim `dp(140)`), `RequestsActivity.kt:111,117` (thumb 44×64dp), `Components.kt:144,185,208,270,325,341,624,637,651` (48dp touch target hardcoded 9×), `Components.kt:125,128` (12/8dp margins).

**What is wrong:** `Tokens.kt:8-12` states "components never hard-code values" and `AppTheme.use(...)` is meant to re-skin everything, but these layout constants are raw ints: changing `space.lg` from 16 does not change `GlassCard`'s default padding; M3's 48dp minimum touch target is scattered as `dp(48)` nine times instead of one token.

**Why it matters:** Atomic Design (atoms = pure token consumers) and M3 (48dp touch target is a system constant); duplicated literals drift silently and make the advertised runtime theme swap incomplete.

**Fix:** Add a `LayoutTokens`/`TouchTokens` block to `DesignTokens` (`minTargetDp = 48`, `cardPadDp`, `posterMinHeightDp`, `scrimHeightDp`, `heroHeightDp`, `heroScrimDp`, `thumbW/H`) and reference it everywhere; keep `dp()` calls only inside `Density.kt`.

### 7. MEDIUM — Radius inconsistencies: skeleton uses `radius.button` in poster slots; nav bar uses `radius.card`
**Files:** `Components.kt:575` (Skeleton `glass(t.radius.button)` = 16dp) vs `Components.kt:496,502` (posters `roundedClip(t.radius.poster)` = 20dp) in the same grid cells (`MainScreen.kt:227-233`); `Components.kt:244,248` (BottomNavBar root glass/outline at `t.radius.card` = 24dp — a card radius on a navigation bar atom).

**What is wrong:** While the grid shows skeletons, corners are 16dp; the moment posters load they become 20dp — a visible corner-radius jump on every load. The bottom nav is a bar, not a card, but borrows the card radius.

**Why it matters:** M3 shape system assigns one radius per component role and expects consistency during state changes; M3's nav bar has its own shape treatment, not a 24dp card. Atomic Design: the mismatch lives in the atom, so it should be fixed in the atom's token usage.

**Fix:** Skeleton → `glass(t.radius.poster)`; add `radius.navBar` (e.g. 28) to `RadiusTokens` and use it in `BottomNavBar` (both `glass()` and the outline provider); keep the `DesignTokensTest` radius ordering assertion extended to cover it.

### 8. MEDIUM — Raw ARGB literals and `themes.xml` hardcodes bypass `ColorTokens`, breaking theme swappability
**Files:** `Components.kt:509` and `DetailActivity.kt:142-143` (rating/year chips use hardcoded `fg = 0xFFFFFFFF.toInt()` instead of `t.colors.textOnAccent`); `Components.kt:426,428` (GradientPoster hardcodes `0xFF101828`, `0xFFFFFFFF`, `0xFF000000`); `Glass.kt:36,43` (fill/sheen hardcode `0xFFFFFFFF`/`0xFF000000`, only branched on theme luminance); `res/values/themes.xml:4` (`windowBackground #0A0C14`, `windowLightStatusBar false` duplicates the dark token).

**What is wrong:** These raw colors duplicate token values; under `PorcelainLight` the rating chips stay whatever the literal says, and the window background is hardcoded dark in XML — until `edgeToEdge()` (`Insets.kt:12-14`) patches it at runtime, so swapping to the light theme flashes a dark window first and uses wrong status-bar icons until then.

**Why it matters:** Atomic Design (no raw values outside tokens) + the `AppTheme.use()` swappability promise (`Tokens.kt:181-189`); WCAG-wise the literals happen to pass on `chipScrim`, but they are unverifiable by the token tests.

**Fix:** Add `textOnScrim`/`scrimInkLight`/`scrimInkDark` (or reuse `textOnAccent`) to `ColorTokens`, use them in `Components.kt`/`DetailActivity.kt`/`Glass.kt`; remove the hardcoded `windowBackground` from `themes.xml` (set it only in `edgeToEdge()` from the active token).

### 9. LOW — Tint/alpha strengths hardcoded instead of tokens
**Files:** `Components.kt:150,166` (badge bg `withAlpha(error, 0.30f)`), `Components.kt:378,400` (chip tint `0.22f`), `Components.kt:649` (banner tint `0.18f`), `Components.kt:433` (0.85), `Components.kt:450` (0.08), `Components.kt:458` (scrim 0.62), `Components.kt:187` (disabled alpha 0.45), `Components.kt:579` (reduce-motion alpha 0.6), `Glass.kt:30-37,43` (border factors 1.6/0.4/0.6, fill +26/+16, sheen 0.10).

**What is wrong / why it matters:** The glass recipe and every tinted surface use raw alpha constants; these are exactly the values that need retuning to fix finding #1, and they can't be swapped per theme. Atomic Design: they belong in `ColorTokens` (e.g. `tintWeak`, `tintStrong`, `scrimStrength`, `disabledAlpha`).

**Fix:** Add these as named tokens in `ColorTokens` and replace the literals; the contrast test in finding #1 then parameterizes over them.

### 10. LOW — `ElevationTokens.level2Dp/level3Dp/shadowColor` are dead tokens
**Files:** `Tokens.kt:76-79` (definition), only usage anywhere is `Components.kt:258` (`level1Dp` on the nav pill).

**What is wrong / why it matters:** Level 2/3 and `shadowColor` are defined but never used — the hero (`DetailActivity.kt:116`) and cards get no elevation, so the hierarchy relies purely on glass alpha. M3 uses elevation levels to separate surfaces; dead tokens also violate the "tokens are the single source of truth" atomic principle (either used or removed).

**Fix:** Either wire hero → `level2Dp`, dialogs/sheets → `level3Dp` with `shadowColor`, or delete the unused fields from `ElevationTokens`.

### 11. LOW — Press feedback duration semantics inverted vs M3
**File:** `Motion.kt:93-96` — press-**down** uses `instantMs * 2` (=160ms = `shortMs`), press-up uses `shortMs`; i.e. the "instant" press-in is slower than the release.

**What is wrong / why it matters:** M3 motion: press feedback should be the fastest, most immediate response (use the shortest duration on ACTION_DOWN). Currently the down-scale takes as long as a standard fade.

**Fix:** Use `instantMs` (80ms) for ACTION_DOWN and `shortMs` for release.

---

## Top 5 changes

1. **Make contrast tests honest about the glass backdrop** (WCAG 2.1 SC 1.4.3): add computed `effectiveSurface(Strong)` = glass composited over worst-case aurora to `Tokens.kt`, assert ≥4.5:1 (≥7:1 for 11sp labels) for all text/status colors against it in both themes in `DesignTokensTest`, then retune `surfaceGlass` alpha, blob alpha (52), `textSecondary`, `accentText` and light-theme `success` until everything passes (fixes findings 1, 9).
2. **Fix `GradientPoster` legibility** (SC 1.4.3): choose text color by computed ratio with a guaranteed ≥4.5:1 floor (no 0.183 dead zone) and extend the scrim (or a `chipScrim` pill) over the top type label (finding 2).
3. **Move every timing value into `MotionTokens`**: delete the hardcoded durations in `res/anim/*.xml`, the 24 000ms aurora loop, the 350ms debounce, stagger steps 45/30, travel dp(24)/dp(16), press scale/durations, and the 80ms delay — one token set drives all motion per M3 (findings 3, 4, 11).
4. **Kill raw dp literals outside `Density.kt`**: add `LayoutTokens`/`TouchTokens` (48dp target, card pad, poster/hero/scrim/thumb sizes) and route the off-grid 2/3/6/10/14/20dp values through `SpaceTokens`, restoring the tested 4dp grid (findings 5, 6).
5. **Replace raw color literals with tokens and align radii**: `textOnScrim`/ink tokens instead of `0xFFFFFFFF`/`0xFF000000` in chips, poster and glass; drop `#0A0C14` from `themes.xml`; skeleton uses `radius.poster`, nav bar gets its own `radius.navBar` (findings 7, 8).
