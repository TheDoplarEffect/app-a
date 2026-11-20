# Round 1 — Simplicity / Anti-Spaghetti Review (app-a)

**Lens:** over-complicated code, speculative generality, unnecessary edge-case handling, tangled control flow, deep nesting, unclear responsibilities. Simpler alternative preferred, usually *deleting* code.
**Reference:** John Ousterhout, *A Philosophy of Software Design* — cited principles: **"complexity is incremental"**, **deep modules / "it's more important for a module to have a simple interface than a simple implementation"**, **"define errors out of existence"**, the **pass-through variable** anti-pattern, and **"the simplest thing that works."**
**Scope:** `app/src/main/java/com/example/appa/*.kt` (3,229 lines), read-only. Top 10 ranked by pain caused.

---

## Top 10 findings (ranked by pain)

### 1. HIGH — `MainScreen.kt:152-211` `loadItems()`: one 60-line method with two ~90%-identical branches
The browse branch (L163-186) and the search branch (L188-211) duplicate the entire cache/staleness/loading/thread/handler/job-guard dance; only the cache map (`itemsByTab`+`loadedAt` vs `searchCache`) and the repo call (`browse(tab)` vs `search(key)`) differ. Every future change to loading behavior must be made twice, in sync — that is Ousterhout's **"complexity is incremental"** in action: two copies of the same logic that will drift.
**Fix:** collapse to one helper — `fun load(cache: MutableMap<String, Pair<Long, List<Media>>>, key: String, fetch: () -> List<Media>)` — and let each branch be 3 lines: pick key + fetcher, call `load(...)`. The tri-state `showLoading(p, loading, hasItems)` (L139-143) can become `show(page, state)` with a single `enum { LOADING, READY, EMPTY }`.

### 2. HIGH — `MainScreen.kt:34-35, 215-233` `renderCurrent()`: two parallel "did anything change?" caches
`signatureByTab: MutableMap<MediaType, String>` and a separate `searchSignature: String` are selected by `if (query.isBlank())` at L225/L227, and the signature *content itself* changes shape depending on blankness (L219-223). Three pieces of state to keep coherent for an optimization whose benefit is "don't rebuild a ≤30-cell grid."
**Fix:** one map keyed by `"$tab|$query"` (`signatureByKey`), or delete the diffing entirely and rebuild the grid unconditionally — for placeholder (54 items) and live (≤30 results) lists a rebuild is cheaper than the bookkeeping. Simplest thing that works.

### 3. HIGH — `Components.kt` (24 functions, e.g. L44, L48, L52, L62, L88, L166, L178): pass-through `t: DesignTokens = AppTheme.current` on every atom
Every `col/row/lp/AppText/Chip/button/...` takes a `t` parameter and forwards it, but **no call site in the app ever passes anything but the default** (46 reads of `AppTheme.current`, zero non-current themes passed; the only override is in `DesignTokensTest`). This is verbatim Ousterhout's **pass-through variable** anti-pattern: it bloats every signature (violating **"simple interface over simple implementation"**) and forces intermediate methods to be aware of a value they don't use.
**Fix:** delete the `t` parameter from all atoms that never need a non-current theme and read `AppTheme.current` inside the function body. Call sites get shorter too (`AppText(ctx, title, TextStyle.BODY)`).

### 4. HIGH — `DetailActivity.kt:91-102`: background fetch → `recreate()` to refresh one screen, plus a racy re-read
When details are missing, the code spawns a thread, then calls `recreate()` (L98) — re-running the whole `onCreate`, re-firing the magnet search and rebuilding every view — just to swap in an overview/poster. The comment at L95-97 admits this replaced "the old recreate-loop" bug, i.e. the guard (`d.id == mediaId && d != media && !isFinishing`) is a band-aid on a tangled control flow. Additionally L102 (`media = AppState.mediaById(mediaId) ?: media`) executes immediately after `Thread { ... }.start()` — the thread hasn't run, so the re-read is racy dead weight.
**Fix:** don't `recreate()`. After the fetch, update the affected views in place (the meta card text and hero) on the main thread. Delete L102. Same for the three other `recreate()` calls (L176, L196, L202) after request/cancel — replace with a targeted re-render of the actions row (`renderActions` already exists and is rebuild-safe).

### 5. MEDIUM-HIGH — `LiveRepository.kt:51-55`: blocking network I/O inside the constructor
`init { if (cfgN.hasProwlarr) audiobookIndexerIds = prowlarr.indexerIdByName("audiobook")... }` performs a synchronous HTTP call (25s connect timeout in `Http.kt:28`) in the constructor. `LiveRepository` is constructed on the **main thread** in `AppState.init()` (called from every `Activity.onCreate`) and in `AppState.saveConfig`, so on Android this throws `NetworkOnMainThreadException`, which the inner `runCatching` silently swallows → `audiobookIndexerIds` is always `""` in practice. Unclear responsibility (a repository doing I/O at construction) hiding a dead code path.
**Fix:** resolve the indexer id lazily on first `magnetsFor()` call (already on a background thread in `DetailActivity.loadMagnets`), cached in a `var`; or drop the audiobook-indexer filter entirely and search all indexers. Constructor becomes side-effect free.

### 6. MEDIUM — `LiveRepository.kt:57-65` `pingAll()`: tautological success checks
`"prowlarr" to runCatching { prowlarr.search("test", "").isNotEmpty() || true }` and `runCatching { sonarr.seriesStates(); true }` — the `|| true` and `; true` make the expression always-true-if-no-exception, so the `.isNotEmpty()` / result value is dead. This is an edge case handled for its own sake.
**Fix:** `"prowlarr" to (cfgN.hasProwlarr && runCatching { prowlarr.search("test", "") }.isSuccess)` (same for sonarr/radarr) — or better, give Prowlarr/Sonarr/Radarr a real `ping()` like `JellyfinClient.ping()` and call it, defining the error out of existence at the client level.

### 7. MEDIUM — `Catalog.kt:145-180` `RequestStoreCore`: hand-rolled TSV serialization + "legacy format" compat
`persist()`/`reload()` manually escape/unescape `\t`/`\n` per field, and `reload()` accepts `p.size >= 5` with `external = if (p.size > 5) p[5] else ""` — a "legacy 5-field line" format that exists only because one test (`LiveParsingTest.kt:287`) writes it. No real migration is ever needed.
**Fix:** `kotlinx.serialization` is already a dependency — persist as one JSON line (`Json.encodeToString(List<Request>)` needs a serializer, or keep it manual but JSON-encode each field: `JsonPrimitive(s).content`). Drop the 5-field branch; require all 6 fields, delete unparseable lines silently (**"define errors out of existence"**).

### 8. MEDIUM — `Components.kt:405-421` `GradientPoster.onDraw`: hand-rolled canvas word-wrap for a placeholder
~15 lines implementing line-breaking (14-char limit, max 3 lines, font-size per line count, baseline math) — including L417 `lines[2].removeSuffix("…") + "…"`, which guards against a "…" character that **can never occur** because titles never contain it. This is speculative edge-case handling inside the most fiddly drawing code in the app.
**Fix:** replace the custom `View` with a `TextView` (`maxLines = 3`, `ellipsize = END`, centered, bold) over the gradient — Android does the wrapping — or simply `canvas.drawText(title.take(28) + "…", ...)`. Delete the wrap loop.

### 9. MEDIUM — `Components.kt:262-283` `BottomNavBar.select()`: alpha-sniffing guard + index-based child access to break a callback cycle
`if (i == selected && pills.any { it.alpha > 0.5f }) return` exists only because `nav.onSelect → pager.goToPage → onPageSelected → nav.select` would otherwise recurse; the guard reads animation state to detect it. Inside the loop, children are reached via `cell.getChildAt(1)`, `label.getChildAt(0/1)` — positional casts that break silently if the layout changes.
**Fix:** make `select` invoke `onSelect(i)` only when `i != selected` (that alone breaks the cycle — delete the alpha check), and keep direct references (`private val labels = mutableListOf<Pair<TextView, TextView>>`) instead of `getChildAt` casts.

### 10. MEDIUM — Dead / speculative code scattered across 10 files (delete, don't maintain)
Per **"the simplest thing that works"** and "complexity is incremental" (each item is small; together they are noise that future readers must triage):
- `Tokens.kt:122-153` `PorcelainLight` (whole second theme) + `Tokens.kt:156-159` `AppTheme.use/reset` — never used by the app, only by `DesignTokensTest` to "prove swappability". Delete theme + mutators; keep `AppTheme.current` as a `val`.
- `Motion.kt:24, 102-108` `LinearOutSlowIn`, `FastOutLinearIn` (used only by `crossfadeOut`), `crossfadeOut` itself — zero call sites.
- `Glass.kt:18-27` `glassDrawable` — zero call sites (`glass()` uses `glassLayered`).
- `Catalog.kt:20-22` `MediaType.fromSlug`, `Catalog.kt:109-116` `RequestStoreCore.setExternal` (test-only), `Clients.kt:208` `ProwlarrClient.magnetOf`, `SwipePager.kt:31` `pageView`, `Components.kt:329-332` `SearchField.setTextSilently`, `AuroraBackground.kt:99` `renderOnce`.
- `Density.kt:10-11` `dpF` / `sp` — zero call sites.
- `Tokens.kt:73-77` `ElevationTokens` — all three level fields + `shadowColor` are never read (the one elevation in the app is hardcoded `ctx.dp(3)` at `Components.kt:241`).
- `Clients.kt` `RawTorrent.indexerId` / `publishDate` (L190-202) — parsed, never read.
- `AppState.kt:7` `val catalog: Catalog = Catalog` — pointless wrapper around an `object`; use `Catalog` directly.
**Fix:** delete the list above (adjust the two tests that reference `PorcelainLight`/`use`/`setExternal`).

---

## Additional findings (below top-10 pain)

- **MED** `Glass.kt:38` — `withAlpha(if (darkBg) 0xFFFFFFFF.toInt() else 0xFFFFFFFF.toInt(), ...)`: both branches identical. Delete the `if`.
- **MED** `Http.kt:49-52` — `if (loc.startsWith("magnet:")) return loc; return loc`: both branches return the same value; the magnet-vs-torrent distinction is cosmetic. Collapse to `return loc` (keep the non-redirect fallback `return downloadUrl`).
- **MED** `Components.kt:515-521` `Skeleton` — `when (layoutParams)` with three branches that all build the same `width × dp(hDp)` params. Just assign `ViewGroup.LayoutParams(w, ctx.dp(hDp))`.
- **LOW-MED** `SwipePager.kt:69-79 vs 96-101` — `ACTION_DOWN` initialization (downX/downY/lastX, `scroller.forceFinished`, VelocityTracker obtain) is duplicated verbatim in `onInterceptTouchEvent` and `onTouchEvent`. Extract `private fun onDown(ev)`.
- **LOW-MED** `Clients.kt:176-236 vs 271-305` — `api()`, `post()`, `rootFolder()`, `qualityProfileId()` are copy-pasted between `SonarrClient` and `RadarrClient` (~40 duplicated lines). Extract a shared `ArrClient(baseUrl, apiKey, http)` base — a deep module with a simple interface.
- **LOW-MED** `DetailActivity.kt:117-131` — hand-rolled chip wrapping: `chips.take(6)` + duplicated `forEachIndexed` block + `if (chips.size > 6)` second row. Extract `fun chipRow(chips: List<String>)` and loop `chips.chunked(6)`; note chips beyond row 2 are currently dropped silently if the wrap were ever generalized.
- **LOW** `Components.kt:106-142` — badge `Chip` construction duplicated between `CircleIconButton` and `setBadge`. Extract `private fun badgeChip(ctx, n, t)`.
- **LOW** `MainScreen.kt:44-66` + `RequestsActivity.kt:61-80` — the ticker pattern (`Runnable` that spawns a `Thread` then posts back, with a `loadJob` counter guard in MainScreen only) is duplicated across two screens with different intervals (5s/1s) chosen by `if (liveMode)` inside `run()`. Extract one `StatusTicker(handler, live: () -> Boolean, onChanged: () -> Unit)`.
- **LOW** `Clients.kt:112-130` `TmdbClient.toMedia` — two separate `when (type)` blocks (title, then date) with unreachable `else -> return null` / `else -> ""` branches for AUDIOBOOKS (callers already return early). Merge into one `when` returning a pair.
- **LOW** `Components.kt:283-300` `SearchField` — `handler` is declared *after* `root`, whose initializer closes over it (works only because the watcher fires later); `pending!!` after a null-check-then-assign. Hoist `handler`/`pending` above `root` and use a nullable local.
- **LOW** `PagerMath.kt:22-33` — `pageCount <= 0` / `pageWidth <= 0` degenerate guards plus the `flingThresholdPxS` default parameter exist for unit tests; the pager always has 3 pages and positive width once laid out. Keep the two-line clamp, drop the rest if tests are simplified.

## Ridiculous edge cases that don't need handling (explicitly flagged)

1. `Http.kt:49-50` — magnet-vs-torrent `if` with identical `return loc` branches.
2. `Glass.kt:38` — `if (darkBg) 0xFFFFFFFF else 0xFFFFFFFF`.
3. `LiveRepository.kt:59-61` — `.isNotEmpty() || true` and `; true` tautologies in `pingAll`.
4. `Components.kt:417` — `removeSuffix("…") + "…"` guarding a character that never occurs in titles.
5. `Catalog.kt:159-164` — "legacy 5-field line" parsing; only a synthetic test writes that format.
6. `Components.kt:515-521` — three-branch `when` whose arms are identical.
7. `LiveRepository.kt:146-150` — `toIntOrNull() ?: -1` + `firstOrNull { it.first == id }` + `st?.third ?: false`: three layers of "maybe the id isn't there" for an id the app itself just wrote.

## Clean areas (one line each)

- `PagerMath.kt` as a whole: small, pure, well-named — a model deep module (only the test-pinned degenerate guards are arguable).
- `Clients.kt` JSON helpers (`str/dbl/int/arr/obj`, L17-32): compact and genuinely hide parsing complexity behind a simple interface — keep.
- `SecureStore.kt`: single responsibility (encrypt/decrypt one config blob), no speculative options — clean.
- `Config.kt` `normalized()`/`liveReady`: small, obvious, no edge-case pile-up — clean.
- `ImageLoader.kt`: cache-hit fast path + tag guard is the right amount of code for flicker-free posters — clean.
- `SettingsActivity.kt` `field()` local builder + `collect()`: straightforward, no nesting — clean.
- `Insets.kt` `padSystemBars`: captures original padding once, single listener — clean.
- `LiveStatus.compute` (`LiveRepository.kt:23-30`): a pure truth table — exactly the right shape; the tests pin it.
