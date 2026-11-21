# Round 5 — Simplicity & Brevity Review (master HEAD, v4.5, cd6abab)

Scope: `app/src/main/java/com/example/appa/` (22 Kotlin files + MainActivity.java, ~4,000 LOC) + tests.
Lens: KISS/YAGNI — dead code, over-engineering, redundant branches, duplication, spaghetti.
Fresh review; prior round reports were not consulted.

## Findings (ranked by severity)

### 1. MEDIUM — Duplicated mode-banner strings in two places (drift risk)
- **Files:** `MainScreen.kt:171-186` (`refreshChrome`) vs `Components.kt:647-655` (`ModeBanner`)
- The exact banner text (`"🌐 Live — TMDB · Sonarr/Radarr · Prowlarr · Jellyfin"`, `"🧪 Placeholder mode — …"`), colors, and content descriptions are copy-pasted. A copy edit in one place silently diverges from the other.
- **Fix:** extract one function, e.g. `fun ModeBanner.applyState(live: Boolean, t: DesignTokens)` in Components.kt (or `bannerSpec(live, t): Triple<String, Int, String>`), and have both `ModeBanner(...)` and `refreshChrome()` consume it.

### 2. MEDIUM — Dead design tokens shipped in the app
- **File:** `Tokens.kt`
- `ColorTokens.accentSolid` (decl :28, set :110 and :143) is never read by any app code — only `DesignTokensTest` reads it to assert contrast. Same for `ElevationTokens.level2Dp/level3Dp/shadowColor` (:78-79, :124, :145 — only `level1Dp` is used, in `Components.kt` BottomNavBar) and `SpaceTokens.xxl` (:51, :121 — only tests assert the scale is monotonic). `contrastRatio()` (:197) is likewise test-only and lives in the main source set.
- **Why it matters:** dead tokens invite "we already have a token for that" confusion and bloat the design-system surface that must be maintained/swapped per theme.
- **Fix:** delete `accentSolid`, `level2Dp`, `level3Dp`, `shadowColor`, `xxl` from the token classes (adjust tests to stop asserting on them), or move `contrastRatio` to the test source set. If `accentSolid` was meant for solid-fill buttons, either use it or remove it.

### 3. MEDIUM — Dead code: unused `networkError` and unused `Magnet.addedAt`
- **Files:** `Http.kt:16` (`val networkError` — zero references in main or tests); `Catalog.kt:60` (`Magnet.addedAt`, assigned at :380, never read anywhere).
- **Fix:** delete both. If `networkError` was meant to drive UI error messaging, wire it where `resp.status == 0` is currently special-cased implicitly; otherwise remove.

### 4. MEDIUM — `col()`/`row()` carry a dead `t: DesignTokens` parameter
- **File:** `Components.kt:44,47` — `fun col(ctx, t: DesignTokens = AppTheme.current, init)` / `fun row(...)`; `t` is never used in the body, yet ~40 call sites thread it through (`col(ctx, t, init)`).
- **Fix:** drop the parameter: `fun col(ctx: Context, init: LinearLayout.() -> Unit = {})`. Mechanical, removes noise from every screen.

### 5. MEDIUM — Badge chip built twice; `TopBarAction.badge` is a `var` that is never mutated
- **File:** `Components.kt` — badge `Chip` + layout params + content-description string duplicated in `CircleIconButton` (:154-162) and `setBadge` (:167-182). `TopBarAction.badge` (:105) is declared `var` but only ever read at construction time (`setBadge` updates the view, not the data class).
- **Fix:** extract `private fun badgeChip(ctx, n, t): TextView` (chip + tag + LayoutParams), call it from both places; make `TopBarAction.badge` a `val`.

### 6. MEDIUM — Speculative guards / no-op statements kept "just in case"
- **Files:**
  - `Components.kt:532-537` — PosterCard's 400 ms double-tap guard swallows legitimate fast taps; the wrapped `onClick` just opens an activity, so a double-fire is harmless (and `loadJob` already dedupes loads in MainScreen). YAGNI.
  - `AppState.kt:41-42` — `prefs.getString("requests", null)` and `Catalog.all.size` are executed purely for warm-up side effects with discarded results; the comment admits it. Speculative optimization with zero measured need.
  - `AppState.kt:132` — `fun cancel() = stop()` is a redundant alias (only difference would be interrupting workers, which it doesn't do).
- **Fix:** delete the guard, the two warm-up statements (keep the real loads), and the alias (call `stop()` at the call sites).

### 7. LOW-MEDIUM — Throwaway `AppConfig()` instances to call a stateless validator
- **File:** `SettingsActivity.kt:63` and `:122` — `AppConfig().urlError(...)` constructs a default config only to call a method that uses no instance state (`Config.kt:39-41`, `validUrl` is also stateless).
- **Fix:** move `validUrl`/`urlError` to `AppConfig.Companion` and call `AppConfig.urlError(...)`.

### 8. LOW-MEDIUM — Dead branch in `TmdbClient.toMedia`
- **File:** `Clients.kt:89-93` — the `date` `when` has `else -> ""`, but `MediaType.AUDIOBOOKS -> return null` already fired two lines above (:84-85), so the `else` is unreachable.
- **Fix:** replace with a two-branch `when` (or `if (type == MOVIES) … else …`).

### 9. LOW-MEDIUM — Cryptic magic-number arithmetic in the glass fill
- **File:** `Glass.kt:29` — `withAlpha(…, ((base ushr 24) + if (strong) 26 else 16) / 255f)` hand-rolls an alpha channel with magic constants 26/16.
- **Fix:** name it: `const val FILL_TOP_ALPHA_BOOST = 16 / 26` or compute via `withAlpha(sheenColor, (alphaOf(base) + boostDp) / 255f)` with a comment. Same for the sheen `0.10f` (:35).

### 10. LOW — Redundant filter and fragile child-index access in MainScreen
- **File:** `MainScreen.kt:264` — `all.filter { q.isBlank() || it.type == tab }` re-filters what `Catalog.ofType(tab)` already guarantees when `q` is blank; write `if (q.isBlank()) Catalog.ofType(tab) else Catalog.search(q).filter { it.type == tab }`.
- **File:** `MainScreen.kt:358-359` — `renderCurrent` pokes `EmptyState`'s children by index (`getChildAt(1) as TextView`) — breaks silently if EmptyState's layout changes. There is already a tag-based `setSubtitle`; add the analogous `EmptyState.setMessage(title, msg)` (or tag the TextViews) in Components.kt.

### 11. LOW — Over-engineered cache eviction
- **File:** `MainScreen.kt:241-249` (`evictCache`) — TTL eviction plus a second "LRU-ish: drop the oldest half" pass plus a `signatures` cleanup, for a map capped at 24 entries whose keys are tab|query pairs. Sorting and half-eviction is unnecessary complexity.
- **Fix:** keep only the TTL eviction and the 24-entry cap enforced by removing oldest entries in one simple loop (or just `cache.clear()` when over cap — 24 entries is nothing).

### 12. LOW — Inconsistent reduced-motion press feedback
- **File:** `Motion.kt:104` — under `reduceMotion()` the press scale is applied instantly at 0.97, while normal mode animates to 0.94: two different effects, and reduced-motion users arguably should get no scale at all.
- **Fix:** `if (v.context.reduceMotion()) return@setOnTouchListener false`-style skip — one behavior, no animation, no magic 0.97.

### 13. LOW — Redundant `synchronized` on an already-synchronized list
- **Files:** `DetailActivity.kt:102`, `RequestsActivity.kt:80` — `dialogs` is a `Collections.synchronizedList`, then wrapped in another `synchronized(dialogs) { … }` for iteration. The outer block is required for safe iteration of a synchronizedList, so this is actually correct — but the pattern is duplicated; extract `dismissAllDialogs()` helper if kept. (Kept at LOW because it is technically needed for compound iteration; flagging only the duplication.)

### 14. LOW — Legacy TSV migration path kept alive "for one migration release"
- **File:** `Catalog.kt:236-247` (`parseAll` legacy branch). Intentional per the comment, but it is permanent-looking complexity (escape handling `\\t`/`\\n`, per-line tolerance) in the hot persistence path. YAGNI watch: once the migration release has shipped, delete the TSV branch and keep only the JSON array parse.

### 15. LOW — Cosmetic dead weight
- `AuroraBackground.kt:105` — dangling comment `/** Static fallback for tests/previews. */` with an empty body; `AuroraBackground.kt:22-24` sets `ANTI_ALIAS_FLAG` in the constructor and `isAntiAlias = true` again in `apply`.
- `MainScreen.kt:371` companion comment says cache key is `"tab|query"` but `cacheKey()` (:232) actually builds `"L|tab|q"`/`"P|tab|q"` — fix the comment.
- `Config.kt:53-60` — `toJson` routes every put through a local lambda `p`; harmless but can be direct `put(...)` calls.
- `PagerMath.targetPage` (:20) exposes `flingThresholdPxS` default param that no production caller overrides (only tests) — fine to keep for tests, noted for awareness.

## What is NOT a problem (checked, keep as is)
- `StatusPoller` in-flight guard, `loadJob` counter, signature-based re-render skip in `renderCurrent`/`RequestsActivity.render` — real dedupe, not speculative.
- SSRF guard + manual redirect handling in `Http.kt` (`isSafeExternalUrl`, sensitive-header stripping) — security-motivated, not over-engineering; `magnetFromDownloadUrl` fallback chain is justified.
- `SecureStore` wipe-on-decrypt-failure + `configWasWiped` surfacing — small and purposeful.
- `ImageLoader` tag-based obsolete-request check and bounded queue — correct, minimal.
- `RequestStoreCore` "corrupt: keep existing items, never wipe" policy — one clear rule, well commented.
- `SwipePager` manual intercept/touch split was reviewed for a double `releaseDrag` on ACTION_UP — once a ViewGroup intercepts, later events bypass `onInterceptTouchEvent`, so it fires once. No fix needed.

## Summary counts
- Dead/unused in app code: 5 token fields + 1 test-only helper (`Tokens.kt`), `Resp.networkError` (`Http.kt:16`), `Magnet.addedAt` (`Catalog.kt:60`), `t` param of `col`/`row` (`Components.kt:44,47`).
- Duplication: mode-banner strings (MainScreen/Components), badge chip (CircleIconButton/setBadge), dialog-dismiss block (Detail/Requests).
- Redundant branches/aliases: `Clients.kt:91` dead `else`, `AppState.kt:132` `cancel()=stop()`, `MainScreen.kt:264` redundant filter, `Motion.kt:104` dual press-scale paths.
- Over-engineering candidates: `MainScreen.evictCache` LRU-half pass, PosterCard double-tap guard, AppState cache warm-up no-ops, legacy TSV reader (time-boxed).
