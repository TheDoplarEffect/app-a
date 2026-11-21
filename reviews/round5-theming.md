# Round 5 — Theming & UI-consistency review

- **Repo:** https://github.com/TheDoplarEffect/app-a, `master` HEAD = `cd6abab` ("v4.5: round-4 review reports + panel consensus"), cloned to `/tmp/r5-theming/repo`
- **Scope:** `app/src/main` (~22 Kotlin/Java files + `res/values/themes.xml`), `app/src/test` (DesignTokensTest.kt). Fresh review; prior review rounds not consulted.
- **Lens:** UI consistency & theming — token usage, WCAG 2.1 AA contrast (4.5:1 text / 3:1 large), dark/light parity, component reuse. Principles: single source of truth in `Tokens.kt`; no hardcoded colors outside `Tokens.kt`/`Components.kt`.
- **Method:** full read of all UI files + grep sweeps for hex literals / `Color.*` / raw `setTextColor` / `dp()` literals, and an independent numeric re-computation of every WCAG contrast claim using the same relative-luminance math as `Tokens.kt` (`luminance`/`contrastRatio`/`compositeOver`), including worst-case aurora-blob composites and all 12 catalog gradient pairs.

## What is genuinely good (keep it)

- `Tokens.kt` is a real single source of truth: every text/surface color in screens and components routes through `AppTheme.current`; the color math is pure and unit-tested (14 invariant tests in `DesignTokensTest.kt`, incl. worst-case "brightest aurora blob" composites).
- Verified passing: white on accent gradient endpoints 6.29:1 / 5.38:1 (both themes); textPrimary on bg 17.9:1 (dark) / 16.4:1 (light); textSecondary 12.4:1 / 7.1:1; accentText 9.8:1 / 5.8:1; status colors on their composited pill ≥ 5.7:1 (dark) / ≥ 4.7:1 (light) and ≥ 3.0:1 over the worst blob; white on `chipScrim` ≥ 8.6:1 even over a white poster. `ImageLoader` placeholder, glass surfaces, and status-bar icon appearance (`Insets.kt:24-27`) are all token-derived.

## Findings (ranked by severity)

### HIGH

**H1. `GradientPoster` text fails WCAG AA on most placeholder posters — `Components.kt:434-470`**
- The ink polarity switch at `Components.kt:438` uses the **average** of the two gradient-endpoint luminances (`avg > 0.183`) to pick white vs dark ink, but the title (`Components.kt:461-468`, centered) sits on a *diagonal gradient* whose local luminance spans both endpoints, and the scrim (`Components.kt:451-456`) only covers the **bottom third** — the centered title gets no scrim. The top type label (`Components.kt:457-460`) has no scrim at all and is drawn at 0.85 alpha.
- Measured against all 12 catalog gradient pairs (`Catalog.kt`): **9/12 pairs fail 4.5:1 for the centered title at the worst gradient endpoint** (e.g. `#0550ae→#f778ba`: avg 0.23 → dark ink, but over the bright endpoint contrast is **2.34:1**; `#116329→#aeaefe`: 2.42:1; `#0a3069→#2188ff` with white ink: 3.50:1). **All 12 pairs fail for the top label** (2.1–4.2:1) because of the 0.85 alpha + no scrim.
- There is also a dead zone in the threshold itself: white ink needs bg luminance ≤ 0.1833, dark ink (`0xFF101828`, lum 0.0106) needs ≥ 0.2223 — so for avg ∈ (0.183, 0.222) the chosen dark ink yields only ~4.1–4.3:1.
- **Why it matters:** placeholder posters are the primary visual in the main grid and request rows; sub-4.5:1 title text is a direct WCAG 2.1 AA violation.
- **Fix:** choose ink by the *worst* endpoint, not the average — white ink only if `max(l1,l2) ≤ 0.1833`, dark ink only if `min(l1,l2) ≥ 0.2223`; otherwise (mixed-luminance gradients, which is most of the catalog) paint an opaque-enough scrim behind the text zone (or move the title into the existing bottom scrim and extend it full-height like `PosterCard`'s overlay), and draw the top label at full alpha over a top scrim. Add a unit test iterating the catalog pairs asserting `contrastRatio(ink, worstEndpoint) ≥ 4.5` and `contrastRatio(labelColor, worstEndpoint) ≥ 4.5`.

**H2. Tinted-chip alphas are inconsistent, hardcoded, and only partially tested — `Components.kt:155,173` (0.30), `Components.kt:404-407` (0.28), `Components.kt:385` (0.22), `Components.kt:652` + `MainScreen.kt:235` (0.18)**
- Four different magic tint alphas for the same "status tint on translucent chip" pattern, none tokenized. `DesignTokensTest.kt:141-153` only validates the 0.28 pill path; the badge path (0.30) is untested and **fails AA in the light theme**: `error` (`0xFF9F1239`) on `withAlpha(error, 0.30)` over light glass = **4.49:1 < 4.5** (the 0.28 pill passes at 4.69 only because the tint is thinner). Dark-theme badge over the brightest blob sits at 3.02:1 — barely above the 3:1 large-text floor.
- **Why it matters:** same visual pattern, four hardcoded alphas = no single source of truth; an untested path is already non-compliant.
- **Fix:** introduce semantic alpha tokens (e.g. `ColorTokens.chipTintAlpha`, `badgeTintAlpha`) or one shared `tintAlpha`; extend `DesignTokensTest` to assert ≥ 4.5 for **every** alpha actually used (0.30, 0.28, 0.22, 0.18) in both themes; bump light `error` or the badge alpha so the badge path passes.

### MEDIUM

**M1. Hardcoded colors outside `Tokens.kt`/`Components.kt` — `DetailActivity.kt:144-145`, `Glass.kt:29,34`, `res/values/themes.xml:4,7`**
- `DetailActivity.kt:144-145`: rating/year chips use literal `fg = 0xFFFFFFFF.toInt()` instead of a token (the identical chip in `Components.kt:510` does the same, but at least lives in the component layer). White happens to pass on `chipScrim` (≥ 8.6:1), so this is a *governance* violation more than a contrast one.
- `Glass.kt:29,34`: the glass fill-top and sheen colors are literal `0xFFFFFFFF`/`0xFF000000` chosen by background luminance — theming decisions living outside the token file; a third theme could not re-skin them.
- `themes.xml:4`: `android:windowBackground` hardcodes `#0A0C14`, duplicating `CrystalGlassDark.background`; `themes.xml:7` hardcodes `windowLightStatusBar=false`. (`Insets.kt:20` re-sets the window background from tokens at runtime, so the XML value is only a boot flash / dead duplicate.)
- **Fix:** add tokens `textOnScrim` (for chips on `chipScrim`) and `glassSheen`/`glassFillHi` polarity colors to `ColorTokens`; replace the XML windowBackground with a neutral transparent and keep the runtime token-driven value.

**M2. Light theme is unreachable — dark/light parity exists only in tests**
- `AppTheme.use(PorcelainLight)` is **never called** anywhere in app code (only `DesignTokensTest.kt`); there is no settings toggle, and `themes.xml` defines only the dark window. So `PorcelainLight` is dead at runtime and every "both themes" test exercises a path users can't reach; conversely the shipped experience is dark-only while claiming swappability.
- **Fix:** either wire a real theme picker (Settings `SwitchRow` → `AppTheme.use(...)` + `recreate()`, persist choice) so parity is real, or explicitly scope the app dark-only, drop `PorcelainLight` to a test fixture, and say so in `Tokens.kt`.

**M3. Framework widgets are not token-driven — `Components.kt:634-644` (`SwitchRow`), `Components.kt:312-345` (`SearchField`), `Components.kt:612-632` (`FormField`), dialogs in `DetailActivity.kt` / `RequestsActivity.kt`**
- `SwitchRow` sets only the label color; thumb/track keep framework `Theme.Material` defaults (not `accent*`), so a token swap would not re-skin it and the default switch colors clash with the crystal-glass system. `EditText` cursor/handle colors are never tinted (default theme color). `AlertDialog` button text uses framework colors. This breaks the `Tokens.kt` header promise: "swapping `AppTheme.use` re-skins every component."
- **Fix:** set `thumbTintList`/`trackTintList` from `accentSolid`/`glassBorder` tokens in `SwitchRow`; set `textCursorDrawable` tinted with `accentText` in `FormField`/`SearchField`; supply a themed dialog button color or accept framework dialogs explicitly and document the exception.

**M4. Dead tokens — `Tokens.kt:110,124,143` (`accentSolid`), `Tokens.kt:78-79` (`level2Dp`, `level3Dp`, `shadowColor`)**
- `accentSolid` is referenced by no component (the primary button uses the `accentStart→accentEnd` gradient instead); `elevation.level2Dp/level3Dp/shadowColor` are unused (only `level1Dp` at `Components.kt:270`). Meanwhile `DesignTokensTest.kt:32-38` asserts contrast on `accentSolid` — the test validates a token nothing renders.
- **Fix:** either use `accentSolid` where a solid accent fill is semantically right (e.g. nav pill, focus ring) or delete it and test the gradient endpoints only; trim `ElevationTokens` to what's used or actually apply `shadowColor`/levels via `View` elevation + `outlineAmbientShadowColor`.

### LOW

**L1. Layout dimensions bypass the token system in screens** — `DetailActivity.kt:121` (`dp(300)` hero), `:137` (`dp(140)` scrim), `RequestsActivity.kt:110,116` (`dp(44)/dp(64)` thumbs), `MainScreen.kt:199,371` (skeleton 150dp, `GRID_COLUMNS = 3`), plus many `dp()` literals inside `Components.kt` (48dp targets, 56dp poster scrim, paddings). Space/radius tokens exist but there is no size token group.
- **Fix:** add a `SizeTokens` group (touchTarget, thumbW/H, heroH, scrimH, gridColumns) or document that component-internal metrics live in `Components.kt` while screens must use tokens.

**L2. Aurora blob intensity hardcoded** — `AuroraBackground.kt:96` `alpha = 52` is a magic number outside tokens; a theme swap can't tune backdrop intensity (it directly affects the worst-case contrast the tests model).
- **Fix:** move to `ColorTokens.auroraIntensity` (0..255) and reference it in `drawBlob`.

**L3. Disabled primary button uses alpha 0.45 — `Components.kt:194`** — WCAG exempts inactive controls, but the disabled button carries status text ("✓ Available in library", `DetailActivity.kt:201-203`); consider a solid disabled style (e.g. `surfaceGlassStrong` fill + `textSecondary` label) instead of fading below contrast.

**L4. Naming nit** — `Insets.kt:24` `lightIcons = luminance(bg) >= 0.5` actually selects the `APPEARANCE_LIGHT_*` flag (i.e. *dark* icons for a light background); behavior is correct, the name is inverted. `themes.xml:7` duplicates the same decision in XML. Rename to `lightBackground`/`useDarkIcons`.

## Suggested priority order

1. H1 (poster text contrast) — real AA violation on primary UI.
2. H2 (tint-alpha tokens + test coverage) — small change, closes a failing path.
3. M1 (move remaining literals into tokens) — cheap governance win.
4. M2 (decide: ship light theme via a setting, or declare dark-only).
5. M3 (token-drive Switch/EditText/dialogs), M4 (dead tokens), then L1–L4.
