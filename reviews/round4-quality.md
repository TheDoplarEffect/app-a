# Round 4 — Code Quality Review (LENS: Kotlin Coding Conventions · Effective Kotlin · Ousterhout)

- Scope: `app/` only, master HEAD `33ef6e2` ("v4.4: fix smart-cast on mutable redirect body"), repo `TheDoplarEffect/app-a`. Local checkout `/home/dev/app-a` was missing, so master HEAD was cloned and reviewed.
- References:
  - [KCC] Kotlin Coding Conventions — https://kotlinlang.org/docs/coding-conventions.html (naming, functions, style)
  - [EK] Effective Kotlin, M. Moskala (2nd ed.) — chapters: Error handling ("do not swallow exceptions"), Mutability ("limit mutability / avoid shared mutable state"), Reusability (DRY / extract repeated logic), Readability
  - [OUS] Ousterhout, *A Philosophy of Software Design* — ch. 4–5 (deep modules, information hiding & leakage), ch. 8 (pull complexity downward), ch. 10 (define errors out of existence vs. masking them)

Findings are ranked by severity. Line numbers refer to `app/src/main/java/com/example/appa/`.

---

## 1. HIGH — `SettingsActivity.kt:158-171` — double `Thread.start()` crashes "Test connection"
`WorkerScope.spawn { ... }.start()` calls `start()` on a thread that `WorkerScope.spawn` (`AppState.kt:144-150`) **already started** (`th.start()` at `AppState.kt:148`). Java's `Thread.start()` throws `IllegalThreadStateException` on a second call, so tapping "Test connection" throws synchronously on the main thread and crashes the app.
- Why: misuse of the registry API; the returned `Thread` is already running.
- Fix: delete the trailing `.start()` (just `WorkerScope.spawn { ... }`). Add a unit/robolectric test that taps the button, or make `spawn` return `Unit` so this class of bug can't compile.
- Ref: [OUS] ch. 10 (errors should surface, not crash the user); [KCC] — functions (API shape should make misuse impossible).

## 2. HIGH — `ImageLoader.kt:21-24, 35` — bounded queue + default `AbortPolicy` → `RejectedExecutionException` on the main thread
The executor is `ThreadPoolExecutor(2, 2, 30s, LinkedBlockingQueue(24))` with the default abort handler. `executor.execute { ... }` is called from `load()`, which runs on the main thread. Once 2 tasks run + 24 are queued (a fast scroll through a 3-column grid of ~24+ posters easily exceeds that), the 27th `execute` throws `RejectedExecutionException` and crashes the app.
- Why: a bounded queue is good for memory, but the default rejection policy throws in the caller; the caller is the UI thread.
- Fix: pass a `ThreadPoolExecutor.CallerRunsPolicy()` (or wrap `execute` in `try/catch (RejectedExecutionException)` and drop the load), e.g. `ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, LinkedBlockingQueue(64), ThreadPoolExecutor.CallerRunsPolicy())`.
- Ref: [OUS] ch. 10 (define errors out of existence — a queue policy must not produce an unhandled exception); [EK] Error handling.

## 3. MEDIUM — `LiveRepository.kt:147-150, 173-175` — swallowed backend failures silently downgrade request status
`refreshStatuses` wraps every backend call in `runCatching { ... }.getOrDefault(emptyList()/emptySet())`. If Radarr/Sonarr/Jellyfin fail transiently, `hasFile=false`, `inQueue=false`, `inJellyfin=false` → `LiveStatus.compute` returns `PENDING`, and line 175 writes that back via `store.updateStatus` — a request that is actually `AVAILABLE` regresses to `PENDING` (and is persisted). The poller even reports success because `res.isFailure` refers to the outer wrapper, not the individual calls.
- Why: exceptions are masked, so missing data is indistinguishable from "not yet downloaded". This is exactly the silent-corruption pattern.
- Fix: capture per-backend success (`val radarrOk = ...isSuccess`); skip status recomputation for requests whose backend kind failed this round (keep last known status). Never downgrade `AVAILABLE` unless a backend positively reports the file gone.
- Ref: [OUS] ch. 10 (don't mask errors — they resurface as wrong behavior); [EK] Error handling, "do not swallow exceptions".

## 4. MEDIUM — `DetailActivity.kt:220-243` (`doRequest`, live path) — check-then-act race: *arr write happens before the duplicate guard
In live mode the code calls `AppState.repo?.request(media)` (which POSTs to Radarr/Sonarr, `Clients.kt:225-262`) **before** `AppState.store.create(...)`. If `create` returns `null` (already requested — possible because the earlier `AppState.requestFor` check at line 226 is a separate, non-atomic read on another thread), the *arr entry was already added and the UI toasts "Already requested" — the opposite of the comment's promise "never double-add in *arr". The orphaned *arr entry is never cleaned up.
- Why: the invariant is enforced in the wrong order; the side effect precedes the guard.
- Fix: create the local request first (or reserve it), then call `repo.request`, then `store.setExternal(id, external)`; on *arr failure delete the local request (compensating action) and re-throw to the toast. That makes the store the single source of truth for "already requested".
- Ref: [OUS] ch. 10 (design it twice / make invariants structural); [EK] Error handling.

## 5. MEDIUM — `MainScreen.kt:357-359` — child-index casts into `EmptyState` internals (leaky abstraction)
`renderCurrent` does `p.empty as LinearLayout` then `(e.getChildAt(1) as TextView).text = ...` / `getChildAt(2)`. This couples `MainScreen` to the exact child order of the `EmptyState` atom (`Components.kt:556-572`). Reordering/adding a view in `EmptyState` silently breaks the main grid's empty-state text.
- Why: information leakage between layers — the screen reaches into a component's private structure.
- Fix: have `EmptyState` return a small holder, e.g. `class EmptyState(val root: LinearLayout, val title: TextView, val message: TextView)`, or tag the views (`tag = "empty_title"`) and use `findViewWithTag` (same pattern already used for `"subtitle"`/`"badge"` in `Components.kt`).
- Ref: [OUS] ch. 5 (information leakage); [EK] Readability/hiding internals.

## 6. MEDIUM — Inconsistent error style across the HTTP/client layer; enrichment paths swallow everything
The code deliberately throws on the write path (`ArrClient.rootFolder/qualityProfileId/post`, `Clients.kt:200-226` — good), but `TmdbClient.details` ends with `catch (e: Exception) { media }` (`Clients.kt:75`), and `DetailActivity.kt:66` uses `runCatching { ... }.getOrDefault(media)`. So a 401 from TMDB, a JSON parse error and a timeout are all indistinguishable from "no extra metadata". Meanwhile `Http.realHttp` never throws by design (`Http.kt:69-121`). Three different error contracts (never-throw / throw / swallow) with no documented rule.
- Why: callers cannot decide whether a failure is recoverable; bugs (parse errors) are hidden as "empty data".
- Fix: pick one policy and document it: transport returns `Resp` (never throws); `httpGetJson*` throw `ApiException`; enrichment (`details`) may degrade but must surface the error (e.g. return `Result<Media>` or log + a flag) instead of `catch (Exception)`.
- Ref: [EK] Error handling ("do not swallow exceptions"); [OUS] ch. 10 (masking vs. defining errors out of existence).

## 7. MEDIUM — Screen scaffold duplicated 4× (and dialog-list boilerplate 2×)
`DetailActivity.kt:49-54`, `RequestsActivity.kt:35-41`, `SettingsActivity.kt:33-38`, `MainScreen.kt:70-74` all repeat the same block: `FrameLayout(this).withAurora()` → `col { padSystemBars(...); setPadding(px, ...) }` → `AppTopBar(..., leading = TopBarAction("✕") { finishDropping() })`. The `Collections.synchronizedList(mutableListOf<AlertDialog>())` + dismiss-in-onDestroy pattern is duplicated in `DetailActivity.kt:35, 94-96` and `RequestsActivity.kt:22, 76-78`.
- Why: every new screen copies ~10 lines; a fix (e.g. insets handling) must be applied in 4 places.
- Fix: extract one helper, e.g. `fun Activity.contentScaffold(title: String, t: DesignTokens, body: LinearLayout.() -> Unit): FrameLayout`, and a small `DialogTracker` class for the dialog list.
- Ref: [EK] Reusability (DRY — extract repeated logic); [OUS] ch. 9 ("better together" — remove duplication).

## 8. MEDIUM — Magic numbers scattered despite the "tokens only" design claim
`Components.kt:14-16` claims "components never hard-code values", but raw numbers appear all over the UI layer: `MainScreen.kt:135` (1000 ms), `:198` (`repeat(9)` skeletons), `:235` (`0.18f` inline-error alpha), `:318/327/330` (`dp(8)/dp(4)/dp(16)`); `Components.kt:363` (350 ms debounce), `:535` (400 ms double-tap), `:275/333` (`dp(8)`); `DetailActivity.kt:121` (`dp(300)` hero), `:137` (`dp(140)` scrim), `:171/176` (3 chips per row, `dp(6)`); `AuroraBackground.kt:54` (`24_000` ms), `:96` (`alpha = 52`).
- Why: values that should be design decisions live as unexplained literals; changing one requires hunting through organisms.
- Fix: add size tokens to `DesignTokens` (e.g. `sizes.heroDp`, `sizes.scrimDp`, `sizes.thumbDp`) and named constants (`const val SEARCH_DEBOUNCE_MS = 350`, `const val AURORA_PERIOD_MS = 24_000L`) in a companion/`const` block.
- Ref: [KCC] — naming/style (named constants over magic literals); [OUS] ch. 13 (choose names that carry information).

## 9. MEDIUM — Long functions doing several jobs
`MainScreen.attach` (`MainScreen.kt:61-139`, ~80 lines of view-building + wiring), `MainScreen.loadItems` (`:251-304`, ~54 lines mixing cache policy, placeholder/live branches and rendering), `DetailActivity.onCreate` (`:37-107`, ~70 lines), `Components.PosterCard` (`:484-545`, ~60 lines mixing poster, overlays, labels, click guard).
- Why: each function mixes abstraction levels (policy + view construction + threading), which makes the cache/error logic in `loadItems` hard to verify.
- Fix: decompose — `buildTopBar()`, `buildPages()`, `buildNav()` in `MainScreen`; split `loadItems` into `loadPlaceholder` / `loadLive` (stale-while-revalidate); split `PosterCard` into `posterVisual()` + `posterText()`.
- Ref: [KCC] — Functions (one responsibility, readable length); [OUS] ch. 8 (pull complexity downward into helpers).

## 10. MEDIUM — Mutable shared state held in globals/companion without confinement guarantees
`MainScreen.kt:376-377` keeps process-wide `mutableMapOf` `cache`/`signatures` in the companion, safe only because every access happens to be on the main thread — nothing enforces that (a future worker touching `cache` would corrupt it). Similarly `AppState.onConfigChanged` is a public writable `@Volatile var` lambda (`AppState.kt:22`) that any screen can overwrite, and `TopBarAction.badge` is a `var` (`Components.kt:105`) that is never kept in sync (the badge is updated via view tags in `MainScreen.kt:367-369` instead).
- Why: shared mutable state is the classic source of races; the current code works by convention, not by construction.
- Fix: encapsulate the cache in a small class annotated/documented `@MainThread`, or use `Collections.synchronizedMap`; replace `onConfigChanged` with a listener list (`addOnConfigChangedListener`) or make it `internal set`; make `TopBarAction.badge` a `val` (the view owns the live count).
- Ref: [EK] Mutability ("limit mutability", "avoid shared mutable state"); [OUS] ch. 5.

## 11. LOW — Duplicated literal content: mode-banner text, default poster colors, URL-building plumbing
- `ModeBanner` strings/colors are duplicated verbatim in `MainScreen.refreshChrome` (`MainScreen.kt:176-181` vs `Components.kt:647-653`) — change one and the other lies.
- Default poster colors `"#1f6feb"/"#8250df"` appear in both `Clients.kt:102` (TMDB) and `Catalog.kt:110` (`MediaSnapshot.toMedia`), and `"#953800"/"#e16f24"` in `Clients.kt:133` (Open Library).
- `ProwlarrClient.url/api` (`Clients.kt:161-164`) duplicate `ArrClient.url/api` (`:211-215`).
- Fix: extract `fun modeBannerSpec(live: Boolean, t: DesignTokens): Pair<String, Int>`, a `MediaDefaults.COLOR_1/COLOR_2` constant, and have `ProwlarrClient` share the `ArrClient` plumbing (or a common `url(path, params)` top-level helper).
- Ref: [EK] Reusability (DRY); [KCC] — file organization/single home for shared values.

## 12. LOW — `Catalog.kt:249, 265` — per-instance non-daemon executor thread, never shut down
Every `RequestStoreCore` creates its own `Executors.newSingleThreadExecutor()` (non-daemon threads). In unit tests (each test builds a store, see `RequestStoreCoreTest.memStore`) these threads accumulate and keep the JVM alive; nothing ever calls `shutdown()`.
- Fix: use one shared executor with a daemon thread factory (`Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }`), or expose `close()`/`shutdown()` and call it in tests.
- Ref: [EK] Resource management; [KCC] — avoid resource leaks in long-lived objects.

## 13. LOW — `Components.kt:44, 47` — `col`/`row` take a `t: DesignTokens` parameter that is never used
Both helpers accept `t: DesignTokens = AppTheme.current` but the body ignores it, so ~30 call sites pass a token that does nothing — a misleading signature (callers believe they're theming the layout).
- Fix: remove the parameter (callers already rely on `AppTheme.current` implicitly) or actually use it.
- Ref: [KCC] — meaningful signatures / no dead parameters.

## 14. LOW — `DetailActivity.kt:35, 94-96` — dialog list grows forever
`dialogs` is only appended to (`showDialog`) and cleared in `onDestroy`; dismissed dialogs are never removed, and `onDestroy` re-dismisses already-dismissed dialogs. Harmless today, but it's an unbounded collection in a long-lived object.
- Fix: remove the dialog in an `OnDismissListener` when it's dismissed by any path.
- Ref: [EK] resource/memory hygiene; [OUS] ch. 5 (leakage of state).

## 15. LOW — `DetailActivity.kt:65-72` — live details update only the meta card, the hero stays stale
After the background `details()` fetch, only `renderMeta(d, t)` is called; the hero built at `:61` via `buildHero` (`:116-154`) keeps the old rating/year/poster. The user sees two different ratings on one screen.
- Fix: re-render the hero chips too (extract the hero-text block into a function and update it), or pass the enriched `Media` into a single re-render of the whole content column.
- Ref: [OUS] — consistency of view state; [EK] Readability.

## 16. LOW — `Http.kt:135-150` — `magnetFromDownloadUrl` returns a non-magnet as a "magnet"
The function's contract (and name) says it resolves to a magnet link, but on any non-3xx/non-magnet outcome it returns the original `downloadUrl` — which for Prowlarr carries the API key in the query string. `DetailActivity.kt:305-307` currently guards with `startsWith("magnet:")`, so nothing bad is copied today, but the safety depends on the caller remembering.
- Fix: change the return type to `String?` (null = no magnet) and let the caller produce the error toast; never return the apikey-bearing URL as a "resolved magnet".
- Ref: [OUS] ch. 4-5 (deep module: the abstraction should make the safe behavior the only behavior); ch. 10.

---

## Top 5 changes
1. **`SettingsActivity.kt:171`** — remove the extra `.start()` on the already-started worker thread (instant crash on "Test connection").
2. **`ImageLoader.kt:21-24`** — set `CallerRunsPolicy` (or catch `RejectedExecutionException`) so a burst of poster loads can never throw on the main thread.
3. **`LiveRepository.kt:142-176`** — stop swallowing per-backend failures in `refreshStatuses`; skip (never downgrade) statuses for backends that failed this tick.
4. **`DetailActivity.kt:230-243`** — reorder `doRequest` so the local store decides "already requested" *before* any *arr write, with a compensating delete on failure.
5. **Extract the shared scaffold + banner/colors constants** (`MainScreen.kt:70-74` pattern ×4, `MainScreen.kt:176-181` vs `Components.kt:647-653`, `Clients.kt:102`/`Catalog.kt:110`, `Clients.kt:161-164` vs `:211-215`) — one helper for screen chrome, one source for banner text and default colors, one URL-builder.
