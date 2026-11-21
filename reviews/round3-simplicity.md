# Round 3 — Simplicity lens (Ousterhout / KISS / YAGNI)

Scope: `/home/dev/app-a/app/src/main/**` only. References: Ousterhout, *A Philosophy of Software Design* (esp. "taming complexity": one method = one job, delete speculative generality, "it's more important for a module to have a simple interface than a simple implementation"); KISS; YAGNI.

---

## Findings (ranked)

### 1. HIGH — `MainScreen.loadItems()` is five jobs in one 50-line method
**File:** `MainScreen.kt:233-284`
**What:** One method handles (a) the placeholder path, (b) cache lookup, (c) TTL freshness check, (d) stale-while-revalidate rendering, (e) worker spawn + job-counter race guard + error rendering. Control flow jumps between `cache`, `loadJob`, `attached`, `spawnWorker`, `showLoading`, `showError`.
**Why it matters:** Ousterhout's core rule — a method should do one thing and do it completely; deep methods with several responsibilities are where bugs hide (e.g. the placeholder path bypasses `loadJob` entirely, so a late placeholder render can't be cancelled the way a live one can). Every new "state" (loading/empty/error/stale) adds a branch here.
**Fix:** Split into `loadPlaceholder(p, tab, q)` and `loadLive(p, tab, q, key)`; keep a single tiny `runLoad(job: Int, block: () -> Unit)` that owns the `loadJob`/`attached` guard. Each function then reads top-to-bottom.

### 2. HIGH — Process-wide companion cache keyed `"tab|query"` with no mode in the key and no size bound
**File:** `MainScreen.kt:56-57, 194, 227-231, 344-350`
**What:** `cache`/`signatures` live in a `private companion object` (global mutable state), keyed only by `"$tab|$q"`. Every distinct search query adds an entry forever (only time-based eviction after 5 min); and because the key omits live/placeholder mode, toggling live mode in Settings fires `onConfigChanged → loadItems()`, which finds the *placeholder* results in `cache[key]` and renders them instantly as if they were live data before the fetch completes.
**Why it matters:** Global mutable state + parallel `signatures` map that must be kept in sync (`evictCache` removes signature keys not in cache) is exactly the "hidden coupling" complexity Ousterhout warns about; the mode-blind key is a real correctness wart, not just style.
**Fix:** Include the mode in the key (`"${AppState.liveMode}|$tab|$q"`) or simply clear both maps in the `onConfigChanged` callback; cap the map (e.g. keep only the 8 most-recent keys, or a plain `LinkedHashMap` with removeEldestEntry).

### 3. MEDIUM — `StatusPoller`'s "in-flight guard" doesn't guard the slow part
**File:** `AppState.kt:90-113` (guard at 94, released at 109-110)
**What:** The doc comment promises "slow *arr refreshes never pile up", but `busy.set(false)` runs in `finally` *immediately after the network thread is spawned*, so the next 5s tick starts another `refreshStatuses` while the previous one is still running. The guard only covers the synchronous `store.tick()`.
**Why it matters:** Complexity that doesn't deliver its advertised behavior is worse than no guard (KISS: don't build a mechanism that lies). Slow *arr servers → overlapping Jellyfin/Radarr/Sonarr scans every tick.
**Fix:** Move `busy.set(false)` into the `handler.post { ... }` callback after `onTick(...)` (and keep the early-return reschedule), so the flag spans the whole refresh. Or drop the flag and just let the 5s period self-throttle with a `@Volatile var refreshRunning` checked at tick start.

### 4. MEDIUM — Mode-banner text/logic duplicated verbatim in two places
**File:** `Components.kt:644-654` (`ModeBanner`) vs `MainScreen.kt:172-182` (`refreshChrome`)
**What:** The exact strings `"🌐 Live — TMDB · Sonarr/Radarr · Prowlarr · Jellyfin"`, the warning variant, and the contentDescription logic are copy-pasted into `refreshChrome` so the banner can be re-styled in place.
**Why it matters:** Duplicated logic rots — change the copy in one place and the two banners diverge. (Ousterhout: pull related functionality together.)
**Fix:** Make `ModeBanner` return a handle, e.g. `fun ModeBanner(...): TextView` plus `fun TextView.setModeBanner(live: Boolean, t)` in Components.kt; `refreshChrome` calls `banner.setModeBanner(AppState.liveMode, t)` and the strings exist once.

### 5. MEDIUM — Poster construction duplicated in three screens
**File:** `Components.kt:492-503` (`PosterCard`), `DetailActivity.kt:120-130` (`buildHero`), `RequestsActivity.kt:105-115` (`render`)
**What:** All three do the same `if (media.posterUrl != null) ImageView + ImageLoader.load else GradientPoster`, each with its own roundedClip/contentDescription/FrameLayout wrapper.
**Why it matters:** Three copies of the same decision; a fix (e.g. placeholder color, fallback) must be applied three times.
**Fix:** One helper in Components.kt: `fun posterView(ctx, m, w, h, radiusDp, t): FrameLayout` containing the ImageView/GradientPoster choice + clip + contentDescription; all three call sites shrink to one line.

### 6. MEDIUM — Thread-registry boilerplate (`spawnWorker` + `workers` + synchronized interrupt) copied three times
**File:** `MainScreen.kt:59-63` + `158-160`, `DetailActivity.kt:92-96` + `87-90`, `AppState.kt:85` + `130-133` (`StatusPoller`)
**What:** Each class re-implements "keep a list of worker threads, interrupt them all on destroy".
**Why it matters:** Triplicated lifecycle plumbing is a maintenance hazard (forget it in the next screen and you leak threads) — classic KISS violation; the app already has ~3 places that must stay in sync.
**Fix:** One tiny shared helper, e.g. `class WorkerRegistry { fun run(block: () -> Unit); fun shutdown() }` (a synchronized list + `runBg` extension on it), instantiated per screen/poller.

### 7. MEDIUM — `GradientPoster.onDraw` hand-rolls a text-layout algorithm
**File:** `Components.kt:463-475` (word wrap: `cand.length <= 14`, cap 3 lines, `take(11) + "…"`, font-size switch on line count, manual baseline math) plus the luminance polarity switch at `423-427` (`0.183` threshold).
**What:** A custom word-wrap/truncate/font-fit algorithm inside a `View.onDraw`, with magic numbers, instead of using the platform's text layout.
**Why it matters:** This is the definition of a "ridiculous edge case" hand-rolled: long titles, single-word overflow (`cur = word` can exceed 14 chars and is never re-split), and locale/bidi text are all handled worse than `TextView`. ~30 lines of drawing code exist only to avoid putting a `TextView` in a `FrameLayout`.
**Fix:** Make the poster a `FrameLayout` with the gradient as background and a centered `TextView` (`maxLines = 3`, `ellipsize = END`, gravity center) on top; delete the wrap loop, line math, and both Paints. Keep the scrim `View` + luminance-based text color if wanted, or just use the existing scrim chip approach.

### 8. MEDIUM — Three parallel hand-rolled JSON codecs for three data classes
**File:** `Config.kt:52-74` (`AppConfig.toJson/fromJson`), `Catalog.kt:96-125` (`MediaSnapshot.toJson/fromJson`), `Catalog.kt:208-267` (`RequestStoreCore.reload/persist`)
**What:** Three bespoke `buildJsonObject`/`parseToJsonElement` pairs, each with stringly-typed reads (`o["requestedAt"].str().toLongOrNull() ?: 0L`, `o["liveMode"].str() == "true"`) — a hand-written, type-unsafe serialization layer duplicating what kotlinx-serialization's `put`/`get` already does.
**Why it matters:** Every new field must be added in two places per class; the stringly conversions silently coerce malformed data to defaults. (YAGNI/KISS: the app already depends on kotlinx-serialization.)
**Fix:** Add `JsonElement?.long(def)` (and reuse `int`/`dbl`/`str`) to `JsonX.kt`, use kotlinx's `put(key, value)` overloads for writing (they exist for String/Long/Boolean/Double), and write one tiny generic `fun <T> JsonObject.read(...)`-style helper or simply keep the accessors but delete the bespoke builders. `MediaSnapshot` itself is a subset of `Media` — consider persisting `Media` fields directly and dropping the duplicate class.

### 9. MEDIUM — `refreshChrome` reaches into `AppTopBar`'s view hierarchy by child index
**File:** `MainScreen.kt:172-173`
**What:** `(topBar.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout` then `getChildAt(1) as? TextView` — the caller depends on the exact internal child order of `AppTopBar`.
**Why it matters:** Ousterhout: information hiding fails here — add one icon to `AppTopBar` and this silently updates the wrong view (the `as?` makes it fail soft). It's spaghetti coupling between organism and atom.
**Fix:** Have `AppTopBar` return a small handle, e.g. `data class TopBarHandle(val root: LinearLayout, val subtitle: TextView?)` or tag the subtitle (`tag = "subtitle"`) and use `topBar.findViewWithTag("subtitle")`; `refreshChrome` then just sets `sub?.text`.

### 10. MEDIUM — `BottomNavBar`: four parallel lists + `initialized` flag + `root.post` hack
**File:** `Components.kt:233-296`
**What:** `cells`, `pills`, `icons`, `labels` are kept as four index-aligned mutable lists; `select()` early-returns on `i == selected && initialized`; initial selection is deferred via `root.post { select(initial, animate = false); initialized = true }`.
**Why it matters:** Index-aligned parallel collections are a replace-with-one-struct smell; the `initialized`/`post` dance exists only to animate the first selection and makes `select` behave differently before/after layout — subtle control flow for no user benefit.
**Fix:** One `private data class Cell(val root, pill, icon, label)` in a single `List<Cell>`; call `select(initial, animate = false)` directly at the end of `init` (no `post` needed — nothing in `select` depends on measured width), and drop `initialized` (use `if (i == selected && cells.isNotEmpty() && hasBeenSelectedOnce)` or simply always run the loop; it's 3 items).

### 11. MEDIUM — Two magnet types with a conversion shim
**File:** `Catalog.kt:50` (`Magnet`), `LiveRepository.kt:10` (`LiveMagnet`), shim at `DetailActivity.kt:263-266`
**What:** Placeholder mode uses `Magnet` (id/mediaId/addedAt/magnetUri…), live mode uses `LiveMagnet` (downloadUrl/magnetUri), and `DetailActivity.loadMagnets` maps one to the other field-by-field with `downloadUrl = ""`.
**Why it matters:** Duplicated model + adapter shim (Ousterhout: "pull related things together"); the empty-string sentinel for `downloadUrl` is a fake value waiting to be dereferenced.
**Fix:** Keep a single `Magnet` data class (add `downloadUrl: String = ""` to it), have `LiveRepository.magnetsFor` return it, and delete the mapping in `DetailActivity`.

### 12. LOW — Dead / speculative code (YAGNI)
**Files:** `Tokens.kt:127-161, 163-166` (`PorcelainLight`, `AppTheme.use/reset` — referenced only from `DesignTokensTest`, never from production), `Motion.kt:32` (`EmphasizedPath` — zero references anywhere), `AuroraBackground.kt:104` (`renderOnce()` — zero references), `Tokens.kt:205-211` (`compositeOver` — test-only).
**What:** A second theme + runtime theme-swap machinery exist only to "prove swappability"; three helpers are never called in main.
**Why it matters:** YAGNI — unused generality costs reading time and test surface; if a light theme is ever needed it's a 30-line addition, not a redesign.
**Fix:** Delete `EmphasizedPath`, `renderOnce`, `compositeOver` (or move to test sources); either delete `PorcelainLight`/`use`/`reset` or keep only if a settings toggle is actually planned — decide, don't hedge.

### 13. LOW — Legacy TSV migration format kept "for one release"
**File:** `Catalog.kt:231-245` (`RequestStoreCore.reload` legacy branch)
**What:** A second parse format (TSV with `\\t`/`\\n` unescaping) is maintained alongside the JSON format, gated by `text.trimStart().startsWith("[")`.
**Why it matters:** YAGNI / dead-weight complexity: two formats to maintain and test forever for a migration that either already happened or never will. The format sniffing itself is fragile.
**Fix:** Drop the TSV branch (and its tests) in the next release; if a migration is genuinely still needed, do it once, offline, and delete immediately after.

### 14. LOW — Sonarr/Radarr fetchers are near-identical clones; Prowlarr duplicates the ArrClient plumbing
**File:** `Clients.kt:271-287` vs `310-326` (`seriesStates/queueSeriesIds` vs `movieStates/queueMovieIds`), `Clients.kt:158-166` (Prowlarr's own `url`/`api`) vs `211-215` (ArrClient's identical `url`/`api`)
**What:** Two pairs of methods differ only in endpoint, field name, and result type; and Prowlarr re-implements the URL-builder + retrying-GET wrapper that `ArrClient` already has.
**Why it matters:** Duplication means the retry/URL logic can drift between Prowlarr and the *arr clients.
**Fix:** On `ArrClient` add generic `fun <T> states(path: String, map: (JsonObject) -> T): List<T>` and `fun queueIds(path: String, field: String): Set<Int>` and let Sonarr/Radarr one-line them; either make `ProwlarrClient` extend the same base (it needs GET+retry but not POST) or extract a 10-line `ApiGet` helper both use.

### 15. LOW — `LiveRepository.refreshStatuses` does per-request linear scans and hides a cache mutation
**File:** `LiveRepository.kt:142-180` (`firstOrNull { it.radarrId == id }` at 164-165), `183-195` (`markAvailability` writes `mediaCache` as a side effect)
**What:** For each request it linearly scans the whole Radarr/Sonarr library list; and `markAvailability` both returns a copy *and* mutates the shared `mediaCache` map as a side effect of a search call.
**Why it matters:** O(n×m) for no reason (libraries can be thousands of entries), and hidden side effects in a "getter-ish" function are exactly the kind of complexity that bites later (KISS: one clear effect per call).
**Fix:** Build `val radarrById = radarrStates.associateBy { it.radarrId }` (and sonarr) once before the loop; make `markAvailability` a pure function returning the copy, and let callers that want caching call `remember(...)` explicitly (they already do in MainScreen).

### 16. LOW — Settings form: local `field()` builder + 10 `!!` lookups + per-field anonymous TextWatcher
**File:** `SettingsActivity.kt:53-90` (`field(...)` with nested `TextWatcher`), `105-118` (`collect()`)
**What:** A local function capturing `form`/`fields` builds 9 fields with a bespoke error-label + watcher each, and `collect()` re-reads each key with `fields["…"]!!`.
**Why it matters:** Verbose but more importantly the URL validation is wired per-field instead of once at save time — the `AppConfig.urlError` check could run in `save()`/`test()` on the collected config, deleting the watcher and error views entirely.
**Fix:** Keep the simple loop that adds `FormField`s keyed by name; validate URLs once in `save()` using the existing `urlError` on the collected `AppConfig` (show a Toast naming the offending field); `collect()` becomes `AppConfig` built from `fields.mapValues { it.value.text.toString() }` via a small ordered key list.

### 17. LOW — `navInitiated` flag to distinguish nav-tap from swipe
**File:** `MainScreen.kt:46, 107-111, 125`
**What:** A boolean is set in the nav callback and cleared in the pager callback so `onPageSelected` knows whether the change came from a nav tap (to scroll-to-top + hide keyboard).
**Why it matters:** Two callbacks mutating shared state to pass a one-bit message — a mini state machine for "did the user tap the nav".
**Fix:** Give `SwipePager.goToPage(i, animate, fromNav: Boolean = false)` and pass it through to `onPageSelected(i, fromNav)`; delete `navInitiated`. (Or simply always scroll to top on page change — the current distinction is barely user-visible.)

---

## Top 5 changes

1. **Split `MainScreen.loadItems()`** into `loadPlaceholder` / `loadLive` with one shared job-guard helper, and **fix the cache key to include live/placeholder mode** (or clear the companion cache on config change) + bound its size — kills the biggest complexity cluster and a real stale-data flash. (`MainScreen.kt:233-284, 344-350`)
2. **Fix `StatusPoller`'s busy flag** so it is released only after the async refresh callback runs — the guard then actually prevents piled-up *arr refreshes as documented. (`AppState.kt:90-113`)
3. **Delete the hand-rolled text layout in `GradientPoster.onDraw`** — put a `TextView` (maxLines=3, ellipsize) over the gradient and remove ~30 lines of wrap/truncate/font-fit math. (`Components.kt:463-475`)
4. **Deduplicate the repeated building blocks:** one `posterView(...)` helper for the 3 poster sites, one `setModeBanner(...)` for the 2 banner copies, one `WorkerRegistry` for the 3 thread registries, one `Magnet` type instead of `Magnet`+`LiveMagnet`. (`Components.kt:492`, `DetailActivity.kt:120`, `RequestsActivity.kt:105`, `MainScreen.kt:172`, `MainScreen/DetailActivity/AppState` workers, `DetailActivity.kt:263-266`)
5. **Remove YAGNI/dead code and simplify the nav-bar:** delete `EmphasizedPath`, `renderOnce`, `compositeOver`, the unused `PorcelainLight`/`AppTheme.use` (or commit to them), and the legacy TSV branch in `RequestStoreCore.reload`; collapse `BottomNavBar`'s four parallel lists + `initialized` flag into one list of cells with a synchronous initial `select`. (`Motion.kt:32`, `AuroraBackground.kt:104`, `Tokens.kt:127,205`, `Catalog.kt:231-245`, `Components.kt:233-296`)
