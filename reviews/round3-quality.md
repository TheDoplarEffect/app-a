# Round 3 — Quality review (naming, duplication, dead code, error style, magic numbers, shared state)

Lens: Kotlin Coding Conventions (kotlinlang.org/docs/coding-conventions.html), Effective Kotlin (M. Moskala), Ousterhout, *A Philosophy of Software Design*.
Scope: `/home/dev/app-a/app/src/main` only. All line numbers refer to files under `app/src/main/java/com/example/appa/`.

## Findings (ranked)

### 1. HIGH — StatusPoller's "in-flight guard" does not guard the async work; worker list grows forever
**AppState.kt:94-110** (`busy` released in `finally` immediately after spawning the refresh thread; `workers.add(th)` at :104 with no removal on completion).
The class doc (AppState.kt:76-79) promises "In-flight guard so slow *arr refreshes never pile up". In reality `busy.compareAndSet(false, true)` is reset in the `finally` block on the same tick, *before* the spawned network thread finishes, so the next tick (5 s later) starts another `refreshStatuses` while the previous one is still running — exactly the pile-up the comment claims to prevent. Additionally every tick appends a `Thread` to `workers` and nothing removes finished threads (only `cancel()` clears the list), so the list grows unboundedly (~12 entries/minute while a screen is open).
Why it matters: duplicated concurrent *arr/Jellyfin traffic; a stale comment that documents the opposite of the behavior is worse than no comment (Ousterhout: comments must describe what the code actually does; deep modules should have simple, truthful contracts). Mutable shared state (`busy`, `workers`) is exactly what Effective Kotlin warns about.
Fix: move `busy.set(false)` into the `handler.post { ... }` callback after the refresh completes; in that callback also `workers.remove(th)` (or use a single-thread `ExecutorService` instead of a hand-rolled list).
Ref: Ousterhout ch. 4/8 (comments & contracts), Effective Kotlin Item 30-ish (mutable shared state), Kotlin Conventions (clear, truthful API).

### 2. HIGH — "Test connection" always shows ✓ for Sonarr/Radarr because the read methods swallow their own errors
**Clients.kt:271-277 (`seriesStates`), 310-316 (`movieStates`), 280-285 / 318-323 (`queue*Ids`)** return `runCatching { ... }.getOrElse { emptyList() }`; **LiveRepository.kt:64-65** then tests them with `runCatching { sonarr.seriesStates() }.isSuccess`, which is *always true* because the exception was already swallowed inside. The Settings test chips for Sonarr/Radarr (SettingsActivity.kt:133-164) are therefore green even when the server is down.
Why it matters: inconsistent error style (throw on the write path per the ArrClient doc comment at Clients.kt:223-227, swallow on the read path) silently defeats the outer `runCatching` — Ousterhout: "the best modules are deep… but they must not mask exceptions"; swallowing inside a function makes it impossible for callers to distinguish "empty library" from "server unreachable". In `LiveRepository.refreshStatuses` (LiveRepository.kt:147-150) the same swallow makes a failed fetch look like "not in library" → status stuck at PENDING with no stale signal.
Fix: let `seriesStates/movieStates/queue*` throw `ApiException` on transport failure (returning `Result` or throwing consistently); apply `getOrElse` only once, at the poller/UI boundary, where failure is surfaced (the `failed` flag already exists).
Ref: Ousterhout ch. 8 (exceptions should not be swallowed/masked), Effective Kotlin (one error-handling strategy per layer).

### 3. HIGH — Corrupt stored JSON silently wipes all requests on next write
**Catalog.kt:208-227 (`RequestStoreCore.reload`)**: `items.clear()` runs first, then the JSON parse is wrapped in `runCatching { ... }` with no fallback. A malformed or partially-written `requests` string yields an empty (or partial) list; the next `persist()` (from any `create/delete/tick`) overwrites the stored value — permanent, silent data loss of the user's request history.
Why it matters: Ousterhout — don't mask exceptions; data-destroying code must fail loudly or not at all. Effective Kotlin — `runCatching` without a recovery plan is an anti-pattern.
Fix: parse into a local `val parsed = mutableListOf<Request>()` inside the `runCatching`, and only assign `items.clear(); items.addAll(parsed)` on success; on failure keep the raw string in prefs (do not persist) and expose a `lastLoadError` flag the UI can show.
Ref: Ousterhout ch. 8; Effective Kotlin (error handling must be deliberate).

### 4. MEDIUM — Components hard-code design values, contradicting Tokens.kt's "ONLY place raw design values live"
**Tokens.kt:4-9** declares tokens are the only place raw values live and "components never hard-code values", but Components.kt contains: chip tint alphas `0.30f` (:150, :166), `0.22f` (:378 `restyleChip`, :400 `StatusPill`), `0.18f` (:649 `ModeBanner`); poster-draw fractions `0.84f/0.14f/0.33f/0.30f/0.85f/0.62f/0.08f/0.05f/0.115f/0.10f/0.08f` (:434-475); timing literals debounce `350` ms (:356), double-tap `400` ms (:534); AuroraBackground `24_000` ms, `alpha = 52`, `w/8`, `coerceAtLeast(48)` (AuroraBackground.kt:36-37, 52, 94). The unit test even hard-codes `0.22f` (DesignTokensTest.kt:128-130), so a token change breaks the test — proof the abstraction leaks.
Why it matters: leaky abstraction (Ousterhout ch. 4/6 — tokens are the module interface; hard-coded values in components break the "swap the design system" promise written in the file's own header, Components.kt:25-33).
Fix: add `chipTintAlpha`, `badgeTintAlpha`, `bannerTintAlpha` (and poster-draw constants / timing constants) to `DesignTokens` or named `const val`s in Components.kt; update the test to read the token.
Ref: Ousterhout (information hiding / named constants instead of magic numbers, ch. 9); Kotlin Conventions (constants named, not magic literals).

### 5. MEDIUM — `statusColorOf` / `statusLabelOf` duplicate the same status→presentation mapping
**Components.kt:382-394**: two parallel `when` blocks over identical conditions (`available || AVAILABLE`, `PENDING`, `PROCESSING`), both consumed by `StatusPill` (:396-401). Any future status (e.g. FAILED) must be added in two places; the label and color can drift apart (label "✓ Available" with the wrong color).
Why it matters: direct duplication (Effective Kotlin: DRY); single source of truth for presentation logic.
Fix: one function `fun statusPresentation(media: Media, status: RequestStatus?, t: DesignTokens): Pair<String, Int>?` returning label+color, used by `StatusPill`.
Ref: Effective Kotlin (avoid duplication); Ousterhout (one definition of a concept).

### 6. MEDIUM — ModeBanner text duplicated verbatim; top bar updated by fragile index-based child traversal
**MainScreen.kt:176-184** re-implements the exact strings and content descriptions from **Components.kt:644-653** (`ModeBanner`), and **MainScreen.kt:171-173** reaches into the top bar with `(topBar.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout` then `titleCol.getChildAt(1) as? TextView` — if the subtitle is ever absent at build time, `getChildAt(1)` throws `IndexOutOfBoundsException` (the `as?` cast cannot save it). Similarly `renderRequestsBadge` (MainScreen.kt:341) finds the button via the string tag `"top_action_📥"` built inside `AppTopBar` (Components.kt:118).
Why it matters: Ousterhout ch. 6 — information hiding; callers depending on internal view structure/tags of a component is a leaky abstraction that will break on any refactor of `AppTopBar`. Duplicated literals drift.
Fix: give `AppTopBar` an `update(title/subtitle)` or return a handle holding the subtitle `TextView`; make `ModeBanner` a small class with `fun update(live: Boolean)` (or a `modeBannerText(live, t)` function both callers use); expose a typed accessor for the requests action button instead of a tag string.
Ref: Ousterhout ch. 4/6 (information hiding, pull complexity down); Effective Kotlin (no duplicated literals).

### 7. MEDIUM — Process-wide mutable maps in `MainScreen`'s companion, accessed unsynchronized and shared across activity instances
**MainScreen.kt:344-350**: `val cache = mutableMapOf(...)`, `val signatures = mutableMapOf(...)` in `private companion object`, aliased at :56-57 (`private val cache = Companion.cache`). They are plain `MutableMap`s (not concurrent, no lock) mutated in `evictCache` (:229-230) and `loadItems`/`renderCurrent`. Today they happen to be touched only on the main thread, but nothing enforces that (worker callbacks post to main, yet the type system allows any access), and they silently couple every `MainScreen` instance created by rotation.
Why it matters: mutable shared state (Effective Kotlin); hidden global state that makes behavior depend on instance history (Ousterhout — classes should encapsulate their state; a companion map is a global).
Fix: wrap in a small `object ResultCache { private val map = ConcurrentHashMap...; fun get/put/evict }` with synchronized methods, or make it an explicit app-scoped singleton (e.g. in `AppState`) with a documented owner; drop the `Companion.cache` alias indirection.
Ref: Effective Kotlin (mutable shared state); Ousterhout ch. 5/12 (encapsulation).

### 8. MEDIUM — `col`/`row` accept a `t: DesignTokens` parameter they never use; `lp` only supports top/start margins
**Components.kt:44-48**: `fun col(ctx, t: DesignTokens = AppTheme.current, init)` — `t` is unused in the body, so every caller passing a theme is misled into thinking the container is theme-aware. **Components.kt:50-54**: `LinearLayout.lp(w, h, weight, marginTopDp, marginStartDp)` silently ignores bottom/end margins — callers needing those must hand-roll `LayoutParams`, and the generic name `lp` promises full layout params.
Why it matters: misleading names/unused parameters (Effective Kotlin: don't expose parameters you don't use; names must match behavior; Kotlin Conventions: avoid dead parameters in public API).
Fix: remove the `t` parameter from `col`/`row` (or actually apply a default background from it); rename/extend `lp` to `lp(w, h, weight, marginsDp: Int = 0)` or accept a `Margin` data class.
Ref: Effective Kotlin (naming, unused params); Kotlin Coding Conventions (function signatures).

### 9. MEDIUM — No consistent error-handling style across the codebase
Five different strategies coexist: `runCatching{}.getOrDefault` (LiveRepository.kt:147-150), `runCatching{}.getOrElse` (Clients.kt:272), `runCatching{}.getOrNull` (Clients.kt:168, 336), manual `Result.failure(ApiException(...))` (MainScreen.kt:271), bare `catch (e: Exception) { media }` (Clients.kt:100-101 in `TmdbClient.details`), and `throw ApiException` (write path, Clients.kt:233-236). Consequence: some failures are silent, some become empty lists, some become toasts — the caller cannot tell them apart (this is the root cause of findings 2 and 3).
Why it matters: Effective Kotlin — pick and document one error strategy per layer; Ousterhout ch. 8 — exceptions should propagate to the layer that can decide what they mean.
Fix: rule = "clients throw `ApiException`; only the UI/poller layer converts to `Result`/stale-flag". Replace `catch (e: Exception)` in `details` with `runCatching` + logged fallback, and delete the manual `Result.failure` in MainScreen (use `runCatching` around a nullable repo instead).
Ref: Ousterhout ch. 8; Effective Kotlin (consistent error handling, don't swallow).

### 10. MEDIUM — `spawnWorker`/`workers` (and the `dialogs` list) copy-pasted across three files
**MainScreen.kt:47, 59-64**, **DetailActivity.kt:37, 101-106**, **AppState.kt (StatusPoller:87, 104)** all repeat the same `Collections.synchronizedList(mutableListOf<Thread>())` + `Thread(block).also { workers.add(it); it.start() }` + interrupt-on-destroy pattern; the dialog-dismiss pattern is duplicated in DetailActivity.kt:93-96 and RequestsActivity.kt:81-83.
Why it matters: duplication of concurrency plumbing (Effective Kotlin DRY); each copy can drift (as finding 1 shows the poller copy already diverged in behavior).
Fix: extract `class WorkerScope { fun spawn(block: () -> Unit); fun cancelAll() }` (and `DialogScope`) into one file, e.g. `AppState.kt` or a new `Concurrency.kt`; use it in all three places.
Ref: Effective Kotlin (avoid duplication); Ousterhout (one place for one concept).

### 11. MEDIUM — Long functions: `onCreate`/`attach` build entire screens inline; `onDraw` does layout math inline
**DetailActivity.kt:40-125** (~85 lines in `onCreate`), **SettingsActivity.kt:39-130** (~90 lines, including a local `field` factory closure), **MainScreen.kt:67-141** (`attach`, ~75 lines), **Components.kt:438-475** (`GradientPoster.onDraw`, ~37 lines of wrapping/drawing math with 10+ magic fractions).
Why it matters: Ousterhout ch. 7 — long methods hide the structure; each of these mixes construction, wiring, and logic at one abstraction level, making changes risky (see finding 6 for a concrete breakage).
Fix: extract `buildTopBar/buildBanner/buildPager/buildNav` in `MainScreen.attach`, `buildForm` in Settings, `buildHero/buildMeta/buildMagnets` in Detail; move the title-wrapping loop in `onDraw` into a pure `fun wrapTitle(title: String, maxChars: Int, maxLines: Int): List<String>` (unit-testable, like `PagerMath`).
Ref: Ousterhout ch. 7 (split long methods); Effective Kotlin (small functions, one level of abstraction).

### 12. MEDIUM — Inverted, misleading variable name `lightIcons` in edge-to-edge setup
**Insets.kt:24-29**: `val lightIcons = luminance(background) >= 0.5` — a *light background* means we must request **dark** icons, which is what `APPEARANCE_LIGHT_STATUS_BARS` does. The variable reads as the opposite of what it controls; the `0.5` threshold is an unnamed magic number.
Why it matters: misleading names are a readability hazard (Effective Kotlin: names must be precise; Kotlin Conventions: meaningful names); a future reader will "fix" the apparent inversion and break status-bar icon contrast on the light theme.
Fix: rename to `lightBackground` (or `needsDarkIcons = luminance(bg) >= LUMINANCE_LIGHT_THRESHOLD`), extract `const val LIGHT_BG_LUMINANCE = 0.5f`.
Ref: Effective Kotlin (naming); Kotlin Conventions (naming); Ousterhout (magic numbers → named constants).

### 13. MEDIUM — Settings form uses nine `fields["key"]!!` lookups keyed by string literals duplicated from the `field(...)` call sites
**SettingsActivity.kt:66-103** registers fields with literal keys (`"tmdbToken"`, …), and **SettingsActivity.kt:106-114** reads them back with `fields["tmdbToken"]!!` etc. (plus `chips[name]!!` at :155). Renaming a key on one side only → runtime NPE in `collect()`; the `!!` operators are exactly the crash-prone pattern Effective Kotlin tells you to avoid.
Why it matters: stringly-typed wiring + `!!` = latent crash; duplication of nine key literals.
Fix: define `enum class ConfigField { TMDB_TOKEN, SONARR_URL, ... }` (or `const val` keys) used on both sides, and have `field()` return the `EditText` which is stored in typed properties (e.g. `private lateinit var tmdbInput: EditText`), eliminating both the map and the `!!`.
Ref: Effective Kotlin (avoid `!!`; prefer typed access); Kotlin Conventions.

### 14. LOW — Dead code: `EmphasizedPath`, `glassDrawable`, `renderOnce` are never referenced
**Motion.kt:32** `val EmphasizedPath` — zero usages in main and test. **Glass.kt:18** `fun glassDrawable(...)` — `glass()` uses `glassLayered`; nothing calls `glassDrawable`. **AuroraBackground.kt:104** `fun renderOnce()` — "static fallback for tests/previews" but no test calls it.
Why it matters: dead code misleads readers about which motion/glass path is live (Effective Kotlin: remove unused declarations; Ousterhout: don't keep speculative generality).
Fix: delete all three (`compositeOver`/`contrastRatio` are used by DesignTokensTest — keep those).
Ref: Effective Kotlin (don't keep dead code); Ousterhout ch. 12 (no speculative code).

### 15. LOW — Package-level `MATCH`/`WRAP` constants and mixed PascalCase/camelCase function naming in the component library
**Components.kt:39-40** `const val MATCH`/`WRAP` shadow `ViewGroup.LayoutParams.MATCH_PARENT/WRAP_CONTENT` semantics under misleadingly generic package-visible names; the same file mixes PascalCase factory-style functions (`AppText`, `Chip`, `PrimaryButton`, `GlassCard`, `StatusPill`) with camelCase ones (`col`, `row`, `glass`, `pressable`), and `MainScreen`/`DetailActivity` use `showMainScreen` vs `AppTopBar` inconsistently.
Why it matters: Kotlin Coding Conventions — function names use camelCase (PascalCase is reserved for constructors/factories of the returned type, and these return `TextView`/`FrameLayout`, not their own name); overly generic constant names pollute the whole package namespace and invite import confusion.
Fix: rename to `MATCH_PARENT`/`WRAP_CONTENT` aliases with distinct names (e.g. `LP_MATCH`, `LP_WRAP`) or use the `ViewGroup.LayoutParams` constants directly; unify function naming (e.g. `appText`, `chip`, `primaryButton` or keep the composable-like style deliberately and document it — but pick one).
Ref: Kotlin Coding Conventions (naming rules); Effective Kotlin (consistent naming).

### 16. LOW — Two different things are both called "liveMode"
**Config.kt:13** `AppConfig.liveMode` is the raw user toggle; **AppState.kt:41** `AppState.liveMode` is the *derived* `config.normalized().liveReady`; SettingsActivity additionally keeps `liveChecked` (:23, :131). A config with the toggle on but TMDB missing has `config.liveMode == true` but `AppState.liveMode == false` — and `ModeBanner`/search hint bind to the latter while the Settings switch binds to the former.
Why it matters: misleading name collision across layers (Effective Kotlin; Ousterhout — names should expose the abstraction the caller needs, not collide).
Fix: rename the derived one to `liveActive` (or `liveReady`) in `AppState`, keep `liveMode` only for the raw config flag.
Ref: Effective Kotlin (naming); Ousterhout ch. 9 (names matter).

### 17. LOW — Live request path can orphan a Sonarr/Radarr entry
**DetailActivity.kt:218-236 (`doRequest`)**: in live mode it calls `repo.request(media)` (adds the movie/series in *arr) and *then* `store.create(...)`; if `create` returns null (already requested locally), the *arr entry exists but no local request tracks it, and the toast says "Already requested" — the user cannot cancel what *arr now holds.
Why it matters: ordering of side effects; the check should happen before the external write (Ousterhout: validate before acting).
Fix: check `AppState.store.byMediaId(media.id) != null` first and bail with a toast; only call `repo.request` when the local create will succeed (or reconcile: adopt the existing request with `setExternal`).
Ref: Ousterhout (do validation before side effects); Effective Kotlin (fail fast).

### 18. LOW — `SearchField`'s debounce Handler is never cleared on detach; AuroraBackground never recycles its bitmap
**Components.kt:330-357**: `handler.postDelayed(pending!!, 350)` — nothing calls `removeCallbacks` when the screen is destroyed, so a pending runnable retains `MainScreen` and can fire `loadItems()` on a detached screen (the placeholder path in MainScreen.kt:243-248 has no `attached` check, unlike the live path at :280). **AuroraBackground.kt:33-41**: `onSizeChanged` assigns a new `Bitmap` without `bmp?.recycle()` — each rotation leaks the previous offscreen bitmap.
Why it matters: resource/memory leaks (Effective Kotlin: manage resources deterministically; Ousterhout: clean up).
Fix: add `fun SearchField.close() { handler.removeCallbacksAndMessages(null) }` and call it from `MainScreen.detach()`; `bmp?.recycle()` before reassigning in `onSizeChanged`.
Ref: Effective Kotlin (resource cleanup); Ousterhout ch. 10-ish (ownership).

### 19. LOW — Leftover cruft: stale comment in build.gradle, Java entry point in a Kotlin codebase
**app/build.gradle:43** ends with `// CI retry trigger (transient maven resolution failure)` — a leftover note with no code attached. **MainActivity.java** is the only Java file; its own comment says "All UI/logic lives in Kotlin" but the entry point itself is Java (minor inconsistency with the project's Kotlin-first convention).
Why it matters: dead comments rot and mislead (Ousterhout: comments should add information); mixed-language entry point contradicts the stated convention (Kotlin Conventions / project consistency).
Fix: delete the trailing comment; optionally migrate `MainActivity` to `MainActivity.kt` (trivial — it only forwards lifecycle calls).
Ref: Ousterhout (comments); Kotlin Coding Conventions (project consistency).

---

## Top 5 changes

1. **Fix `StatusPoller` (AppState.kt:94-110)**: release `busy` only after the async refresh completes and remove finished threads from `workers` — make the code match its documented in-flight guarantee (or replace with a single-thread executor).
2. **Make *arr read methods throw instead of swallowing (Clients.kt:271-323)** so `pingAll` (LiveRepository.kt:64-65) actually detects a down Sonarr/Radarr, and `refreshStatuses` can surface a stale/failed signal instead of "PENDING".
3. **Make `RequestStoreCore.reload` non-destructive (Catalog.kt:208-227)**: parse into a temp list and only swap on success — one corrupt write must never wipe the persisted request history.
4. **Extract the duplicated concurrency/worker plumbing and the status/banner presentation into single definitions** (WorkerScope; `statusPresentation()` replacing `statusColorOf`/`statusLabelOf`; `ModeBanner.update()` replacing the string copies in MainScreen.kt:176-184) — one place per concept.
5. **Honor the token contract (Tokens.kt:4)**: move the hard-coded chip alphas (0.30f/0.22f/0.18f), debounce/double-tap timings (350/400 ms) and poster-draw fractions out of Components.kt/AuroraBackground.kt into `DesignTokens`/named constants, and fix the misleading names (`lightIcons`, `col`/`row`'s unused `t`, `liveMode` collision) plus delete the dead `EmphasizedPath`/`glassDrawable`/`renderOnce`.
