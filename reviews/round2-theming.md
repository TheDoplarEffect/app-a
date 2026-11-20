# Round 2 — Theming / Design-System Review (lens: theming)

Scope: `app/src/main/**` only. References: Material Design 3 (m3.material.io), WCAG 2.1 SC 1.4.3 contrast minimum 4.5:1 (w3.org/TR/WCAG21/#contrast-minimum), Atomic Design (atomicdesign.bradfrost.com). All contrast ratios below were computed with the same WCAG relative-luminance math that `Tokens.kt` itself ships (`luminance`/`contrastRatio`), including sRGB alpha compositing over each theme's `background`/`surfaceGlass`, so they reflect what is actually rendered in **both** `CrystalGlassDark` and `PorcelainLight`.

Severity ranking, most important first.

---

## 1. HIGH — GradientPoster title text fails WCAG 4.5:1 on 9 of 12 catalog gradients
**File:** `Components.kt:417-422` (pick), `:447-458` (draw; `labelPaint` also uses `withAlpha(textColor, 0.85f)`, lowering contrast further).
**What is wrong:** the text color is chosen by `if (avg luminance > 0.45) dark else white`. That threshold is not derived from WCAG: white text needs background luminance ≤ **0.183** for 4.5:1, so every mid-luminance gradient gets white text at 2.0–3.5:1. Measured worst-endpoint contrast per catalog pair (`Catalog.kt:190-193`): `#d29922/#fb6f92` 2.52, `#9a6700/#d29922` 2.52, `#116329/#aeaefe` 2.04, `#0550ae/#f778ba` 2.51, `#0969da/#57ab5a` 2.85, `#1a7f37/#2da44e` 3.22, `#6e40c9/#d670d6` 2.94, `#953800/#e16f24` 3.23, `#0a3069/#2188ff` 3.48 — all below 4.5:1. Note that even choosing white-vs-dark per gradient by *actual* contrast still fails at the far gradient endpoint, because text is drawn across the whole gradient.
**Why it matters:** WCAG 2.1 SC 1.4.3 requires ≥4.5:1 for text; poster titles are the primary content of the home grid, and ~75% of placeholder posters render non-compliant text.
**Fix:** stop picking a text color — put a scrim behind the text, exactly like image posters already do (`PosterCard` adds `posterScrim` + white chip, `Components.kt:494`). In `onDraw`, fill the text band with a `scrimTop→scrimBottom` gradient (tokens already exist) and always use `t.colors.textOnAccent`. Alternative: keep the threshold approach but compute it with the existing helper — `if (contrastRatio(textOnAccent, avgColor) >= 4.5) white else dark` — and additionally restrict the title to the scrimmed bottom area so the worst endpoint doesn't sit under the text. Add a JVM test iterating `Catalog` color pairs asserting ≥4.5:1.

## 2. HIGH — Primary button label fails 4.5:1 across the *entire* accent gradient in both themes
**File:** `Components.kt:182` (`background = ctx.accentGradient(...)`), `Glass.kt:56-64` (`accentGradient` uses `accentStart`/`accentEnd`), tokens at `Tokens.kt:113-114` (dark) and `:152-153` (light).
**What is wrong:** the label is `textOnAccent` (white) on the `accentStart→accentEnd` gradient. Measured: dark theme 2.98 / 2.84 / 2.64 (start/mid/end) — below 4.5:1 *everywhere*; light theme 4.47 / 4.37 / 4.23 — also below 4.5:1 everywhere. `DesignTokensTest` ("text on accent meets WCAG AA") only checks `accentSolid`, never the gradient endpoints actually used by `PrimaryButton`/`BottomNavBar` pill.
**Why it matters:** the primary CTA ("＋ Request", "Retry", "Save") is the highest-traffic text in the app and fails WCAG AA in both themes; the token comment ("dark enough for white text, WCAG AA 5:1") is only true for `accentSolid`, not for the gradient.
**Fix:** darken the gradient endpoints so white passes at both ends — e.g. dark theme `accentStart/End = 0xFF4F46E5 → 0xFF9333EA` (6.29 / 5.38), light `0xFF4338CA → 0xFF7C3AED` (7.90 / 5.70) — or make `accentGradient` use `accentSolid` as one endpoint. Extend `DesignTokensTest` with `contrastRatio(textOnAccent, accentStart) >= 4.5 && contrastRatio(textOnAccent, accentEnd) >= 4.5` for both themes.

## 3. HIGH — `accentSolid` used as *text* on glass fails AA in the dark theme (3.28:1)
**File:** `Components.kt:194` (`SecondaryButton` label), `Components.kt:534` (`SectionHeader` action label).
**What is wrong:** `accentSolid = 0xFF6D5AE0` has luminance 0.159; over dark glass (composited `surfaceGlass` over background, lum 0.014) the ratio is **3.28:1** (< 4.5). On the dark background alone it is 3.89:1. Light theme is fine (5.99 on glass). The token was designed as a *fill* (white text on it = 5.01:1) but is reused as a text color.
**Why it matters:** WCAG 2.1 SC 1.4.3; secondary actions ("Test connection", "Copy magnet", "See all") are unreadable-ish for low-vision users in the default dark theme. M3 similarly expects text color roles (`primary`/`onSurfaceVariant`) to be contrast-checked per role, not reused from fills.
**Fix:** add a per-theme `accentText` color to `ColorTokens` and use it wherever accent is *text on a surface*: dark theme needs luminance ≥ 0.237 (e.g. `0xFFA5B4FC` = 8.25:1 on dark glass), light can keep `accentSolid` (5.99). Keep `accentSolid` for fills only.

## 4. MEDIUM/HIGH — Status pills, test chips, badge and mode banner fail AA on their own tinted backgrounds in PorcelainLight (and error in dark)
**File:** `Components.kt:397` (`StatusPill` = `withAlpha(color, 0.22f)` bg + same-color text), `:372` (`restyleChip`, same pattern), `:150`/`:166` (badge: `error` text on `withAlpha(error, 0.30f)`), `:634` (`ModeBanner`, `withAlpha(color, 0.18f)`).
**What is wrong:** the test suite asserts status colors pass 4.5:1 against the flat `background` (`DesignTokensTest` "status colors … on the theme background"), but the components render them against a **tinted** chip over glass. Measured (text vs its own tint composited over glass): PorcelainLight success 3.90, warning 3.62, error 4.17, info 4.18, banner 4.13/3.84, badge 3.58 — all FAIL; CrystalGlassDark error-on-tint 4.27 and badge 3.64 FAIL.
**Why it matters:** request status is core information (pending/processing/available); in the light theme every status pill is sub-AA. The invariant test gives false confidence because it tests the wrong background.
**Fix:** (a) darken the light-theme status tokens — verified candidates that pass on the 22% tint over light glass: success `0xFF036B4C` (4.54), warning `0xFF92400E` (4.89), error `0xFF9F1239` (5.24), info `0xFF075985` (5.16); dark-theme error could go to `0xFFFCA5B5`-class for tinted uses; (b) for the badge, use `textOnAccent` on a *solid* `error` fill instead of error-on-error-tint; (c) extend `DesignTokensTest` to assert `contrastRatio(c, composite(withAlpha(c,0.22), overGlass)) >= 4.5` for every status color in both themes, so the guarantee matches the rendering.

## 5. MEDIUM — `glassLayered` hardcodes border/sheen colors, bypassing the `glassBorder` token; the token-based glass is dead code
**File:** `Glass.kt:32-46` (hardcoded `0x40FFFFFF/0x10FFFFFF` dark, `0x30000000/0x0D000000` light, sheen `0x1AFFFFFF`), plus `Glass.kt:38` where the ternary is dead: `if (darkBg) 0xFFFFFFFF else 0xFFFFFFFF`.
**What is wrong:** `Tokens.kt` declares `glassBorder` as *the* hairline token and `glassDrawable` (Glass.kt:13-25) uses it — but `View.glass()` (Glass.kt:52) actually calls `glassLayered`, which ignores the token and hardcodes four raw color literals. So the documented design-system rule ("tokens are the ONLY place raw design values live", Tokens.kt:5-11, Atomic Design: tokens are the single source of truth) is broken in the most-used surface in the app; the dead ternary at line 38 shows the light-theme branch was never finished. On PorcelainLight the white sheen is also a no-op.
**Why it matters:** re-skinning via `AppTheme.use(...)` cannot change the glass border; the two glass implementations can drift.
**Fix:** derive the border gradient and sheen from tokens — e.g. `borderHi = withAlpha(c.glassBorder, 1.4f)`, `borderLo = withAlpha(c.glassBorder, 0.5f)`, sheen alpha as a token — or add explicit `borderHi/borderLo/sheen` fields to `ColorTokens`; delete `glassDrawable` (or make it the only implementation) and fix line 38 to a single expression.

## 6. MEDIUM — `themes.xml` hardcodes the dark background and status-bar appearance outside the token system
**File:** `app/src/main/res/values/themes.xml:4` (`android:windowBackground = #0A0C14`), `:7` (`windowLightStatusBar = false`).
**What is wrong:** `#0A0C14` duplicates `CrystalGlassDark.colors.background` in a place `Tokens.kt` explicitly forbids, and `windowLightStatusBar=false` is hardcoded even though `Insets.kt:24-29` derives the appearance dynamically from the active theme. If `AppTheme.use(PorcelainLight)` is ever called, the window background/splash stays dark.
**Why it matters:** breaks the "swap tokens, re-skin everything" promise (Atomic Design) and will visibly clash with the light theme at window level.
**Fix:** in `Activity.edgeToEdge()` (Insets.kt:20) add `window.setBackgroundDrawable(ColorDrawable(AppTheme.current.colors.background))`; remove the raw hex from `themes.xml` (keep a comment that the initial frame is dark-only) and drop the hardcoded `windowLightStatusBar` in favor of the computed `setSystemBarsAppearance`.

## 7. MEDIUM — Activity-transition animations hardcode durations/interpolators outside `MotionTokens` and `Motion.kt`
**File:** `res/anim/slide_up_in.xml` (320/240 ms, `@android:interpolator/decelerate_cubic`), `slide_down_out.xml` (280/220, accelerate_cubic), `fade_in_fast.xml` / `fade_out_fast.xml` (180 ms); used by `Motion.kt:108-118` (`openRising`/`finishDropping`).
**What is wrong:** `Motion.kt:10-11` states "components never hard-code timing", but the XML anims use 180–320 ms values that match neither `MotionTokens` (80/160/300/500) nor the M3 curves defined in `Motion.kt` (`FastOutSlowIn` = path (0.2,0,0,1); the framework `decelerate_cubic` is a different curve).
**Why it matters:** inconsistent motion language between in-screen animations (token-driven) and screen transitions (XML), and the durations can't be re-skinned with the theme — violates the atomic-design "motion tokens" layer and M3's single motion scheme.
**Fix:** build the enter/exit `Animation` objects in code inside `openRising`/`finishDropping` using `AppTheme.current.motion.mediumMs` + `FastOutSlowIn` (exit: `shortMs` + `AccelerateInterpolator`), pass them to `overridePendingTransition(enterAnim, exitAnim)`, and delete the four XML files (or, at minimum, move their durations into `MotionTokens`).

## 8. MEDIUM — Atomicity broken: `BottomNavBar.select` and `MainScreen.renderCurrent` read `AppTheme.current` at call time instead of the `t` parameter they were built with
**File:** `Components.kt:224` (constructor takes `t`) vs `:271` (`AppTheme.current.motion`) and `:278` (`AppTheme.current.colors`); `MainScreen.kt:62` captures `val t = AppTheme.current` in `attach()` but `:256` re-reads `AppTheme.current` in `renderCurrent`.
**What is wrong:** components are supposed to take their tokens as params (Components.kt:28-30 doc). The nav bar bakes its glass background from the constructor `t` but re-colors labels from a *fresh* `AppTheme.current` on every `select()` — a runtime theme swap produces a half-skinned nav bar (new text colors on old glass). Same mixed sourcing in `MainScreen`.
**Why it matters:** breaks the "swap `AppTheme.use(...)` and every component re-skins" contract (Tokens.kt:170-176) and the atomicity rule that atoms are pure functions of their params.
**Fix:** store `private val tokens = t` in `BottomNavBar` and use it in `select()`; in `MainScreen` use the captured `t` consistently (or deliberately re-read everywhere and document that a theme swap requires `recreate()`). Pick one policy and apply it app-wide.

## 9. MEDIUM — Framework widgets (Switch, AlertDialog, ripple) bypass the token system and stay dark-framework under PorcelainLight
**File:** `Components.kt:618-627` (`SwitchRow` — no token tinting of track/thumb), `Components.kt:94-100` (`pressable()` uses framework `selectableItemBackground`), `DetailActivity.kt:176` / `RequestsActivity.kt:130` (`AlertDialog.Builder`), theme parent `android:Theme.Material.NoActionBar` (dark framework theme, themes.xml:3).
**What is wrong:** the app's own atoms are fully token-driven, but these framework widgets render with the platform *dark* Material colors regardless of `AppTheme.current`. Under PorcelainLight the live-mode switch, dialogs and press ripples remain dark-styled on light glass.
**Why it matters:** M3 expects components to follow the theme's color roles; mixed framework/app styling is the most visible atomic-design break in the light theme, and ripple contrast on light glass is poor.
**Fix:** build the `Switch` thumb/track drawables from tokens (`GradientDrawable` with `accentSolid` track when checked, `glassBorder` when not) in `SwitchRow`; wrap dialogs in a token-colored context theme or at least set `colorAccent`; replace `selectableItemBackground` with a token-tinted ripple (`RippleDrawable` with `withAlpha(accentSolid, 0.12f)` mask).

## 10. MEDIUM — Off-scale hardcoded dp values bypass `SpaceTokens`, and sibling button atoms are inconsistent
**File:** `Components.kt:152,168` (badge margins `dp(2)`), `:497,501` (`dp(6)`), `:366-367` (chip padding `dp(8)/dp(3)`), `SettingsActivity.kt:57` (`marginTopDp = 2`), `RequestsActivity.kt:124` (`dp(10)`), button paddings `:183` (20/14), `:197` (16/14), `:209` (14/14), and `:206` where `DangerButton` uses `TextStyle.BODY` while `PrimaryButton`/`SecondaryButton` use `TITLE`.
**What is wrong:** `SpaceTokens` is defined as "4dp-based, strictly increasing" (4/8/12/16/24/32) and `DesignTokensTest` enforces multiples of 4 — yet components hardcode 2, 3, 6, 10, 14, 20 dp. Additionally the three button atoms — the same molecule in Atomic Design — use three different horizontal paddings and two different type styles.
**Why it matters:** spacing rhythm and component consistency are exactly what a token scale exists to guarantee (Atomic Design: atoms are uniform); the off-scale values also defeat the existing test's intent.
**Fix:** snap 2→`xs`, 6→`sm`-ish (or add `xxs=2`, `hairline=1` tokens explicitly if truly needed), 10→`sm`+`xs`; unify all three buttons to one padding (e.g. `lg`/`md`) and one text style (`TITLE`); add a test/lint that greps components for raw `dp(` literals not derived from tokens.

## 11. LOW/MEDIUM — Inconsistent corner-radii semantics: same element gets different radii in different organisms
**File:** poster thumbnails use `t.radius.poster` (20dp) in `PosterCard` (`Components.kt:477,483`) but `t.radius.button` (16dp) for the 44×64 thumb in `RequestsActivity.kt:105,112`; `Skeleton` blocks (which stand in for poster cards) use `t.radius.button` (`Components.kt:559`).
**What is wrong:** the radius scale has no "thumb" step; semantically identical elements get different corners, and skeletons don't match the cards they replace (visible shape jump when loading → loaded).
**Why it matters:** M3/Atomic Design: corner radius is a token with semantic roles; inconsistent roles make the UI feel uncoordinated and break the loading→loaded morph.
**Fix:** add `RadiusTokens.thumb` (e.g. 12dp) and use it for list thumbnails in `RequestsActivity`; use `radius.poster` for `Skeleton` so skeletons morph into poster cards.

## 12. LOW — No "size" token category; component dimensions are hardcoded dp literals
**File:** hero height `dp(300)` and scrim `dp(140)` (`DetailActivity.kt:61,77`), poster min height `dp(150)` and scrim `dp(56)` (`Components.kt:481,494`), thumb `dp(44)×dp(64)` (`RequestsActivity.kt:108,114`), nav pill `dp(48)` (`Components.kt:257`), touch targets `dp(48)` (`Components.kt:144,185,199,211,314,611`).
**What is wrong:** 48dp is the M3 minimum touch target (m3.material.io "touch target 48dp") and is hardcoded in six places with only an inline comment; other layout dimensions (hero, thumb, scrim) are likewise raw literals.
**Why it matters:** these are design values living outside `Tokens.kt`, so they can't be re-skinned or verified; at minimum the M3-mandated 48dp target deserves a named token.
**Fix:** add `SizeTokens(touchTargetDp = 48, heroDp = 300, thumbW = 44, thumbH = 64, posterMinDp = 150, scrimDp = 56)` to `DesignTokens` and reference it everywhere.

## 13. LOW — Ambient/hardcoded timing values bypass `MotionTokens`: aurora loop 24 000 ms + blob alpha 52, search debounce 350 ms, stagger steps 45/30 ms
**File:** `AuroraBackground.kt:55` (`duration = 24_000`), `:97` (`alpha = 52`), `Components.kt:345` (`postDelayed(pending!!, 350)`), `Motion.kt:74` (`stepMs: Long = 45`), callers `DetailActivity.kt:253` / `RequestsActivity.kt:147` (`stepMs = 30`).
**What is wrong:** `MotionTokens` is documented as the home of all durations, but the ambient loop, the debounce and the stagger step are raw literals scattered in three files (two different stagger steps).
**Why it matters:** minor, but it is the same class of token bypass as findings 5/7 and makes motion non-uniform (45 vs 30 ms).
**Fix:** add `MotionTokens.ambientMs` (or document the aurora loop as intentionally exempt), `debounceMs = 350`, `staggerStepMs = 30`, and use them in the three call sites.

---

## Top 5 changes I would make

1. **Fix text contrast where it is actually rendered** (findings 1–4): scrim behind `GradientPoster` titles, darken `accentStart/End` so the primary-button gradient passes 4.5:1 with white in both themes, add an `accentText` token for accent-as-text (dark: `0xFFA5B4FC`), and darken PorcelainLight status colors (verified: `0xFF036B4C / 0xFF92400E / 0xFF9F1239 / 0xFF075985`) — then extend `DesignTokensTest` to assert every text/background pair **as composited** (tints over glass, gradient endpoints), not just flat tokens.
2. **Make `glassLayered` token-driven** (finding 5): derive border gradient + sheen from `glassBorder` (or add explicit border/sheen tokens), fix the dead ternary at `Glass.kt:38`, and delete the duplicate `glassDrawable`.
3. **One theme-source policy** (findings 6 + 8): `BottomNavBar`/`MainScreen` use the captured `t` param everywhere, and `edgeToEdge()` sets the window background from `AppTheme.current` so `themes.xml` no longer hardcodes `#0A0C14`/`windowLightStatusBar` — this makes `AppTheme.use(PorcelainLight)` actually work end-to-end.
4. **Replace XML transition anims with token-driven code** (finding 7): build enter/exit animations in `openRising`/`finishDropping` from `MotionTokens` + `FastOutSlowIn`/`AccelerateInterpolator`, delete `res/anim/*`.
5. **Snap all spacing/radius/size literals into tokens** (findings 10–12): unify the three button atoms (one padding, one `TITLE` style), snap 2/3/6/10 dp to the 4dp scale, add `RadiusTokens.thumb` and a `SizeTokens` (incl. named 48dp M3 touch target), so a grep for raw `dp(`/color literals in components comes back clean.
