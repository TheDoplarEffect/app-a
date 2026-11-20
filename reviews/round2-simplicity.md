# Round 2 — Simplicity lens (Ousterhout / KISS / YAGNI)

Scope: current code under `app/src/main/java/com/example/appa/` only.
Lens: Ousterhout, *A Philosophy of Software Design* — the chapter on simplicity / "taming complexity" (complexity is incremental; prefer the simplest thing that works; general-purpose vs. special-purpose modules), plus KISS and YAGNI. Looking for over-abstraction, premature generalization, duplicated logic that should be one helper, dead "just in case" code, spaghetti control flow / deep lambdas, and unclear happy paths.

---

## Findings (ranked)

### 1. HIGH — A general-purpose theme-swap mechanism is threaded through ~40 functions, but only one theme ever exists (and the mechanism doesn't even work)
- **Where:** `Components.kt` — 25+ signatures carry `t: DesignTokens = AppTheme.current` (lines 44, 47, 63, 117, 140, 162, 179, 193, 205, 224, 298, 359, 371, 376, 393, 403, 416, …); same in `Glass.kt:18,29,55,64`, `Motion.kt:28`, `Tokens.kt`. `Tokens.kt:124` (`PorcelainLight`), `Tokens.kt:156-162` (`AppTheme.use/reset`) — never called anywhere (verified by grep). Inconsistency proof: `BottomNavBar.select` ignores its own constructor `t` and reads `AppTheme.current` directly (`Components.kt:271,278`).
- **What is wrong:** Every component takes a `t` parameter and every caller dutifully passes `t` down, yet nothing ever calls `AppTheme.use()`. Worse, components read token *values* at build time (`t.radius.pill`, `t.colors.error`), so a runtime theme swap would not re-skin existing views anyway — the general-purpose mechanism delivers none of its promised benefit.
- **Why it matters:** This is exactly Ousterhout's general-purpose vs. special-purpose trap: a general mechanism (inject any theme anywhere) built for a special need (one hard-coded theme). It adds a parameter to every signature, noise at every call site, and two divergent read paths (`t` vs. `AppTheme.current`), while being dead weight.
- **Fix:** Delete the `t: DesignTokens = AppTheme.current` parameter from every function and read `AppTheme.current` inside the body (one global, one read path). Delete `PorcelainLight`, `AppTheme.use/reset`. If a second theme is ever actually needed, add the indirection then (YAGNI). Alternatively keep params but remove the global — pick one, not both.
- **Reference:** Ousterhout, ch. on simplicity/"taming complexity" (general-purpose vs. special-purpose modules; "the best features are the ones you don't build"); YAGNI.

### 2. HIGH — Three near-identical *arr HTTP clients (Prowlarr/Sonarr/Radarr) duplicate the same plumbing three times
- **Where:** `Clients.kt:175-180` (Prowlarr `headers`/`url`/`api`), `Clients.kt:214-224` (Sonarr `headers`/`url`/`api`/`post`), `Clients.kt:274-284` (Radarr — same four again), plus duplicated `rootFolder()`/`qualityProfileId()` at `Clients.kt:236-237` and `292-293`.
- **What is wrong:** `mapOf("X-Api-Key" to apiKey)`, the `url(path, params)` concatenation with the blank-params ternary, the `api()` wrapper around `httpGetJsonRetrying`, and the `post()` with error-throwing — all copied verbatim into three classes.
- **Why it matters:** Any fix (trailing-slash bug, retry policy change, header addition, auth change) must be applied three times and will drift. Ousterhout: pull shared complexity down into one module ("taming complexity"); DRY (Hunt & Thomas). The duplication also obscures the actual domain logic of each client.
- **Fix:** One small `ArrClient(baseUrl, apiKey, http)` class exposing `get(path, params)`, `post(path, body)`, `rootFolder(default)`, `qualityProfileId()`; Sonarr/Radarr/Prowlarr hold one instance and keep only their domain methods (`addSeries`, `movieStates`, `search`, …).
- **Reference:** Ousterhout ch. on taming complexity ("pull complexity downward, eliminate duplication"); DRY / KISS.

### 3. MEDIUM — Settings validates *every* field as a URL, so typing into any API-key/token field shows a bogus error
- **Where:** `SettingsActivity.kt:51-66` — the `field()` helper attaches a `TextWatcher` that calls `AppConfig().urlError(et.text…)` (`:60`) to **all nine** fields, including "TMDB API read token" and the four API keys. `Config.kt:38-40`: `validUrl(u) = u.isBlank() || u.startsWith("http://") || u.startsWith("https://")`.
- **What is wrong:** A JWT token or API key is non-blank and doesn't start with `http`, so the first keystroke in any secret field displays "URL needs http:// or https://". The happy path of the settings form is broken by applying a special-purpose validator (URL) to general inputs.
- **Why it matters:** Users see a false error on the fields they edit most; the validation abstraction was generalized one step too far.
- **Fix:** Add `isUrl: Boolean = false` to `field(...)` and only attach the validating watcher when `isUrl` is true (set it for the four `*Url` keys).
- **Reference:** Ousterhout general-purpose vs. special-purpose; KISS ("simplest thing that works").

### 4. MEDIUM — Hand-rolled tab-separated persistence with escaping for data that can never contain tabs
- **Where:** `Catalog.kt:152-181` — `reload()` splits on `"\t"`, unescapes `\\t`/`\\n`, and reads positionally (`p[0]`, `p[1]`, … `p.size >= 5`); `persist()` escapes and joins the same way.
- **What is wrong:** A bespoke serialization format with escape/unescape logic guarding against tabs/newlines in fields that are always `req-<uuid8>`, `series-01`-style slugs, `PENDING`, or `radarr:123` — the "ridiculous edge case nobody will hit". And the escaping is lossy anyway (a literal backslash-t in a field would be corrupted on reload), so it doesn't even fully solve the case it was written for. kotlinx-serialization is already a dependency and `Config` already serializes to JSON.
- **Why it matters:** ~30 lines of fragile, hard-to-reason format code (positional indexes + escaping) replace one JSON array. Complexity with zero payoff.
- **Fix:** Persist as a JSON array via `buildJsonArray { items.forEach { add(buildJsonObject { … }) } }` and parse with the shared `JsonElement` accessors; delete all escaping.
- **Reference:** KISS; Ousterhout "taming complexity" (don't build machinery for hypothetical cases); YAGNI.

### 5. MEDIUM — The status-polling ticker is implemented twice, nearly identically, in two screens
- **Where:** `MainScreen.kt:43-61` and `RequestsActivity.kt:62-79` — both do: `if (liveMode) { store.tick(); Thread { runCatching { repo?.refreshStatuses(store) } … handler.post { re-render } } } else { if (store.tick().isNotEmpty()) render() }` with the same 5000/1000 ms intervals.
- **What is wrong:** Duplicated concurrency logic (background `Thread` + `handler.post` + lifecycle guard) in two nested-lambda pyramids; interval and error handling must be changed in two places.
- **Why it matters:** DRY violation on the most concurrency-sensitive code in the app; the two copies already differ subtly (MainScreen also updates the badge, RequestsActivity re-renders unconditionally).
- **Fix:** One helper, e.g. `AppState.startStatusPoller(onChanged: () -> Unit): () -> Unit` (returns a stop function) that owns the interval, the background thread, and the main-thread callback; both screens just call it and pass their render function.
- **Reference:** DRY; Ousterhout "one module, one responsibility"; KISS.

### 6. MEDIUM — `LiveRepository.refreshStatuses` duplicates the radarr/sonarr branch; the class accumulates four ad-hoc caches
- **Where:** `LiveRepository.kt:161-175` — the `when` on `r.external` has two structurally identical branches differing only in which state/queue lists they consult. Caches at `LiveRepository.kt:49-51` (`audiobookIndexerIds` non-`@Volatile` hand-rolled lazy, `cachedMovieTmdbIds`, `cachedSeriesTmdbIds`) and `:184` (`mediaCache`).
- **What is wrong:** Parse the external ref once (`kind = substringBefore(':')`, `id = substringAfter(':').toIntOrNull()`), then pick `(states, queue)` by kind — instead of two copy-pasted blocks. `audiobookIndexerIds()` is a double-checked-locking lookalike without `@Volatile` (the field at `:49` isn't volatile, unlike its siblings).
- **Why it matters:** Copy-paste branches are where bugs hide (e.g. swapping the two lists); the cache sprawl makes the data flow hard to follow.
- **Fix:** Collapse the `when` to a single lookup as above; mark `audiobookIndexerIds` `@Volatile` or use `by lazy`.
- **Reference:** DRY; Ousterhout taming complexity; KISS.

### 7. MEDIUM — `DetailActivity.onCreate` is a ~120-line, 3-4-deep lambda pyramid; `renderMeta` hand-rolls chip wrapping with `i % 3`
- **Where:** `DetailActivity.kt:36-160` (whole screen built inline inside one `col(this) { … }` with nested `FrameLayout.apply`, `col { … }`, `Thread { … handler.post { … } }`); chip wrapping at `DetailActivity.kt:129-152` (`if (i > 0 && i % 3 == 0)`, `if (i % 3 != 0) marginStart`, `marginTopDp = if (i == 3) 0 else 6`).
- **What is wrong:** The happy path (top bar → hero → meta card → actions → magnets) is buried in nesting; the modulo-wrap is a fragile special-purpose substitute for a flow layout, with margin special-cases that are easy to break.
- **Why it matters:** Ousterhout: avoid deep nesting — extract methods so the reader sees the top-level flow of control. New code added to onCreate keeps sinking deeper.
- **Fix:** Extract `buildHero(media): View`, `buildMetaCard(): GlassCard`, `buildActionsRow()`, `buildMagnetsSection()` and call them in sequence from `onCreate`. Replace the modulo wrap with a tiny `addChipsFlow(container, chips)` helper that appends chips to a row and starts a new row when a max chip count/width is reached — or simply one chip per line (simplest that works).
- **Reference:** Ousterhout ch. on simplicity ("taming complexity: deep nesting is a symptom"); KISS.

### 8. LOW — Dead "might need it later" code (YAGNI violations)
- **Where:** `Components.kt:351` `SearchField.clearSilently()` — never called (its comment claims "used when switching tabs", but no caller exists); `DetailActivity.kt:34,95` `detailsRequested` — written, never read; `Motion.kt:25` `EmphasizedPath` — never used; `Tokens.kt:124` `PorcelainLight` + `Tokens.kt:160-161` `AppTheme.use/reset` — never called; `Glass.kt:18` `glassDrawable` — never called (only `glassLayered` via `View.glass`); `AuroraBackground.kt:107` `renderOnce()` — never called; `Tokens.kt:75-76` `elevation.level2Dp/level3Dp/shadowColor` — only `level1Dp` is ever read (`Components.kt:247`).
- **What is wrong:** Each symbol exists only for a hypothetical future use and adds reading cost + false promise (e.g. `clearSilently` suggests tab switches reset the search, which they don't).
- **Why it matters:** YAGNI — dead code is complexity that never paid for itself and misleads readers about behavior.
- **Fix:** Delete all of the above (and the `ElevationTokens` fields if only level1 is used).
- **Reference:** YAGNI; Ousterhout "the best code is no code".

### 9. LOW — `StatusPill` shows a gray "✓ Available" when the media is available but has no request
- **Where:** `Components.kt:383-397` — `statusLabelOf` returns `"✓ Available"` whenever `media.available` regardless of `status`; then `statusColorOf(null)` (`:376-381`) returns `textSecondary` (gray). So an available item with no request gets a gray success label; the `null` branch of `statusColorOf` is otherwise unreachable.
- **Why it matters:** Color and label are computed from different conditions — a small logic split that produces a wrong (uncolored) pill; the dead `null` branch is confusing.
- **Fix:** In `StatusPill`, derive both from one condition: `val available = media.available; val color = if (available) t.colors.success else statusColorOf(status, t)`; drop the `null` branch of `statusColorOf` (make it non-null) or keep the label/color pair in a single `when`.
- **Reference:** KISS — one condition, one decision; Ousterhout (avoid splitting related logic across functions).

### 10. LOW — Reduce-motion check duplicated in two files
- **Where:** `Components.kt:562-565` (`Skeleton`) and `AuroraBackground.kt:45-48` both query `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` with the same 3-line dance.
- **Fix:** One `fun Context.reduceMotion(): Boolean` in `Motion.kt`, used by both.
- **Reference:** DRY; KISS.

### 11. LOW — `BottomNavBar` reaches into children by index with casts instead of keeping references
- **Where:** `Components.kt:275-277` — `cell.getChildAt(1) as LinearLayout`, `label.getChildAt(0) as TextView`, `label.getChildAt(1) as TextView` — while the views were created locally at `:255-263` and could simply be stored.
- **Why it matters:** Any reordering of `addView` in the cell breaks this with a runtime `ClassCastException` far from the cause.
- **Fix:** Keep `val iconViews = mutableListOf<TextView>()` / `labelViews` alongside `cells`/`pills` when building, and index those in `select()`.
- **Reference:** Ousterhout — keep related things together; KISS.

### 12. LOW — `GradientPoster` word-wrap contains a no-op and over-fitted truncation
- **Where:** `Components.kt:449-458` — `lines[2].removeSuffix("…") + "…"` at `:456`: `removeSuffix("…")` never matches (no line ends with "…"), so it's dead logic wrapped around truncating to 3 lines.
- **Fix:** `val shown = if (lines.size > 3) lines.take(2) + (lines[2].take(11) + "…") else lines` — or simply `lines.take(3)`.
- **Reference:** KISS; delete code that does nothing.

### 13. LOW — `Config.fromJson` re-implements JSON primitive extraction that already exists (privately) in `Clients.kt`
- **Where:** `Config.kt:64-76` hand-rolls `val g = { k -> o[k]?.let { (it as? JsonPrimitive)?.content } ?: "" }` and a cast of the root element; `Clients.kt:22-34` has the same idea as private `str()/dbl()/int()/arr()/obj()` helpers.
- **Fix:** Move the `JsonElement?` accessors to one shared internal file (e.g. `JsonX.kt`) and use them in both `Config` and all clients.
- **Reference:** DRY; Ousterhout (one home for each piece of knowledge).

### 14. LOW — MainScreen manages the error view through string tags and view removal/re-add
- **Where:** `MainScreen.kt:212-243` (`showError` does `p.root.removeView(p.empty)`, adds `errView`, sets `errView.tag = "error"`) and `:255` (`renderCurrent` does `findViewWithTag<View>("error")?.let { removeView(it); addView(p.empty, …) }`). Also `cache`/`signatures` maps (`:32-33`) grow one entry per distinct query string forever.
- **What is wrong:** Invisible coupling via magic tags; easy to leak a view or leave `empty` detached. The unbounded caches are a slow leak on long sessions with many searches.
- **Fix:** Give `Page` an explicit `error: View? = null` field and one `show(state: State)` function handling loading/content/empty/error visibility; clear `cache`/`signatures` entries for other tabs, or cap map size (e.g. keep only current tab + current query).
- **Reference:** KISS — explicit state over tag-based view juggling; Ousterhout (make the happy path obvious).

### 15. LOW — The placeholder magnet generator has a 7-quality × 4-size-branch matrix for fake data
- **Where:** `Catalog.kt:250-296` (`magnetsFor`): per-type quality lists and a nested `when` mapping each quality prefix to a size range, plus per-type name templating.
- **What is wrong:** ~45 lines of branching to fabricate demo rows that nobody reads closely; the branches encode "realism" edge cases (FLAC vs MP3 sizes, REMUX vs WEB-DL) that placeholder mode doesn't need.
- **Fix:** One quality list, one size formula (e.g. `rnd.nextLong(500MB, 40GB)`), one name template — ~15 lines.
- **Reference:** YAGNI / KISS — simplest thing that works for demo data.

---

## Top 5 changes I would make

1. **Remove the `t: DesignTokens` parameter from every component and read `AppTheme.current` directly; delete `PorcelainLight` and `AppTheme.use/reset`** (finding 1) — biggest readability win in the codebase; kills a dead general-purpose mechanism and the two divergent theme-read paths.
2. **Collapse Prowlarr/Sonarr/Radarr into one shared `ArrClient` base** (finding 2) — removes three copies of the same HTTP plumbing, including the duplicated `rootFolder`/`qualityProfileId`/`post`.
3. **Fix the settings validation so only URL fields are validated as URLs** (finding 3) — one `isUrl` flag; restores the happy path of the most-edited screen.
4. **Replace the hand-rolled TSV+escaping persistence in `RequestStoreCore` with a JSON array** (finding 4) — deletes ~30 lines of fragile format code using the already-present kotlinx-serialization.
5. **Extract one shared status poller used by both `MainScreen` and `RequestsActivity`** (finding 5) — deduplicates the only concurrency-heavy loop in the app.
