# Round 4 — Theming / M3 / WCAG / Atomic Design review (LENS: theming)

**Scope.** Reviewed the current `app/` module of app-a (v4.4 tree, identical to the checkout at
`/tmp/appsrc`, which matches `origin/master` @ `33ef6e26`): all Kotlin under
`app/src/main/java/com/example/appa/`, `app/src/main/res/`, and the token tests under
`app/src/test/`. No files were modified. Contrast numbers below were computed with the app's own
math (`luminance`/`contrastRatio`/`compositeOver` from `Tokens.kt`, replicated exactly) for **both**
`CrystalGlassDark` and `PorcelainLight`.

**Reference key used below:** WCAG 2.1 SC 1.4.3 (text 4.5:1; large text 3:1, where large = ≥24px or
≥18.66px bold — 11sp/14sp at baseline density are *not* large); SC 1.4.11 (non-text 3:1);
m3.material.io (color/contrast, shape, typography, motion, top app bar); Atomic Design
(tokens → atoms → organisms, state passed down as props).

---

## Ranked findings

### 1. HIGH — Status pill on poster cards fails WCAG 1.4.3 catastrophically (down to ~1.2:1)
**File:** `app/src/main/java/com/example/appa/Components.kt:515-517` (placement), `:404-406,409-413` (`statusPillBg`/`StatusPill`)
**What:** `PosterCard` places the `StatusPill` at `Gravity.TOP or START` directly over the raw poster
(image or gradient) with **no scrim**, and `statusPillBg` composites the 28% status tint over
`surfaceGlassStrong` + `background` — i.e. it assumes a glass backdrop that is not there.
**Why (computed):** pill text is 11sp bold (not large → needs 4.5:1). Over a bright poster:
dark theme success/warning/error/info = **1.59 / 1.44 / 1.60 / 1.23 : 1**; light theme over a dark
poster = **1.91–2.31 : 1**. Even over the worst-case aurora blob behind glass the dark theme gives
3.14–3.95:1 (< 4.5). `DesignTokensTest.kt:151` only asserts ≥3.0 there, so the tests never catch it.
**Fix:** give the pill a real backdrop — composite the tint over `chipScrim` (like the rating chip)
or move the pill into the bottom scrim zone; and change the test to assert ≥4.5 against the *actual*
composited backdrop (poster luminance extremes), not just glass.
**Ref:** WCAG 2.1 SC 1.4.3; m3.material.io color/contrast (text on any surface must meet AA).

### 2. HIGH — GradientPoster type label / title contrast fails 1.4.3 on most catalog gradients
**File:** `app/src/main/java/com/example/appa/Components.kt:436-460` (ink choice, scrim, label, title)
**What:** ink polarity is chosen from the **average** luminance of the two gradient endpoints
(threshold `0.183`, line 438), but the top label (line 457) is drawn at `withAlpha(ink, 0.85f)` over
**c1 only**, with no scrim (the scrim is bottom-only, lines 450-456, height `max(screenH/3, 120dp)`).
**Why (computed over the 12 placeholder pairs in `Catalog.kt:277-280`):** on a short screen where the
scrim doesn't reach the top, the label gets **2.14–4.21:1** on 8 of 12 pairs (e.g. `#0550ae→#f778ba`
= 2.14, `#116329→#aeaefe` = 2.20); even with scrim the 0.85-alpha label gives 3.11–4.30:1 on 6 of 12.
The centered title drops to 3.69–4.65:1 whenever the scrim doesn't fully cover it. 11sp bold ≠ large
text → 4.5:1 required.
**Fix:** pick ink from the local backdrop (`c1` for the top label), use full-alpha ink, extend a top
scrim (or drop the label), and add a unit test iterating all `Catalog` pairs asserting ≥4.5 vs the
real composited backdrop at the label's position.
**Ref:** WCAG 2.1 SC 1.4.3; `Tokens.kt`'s own `luminance`/`contrastRatio` helpers (use them in tests).

### 3. MEDIUM — Token tests codify 3:1 for normal-size text over aurora blobs
**File:** `app/src/test/java/com/example/appa/DesignTokensTest.kt:135,151`
**What:** the "worst aurora blob" assertions require only `>= 3.0` for primary/secondary/accentText
and status colors.
**Why:** 3:1 is the SC 1.4.3 threshold only for *large* text (≥24px / ≥18.66px bold); body (14sp) and
label (11sp) are not large, so the requirement is 4.5:1. Dark-theme status colors over the worst blob
measure 3.14–3.95:1 — the test passes while SC 1.4.3 fails. (3:1 is the right threshold for SC 1.4.11
non-text, e.g. glass borders/icons.)
**Fix:** assert ≥4.5 for all text at non-large sizes against the real composited surfaces; reserve 3:1
for large text and for non-text contrast (1.4.11).
**Ref:** WCAG 2.1 SC 1.4.3 vs SC 1.4.11.

### 4. MEDIUM — Raw colors outside `Tokens.kt`, violating its "ONLY place raw values live" contract
**File:** `Components.kt:438-439` (ink `0xFF101828`/`0xFFFFFFFF`, scrim black/white), `:510` and
`DetailActivity.kt:144-145` (`fg = 0xFFFFFFFF` on `chipScrim`), `Glass.kt:23-24,29,31,34-39` (border
alpha multipliers 1.6/0.4/0.6, fill +16/+26, 1dp inset, sheen 0.10, white/black polarity),
`Components.kt:404-406` (0.28 pill tint), `:184` badge tint 0.30, `ModeBanner` 0.18 tint
(`Components.kt:648-652`), `MainScreen.kt:236` (0.18 warning tint).
**Why:** `Tokens.kt:8` declares tokens as the single source of design values; these raw ARGB/alpha
literals are scattered in atoms/screens, so a theme swap (`AppTheme.use(PorcelainLight)`) re-skins
glass polarity but leaves poster ink, chip foreground and tint strengths hardcoded — drift and
inconsistent theming.
**Fix:** add `ColorTokens` entries (`onChipScrim`, `posterInkDark/Light`, `scrimInkDark/Light`,
`pillTint`, `badgeTint`, `bannerTint`) and alpha/elevation entries for the glass recipe; reference
only tokens from components.
**Ref:** Atomic Design (tokens are the single source; atoms never hard-code values) — stated in
`Tokens.kt` header itself; m3.material.io color roles.

### 5. MEDIUM — `themes.xml` hardcodes the dark background hex and the light theme is dead code (no night wiring)
**File:** `app/src/main/res/values/themes.xml:4,7`; `Tokens.kt:128-162` (`PorcelainLight`, `AppTheme`)
**What:** `android:windowBackground` is the raw literal `#0A0C14`, duplicating
`CrystalGlassDark.background` (`Tokens.kt:98`) outside the token file; the style is dark-only
(`windowLightStatusBar=false`, no `values-night`), and `AppTheme.use(...)` is never called in app
code — only in `DesignTokensTest.kt:113-127`. Nothing maps `Configuration.UI_MODE_NIGHT` to
`PorcelainLight`.
**Why:** M3 themes ship light + dark schemes (ideally dynamic color); here the entire light theme is
unreachable, the XML hex will drift from the token, and system dark-mode is ignored.
**Fix:** remove the hex from XML (the window background is already set programmatically from tokens in
`Insets.kt:20`); wire `AppTheme.use(if (uiMode and UI_MODE_NIGHT_MASK) != 0 … )` — or add
`values-night` — so both shipped themes are actually used.
**Ref:** m3.material.io/develop/android theming (light/dark color schemes); `Tokens.kt` `AppTheme`
contract ("swap at runtime to re-skin the app").

### 6. MEDIUM — Motion bypasses `MotionTokens`: XML anim durations, 24s aurora loop, hardcoded travel/stagger values
**File:** `app/src/main/res/anim/slide_up_in.xml` (320/240ms), `slide_down_out.xml` (280/220ms),
`fade_in_fast.xml` / `fade_out_fast.xml` (180ms) used at `Motion.kt:119,131`;
`AuroraBackground.kt:54` (`duration = 24_000`), `:83-85` (blob paths/radii), `:96` (`alpha = 52`);
`Motion.kt:44,90` (`dp(24)`/`dp(16)` travel), `:85` (`stepMs = 45`), `DetailActivity.kt:78,296` and
`RequestsActivity.kt:154` (raw `delayMs = 80`, `stepMs = 30`); `Components.kt:363` (350ms debounce).
**Why:** `MotionTokens` (Tokens.kt:123: 80/160/300/500) and Motion.kt's contract ("components never
hard-code timing") are bypassed: activity transitions use 180–320ms from XML with framework
accelerate/decelerate cubic interpolators instead of the M3 curves defined in `Motion.kt:34-35`
(fast-out-slow-in 0.2,0,0,1); the ambient loop and press/travel distances have no token at all, so
re-skinning motion or honoring reduced motion consistently is impossible (only animator-driven
`View.animate()` paths check `reduceMotion()`).
**Fix:** add `MotionTokens` slots (`ambientMs`, `staggerStepMs`, `travelDp`, `activityEnterMs/ExitMs`,
`debounceMs`) and drive `overridePendingTransition` from tokens with the M3 interpolators (or
animate the decor view); keep the `reduceMotion()` early-outs for every path.
**Ref:** m3.material.io/styles/motion (duration/easing tokens, reduced-motion); `Motion.kt:22-25`
contract.

### 7. MEDIUM — Spacing/radius literals off the 4dp grid bypass `SpaceTokens`/`RadiusTokens`
**File:** `Components.kt:379` (chip `dp(3)` vertical), `:258,277` (nav `dp(6)`, pill `dp(48)`),
`:324` (search `dp(14)/dp(10)`), `:190,213` (button `dp(20)/dp(14)`), `:450-459` (poster scrim
`heightPixels/3 coerceAtLeast dp(120)`, padding `dp(10)`), `:512,516` (`dp(6)`), `:413/:601`
(`GlassCard padDp = 16`, `MagnetRow padDp = 12` raw defaults); `DetailActivity.kt:121,137,146,176,211`
(`dp(300)`, `dp(140)`, `dp(6)`, `dp(8)`); `RequestsActivity.kt:110,116,126,130,145` (`dp(44)/dp(64)`,
`dp(10)`, `dp(8)`); `SettingsActivity.kt:58,155` (`marginTopDp = 2`, `dp(6)`, `dp(4)`).
**Why:** `SpaceTokens` promises a "4dp-based, strictly increasing" scale (enforced in
`DesignTokensTest.kt:47-52`), yet components hardcode 2/3/6/10/14/20/44/64/300dp — the token scale
is not actually the source of layout rhythm, and M3's 4dp baseline grid is broken at the atom level.
**Fix:** extend `SpaceTokens`/`RadiusTokens` with semantic slots (`touchTarget`, `buttonPadH/V`,
`chipPadV`, `thumbW/H`, `heroH`, `scrimH`, `debounce`…) and route every `dp(...)` literal through
them.
**Ref:** m3.material.io/layout (4dp baseline grid); Atomic Design (spacing tokens in atoms).

### 8. MEDIUM — Atomic-design layering broken: organisms read the `AppState` singleton directly
**File:** `MainScreen.kt:68-69,75-135,247-262,340-356`, `DetailActivity.kt:41-47,158,238-263,289-313`,
`RequestsActivity.kt:44,59,100-152`, `SettingsActivity.kt:44,64-127,139-171` (43 `AppState.` call sites
in screens; atoms in `Components.kt`/`Glass.kt` correctly take `DesignTokens` params and do *not*
touch `AppState`).
**Why:** the design system's contract is that components receive everything via parameters
(`t: DesignTokens = AppTheme.current`); state is the exception — screens reach into the global
`AppState.liveMode/store/repo/config` directly, so screens can't be re-skinned/re-hosted with a
different state (or unit-tested) without the singleton, and config changes must be propagated via the
`onConfigChanged` callback instead of props.
**Fix:** pass a `ScreenState(liveMode, store, repo, config)` snapshot into `showMainScreen` and the
activities (or a thin presenter); keep `AppTheme.current` for tokens only.
**Ref:** Atomic Design — organisms compose atoms and receive state/props from templates/pages, not
globals (Brad Frost, atomic design methodology).

### 9. LOW — Radius scale not mapped to M3 shape roles; `button = 16` isn't an M3 button shape
**File:** `Tokens.kt:122` (`RadiusTokens(poster=20, card=24, button=16, sheet=32, pill=999, thumb=12,
navBar=28)`), `Components.kt:186-196,213-219` (buttons at `radius.button`)
**What/Why:** M3 buttons are fully rounded (`shape.corner.full` = pill); a 16dp corner on a 48dp-high
button is not an M3 button shape, and the other values (20/24/28/32) don't map to the M3 shape scale
(extra-small 4 / small 8 / medium 12 / large 16 / extra-large 24/28). Internally consistent (test at
`DesignTokensTest.kt:61-67` enforces ordering) but not M3-conformant.
**Fix:** either set `button = pill` (M3 default for filled buttons) or document the deviation; name
radius tokens after M3 shape-scale steps.
**Ref:** m3.material.io/styles/shape (shape scale; buttons default to full rounding).

### 10. LOW — Type scale doesn't map to M3 type roles; top bar title uses 22sp where M3 large top app bar uses 28sp; display/headline forced bold
**File:** `Tokens.kt:120` (`display=28, headline=22, title=17, body=14, label=11`),
`Components.kt:70-80` (`AppText`: DISPLAY/HEADLINE forced `Typeface.BOLD`), `:122-124` (`AppTopBar`
title = `TextStyle.HEADLINE`).
**What/Why:** M3's baseline scale is display 57/52/45 … label 14/12/11 with display/headline at
regular–medium weight; the app's "display" (28) equals M3 *headlineMedium*, and the large top app bar
should use 28sp expanded / 22sp collapsed — here the always-expanded title is 22sp and bold display
text deviates from M3 typography weights.
**Fix:** rename `TypeTokens` to M3 role names and pick weights per role (M3: display/headline
regular/medium); use `displaySp` (28) for the `AppTopBar` title to match M3 large top app bar.
**Ref:** m3.material.io/styles/typography/type-scale; m3.material.io/components/top-app-bar.

---

## Top 5 changes
1. **Fix status-pill contrast on posters** (`Components.kt:515`): composite the pill over `chipScrim`
   (or place it in the bottom scrim) and raise the pill contrast test to ≥4.5 vs the real backdrop —
   currently as low as **1.2:1** (SC 1.4.3).
2. **Fix GradientPoster label/title legibility** (`Components.kt:436-460`): choose ink from the local
   gradient color, drop the 0.85 alpha, cover the top label with scrim; add a Catalog-wide ≥4.5
   contrast test — currently **2.1–4.3:1** on most placeholder gradients.
3. **Raise the token tests to SC 1.4.3**: assert ≥4.5 for all non-large text on real composited
   surfaces (`DesignTokensTest.kt:135,151`); keep 3:1 only for large text / SC 1.4.11 non-text.
4. **Make tokens the only source of raw values**: move the hardcoded colors in `Components.kt` /
   `Glass.kt` / `DetailActivity.kt` and the hex in `themes.xml:4` into `Tokens.kt`; wire
   `values-night`/`AppTheme.use(PorcelainLight)` so the light theme is actually reachable.
5. **Route all motion through `MotionTokens`**: replace the hardcoded `res/anim` durations (180–320ms)
   and the 24s aurora loop / stagger / travel constants with token slots and M3 easing, honoring
   `reduceMotion()` on every path.
