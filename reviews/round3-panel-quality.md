# Round 3 — Triage panelist report (FOCUS: code quality & structure)

Panelist lens: Kotlin conventions, Ousterhout (deep modules, information hiding, truthful comments), Clean Architecture dependency direction, SOLID. All line refs are `app/src/main/java/com/example/appa/`. Proposals deduplicated across the six round-3 reports; "proposed by" lists every report that raised the same change.

## Triage table

| # | change | proposed by | your vote | justification |
|---|--------|-------------|-----------|---------------|
| 1 | StatusPoller: hold `busy` until the async refresh completes; prune finished threads (or single-thread executor) | quality#1, architecture#5, simplicity#3, robustness#1/#10 | APPROVE | `AppState.kt:94-110` releases `busy` in `finally` immediately after `th.start()`, so every 5 s tick spawns overlapping `refreshStatuses` while the comment at :76-79 promises "never pile up"; `workers.add(th)` at :104 is never removed on completion. |
| 2 | Make the delayed `poller.start()` cancellable in `MainScreen.detach()` | robustness#2 | APPROVE | `MainScreen.kt:135` posts an anonymous lambda that `detach()` (:157-161) never removes (`cancel()` only removes the tick runnable, `AppState.kt:126-132`), so polling can start after onDestroy and leak the Activity via the Handler. |
| 3 | Extract one shared WorkerScope (spawn/cancelAll) for MainScreen, DetailActivity, StatusPoller, Settings test thread | quality#10, simplicity#6, robustness#7 | APPROVE | Identical `synchronizedList`+`Thread`+interrupt blocks at `MainScreen.kt:59-64`, `DetailActivity.kt:37,101-106`, `AppState.kt:87,104`, `SettingsActivity.kt:149-160`; the poller copy has already drifted (row 1), proving the duplication cost. |
| 4 | Make worker cancellation actually stop work (executor + shutdownNow + cooperative cancelled flag) | robustness#8 | MODIFY | `Thread.interrupt()` does not unblock `HttpURLConnection` reads (`Http.kt:72-73`), so adopt the WorkerScope executor with `shutdownNow()` and check a `cancelled` flag between repository steps; skip deep `disconnect()` plumbing as a follow-up, not this change. |
| 5 | Build `RequestStoreCore` (first prefs read) and pre-warm `Catalog` inside the existing background thread in `AppState.init` | robustness#3/#14 | APPROVE | `AppState.kt:33-38` constructs the store on the main thread and `init { reload() }` (`Catalog.kt:189-191`) calls `prefs.getString` there — a cold-start DiskReadViolation that debug StrictMode (`MainActivity.java:12-15`) already flags. |
| 6 | Confine all `RequestStoreCore` mutations incl. `persist()` to one serial executor | architecture#11, robustness#11 | APPROVE | Poller calls `store.tick()` on main (`AppState.kt:96`), `DetailActivity.kt:239` / `RequestsActivity.kt:138` mutate on main, and `persist()` (`Catalog.kt:243-260`) re-serializes the whole list under lock on the UI thread — jank that grows with request count. |
| 7 | Let *arr read methods throw on transport failure instead of returning empty | quality#2, quality#9(partial) | APPROVE | `Clients.kt:271-323` swallow via `getOrElse { emptyList() }`, so `pingAll` (`LiveRepository.kt:64-65`) `runCatching{…}.isSuccess` is always true (green ✓ on a dead Sonarr/Radarr) and `refreshStatuses` (:147-150) reads failure as "not in library" → status stuck PENDING. Apply the `getOrDefault` once at the poller boundary where the `failed` flag exists. |
| 8 | Adopt one documented error strategy per layer (clients throw `ApiException`; only UI/poller converts) | quality#9 | MODIFY | Five strategies coexist (`Clients.kt:272,168,100-101`, `MainScreen.kt:271`); implement the rule via row 7 and make `TmdbClient.details`' catch an explicit documented fallback — skip a codebase-wide rewrite of every call site in this round. |
| 9 | Non-destructive `RequestStoreCore.reload`: parse into a temp list, swap only on success, keep raw string + expose load error | quality#3 | APPROVE | `Catalog.kt:208-227` runs `items.clear()` before the `runCatching` parse; one malformed prefs string silently empties the history and the next `persist()` makes the loss permanent. |
| 10 | Drop the legacy TSV migration branch in `reload` | simplicity#13 | MODIFY | Deletion is safe only together with row 9 (without it, an old-format string would wipe requests); do it in the next release, after row 9 lands, and delete the format sniffing. |
| 11 | Check-before-add ordering in `doRequest` (bail if `store.byMediaId` exists before calling `repo.request`) | quality#17, architecture#2 | MODIFY | `DetailActivity.kt:222-236` calls the side-effecting *arr add before `store.create` returns null for duplicates → orphaned *arr entry with an "Already requested" toast; fix inline with a 3-line guard now, defer the full `RequestMediaUseCase` extraction (that part is a bigger refactor). |
| 12 | Give `refreshStatuses` the request-snapshot fallback for `mediaById` | architecture#7 | APPROVE | `LiveRepository.kt:150` `mediaById(r.mediaId) ?: continue` skips every request after a cold start (empty mediaCache, live ids not in Catalog) so statuses freeze at PENDING; `AppState.kt:70-79` already shows the 3-way resolver to reuse. |
| 13 | `refreshStatuses`: build id→state maps once; make `markAvailability` pure | simplicity#15 | APPROVE | `LiveRepository.kt:164-165` does `firstOrNull` over the whole library per request (O(n×m) with thousands of entries) and `markAvailability` (:183-195) mutates `mediaCache` as a hidden side effect of a browse call. |
| 14 | Cap HTTP response body size for JSON calls | robustness#4 | APPROVE | `Http.kt:81` `stream.readBytes()` is uncapped for every API call (only `ImageLoader` caps at 8 MB, `ImageLoader.kt:33`) — a huge/hostile response is a heap OOM crash. |
| 15 | Re-run `isSafeExternalUrl` on every redirect target in `realHttp` | robustness#5 | MODIFY | The redirect loop (`Http.kt:89-97`) follows `Location` without ever re-validating the target, so a 3xx to `169.254.169.254` is fetched; adding the per-hop check is cheap and real. Skip full DNS-rebinding pinning (connect-by-IP) — over-engineering for a user-configured LAN indexer. |
| 16 | On 301/302/303 switch to GET with null body (keep method only for 307/308) | robustness#12 | APPROVE | `Http.kt:89-97` re-sends the original POST body to the redirect target, so a trailing-slash or http→https redirect from *arr executes `addMovie`/`addSeries` twice → duplicate library entries. |
| 17 | Only ever copy `magnet:` URIs to the clipboard | robustness#6 | APPROVE | `DetailActivity.kt:295-300` accepts any string containing ".torrent", and `magnetFromDownloadUrl` (`Http.kt:109-116`) returns the apikey-bearing `downloadUrl` as-is on a 200 — the indexer API key lands on the shared clipboard, contradicting the code's own comment. |
| 18 | Result-cache key must include live/placeholder mode (or clear on config change); bound its size | architecture#6/#13, simplicity#2/#13, quality#7 | APPROVE | `MainScreen.kt:194` keys `"$tab|$q"` only, so after enabling live mode `onConfigChanged → loadItems()` (:67) serves placeholder results for up to `CACHE_TTL_MS` 45 s (:348); include `AppState.liveMode` in the key (or clear in the handler) and cap entries with an LRU. |
| 19 | Encapsulate the companion `cache`/`signatures` in a small synchronized `ResultCache` object | quality#7, architecture#13 | MODIFY | `MainScreen.kt:344-350` plain `MutableMap`s in a companion, aliased at :56-57, mutated from `loadItems`/`evictCache` with no synchronization; wrap in one object with synchronized get/put/evict/clear (still process-scoped for rotation) — don't redesign ownership beyond that. |
| 20 | Split `MainScreen.loadItems()` into `loadPlaceholder`/`loadLive` behind one tiny job-guard helper | simplicity#1 | APPROVE | `MainScreen.kt:233-284` is five jobs in one method (placeholder path, cache, TTL, stale-while-revalidate, worker+race+error) and the placeholder branch bypasses the `loadJob` race guard entirely (:243-248). |
| 21 | Define mode-banner text once (`TextView.setModeBanner(live, t)` in Components) | quality#6, simplicity#4 | APPROVE | Identical strings/content-descriptions copy-pasted at `Components.kt:644-653` and `MainScreen.kt:176-184`; one function in Components.kt used by both kills the drift. |
| 22 | Stop reaching into `AppTopBar` internals by child index / tag string | quality#6, simplicity#9 | APPROVE | `MainScreen.kt:171-173` `(topBar.getChildAt(0) as LinearLayout).getChildAt(0) as LinearLayout` then `getChildAt(1) as? TextView` throws IndexOutOfBounds if the subtitle is absent; tag the subtitle and use `findViewWithTag` (same pattern already used for the action button at :341). |
| 23 | Merge `statusColorOf`/`statusLabelOf` into one `statusPresentation()` returning label+color | quality#5, architecture#9(partial) | APPROVE | `Components.kt:382-394` two parallel `when`s over identical conditions feeding `StatusPill` (:396-401); any new status must be added twice and label/color can drift apart. |
| 24 | One `posterView(...)` helper for the three poster sites | simplicity#5 | APPROVE | `Components.kt:492-513`, `DetailActivity.kt:120-130`, `RequestsActivity.kt:105-115` each re-implement the ImageView-vs-GradientPoster + clip + contentDescription decision. |
| 25 | Single `Magnet` type instead of `Magnet` + `LiveMagnet` + mapping shim | simplicity#11 | APPROVE | `DetailActivity.kt:263-266` maps field-by-field with a `downloadUrl = ""` sentinel; add `downloadUrl: String = ""` to `Magnet` and delete the shim. |
| 26 | Generic `ArrClient.states/queueIds` to dedupe Sonarr/Radarr clones | simplicity#14 | APPROVE | `Clients.kt:271-287` vs `:310-326` differ only in endpoint/field name/result type; Prowlarr re-implements the same GET+retry plumbing (:158-166 vs :211-215). |
| 27 | `BottomNavBar`: one `List<Cell>`, drop `initialized` flag and `root.post` hack | simplicity#10 | APPROVE | `Components.kt:233-296` keeps four index-aligned lists (`cells/pills/icons/labels`) plus an `initialized` flag whose only job is to defer the first `select` via `root.post`. |
| 28 | Replace `GradientPoster.onDraw` hand-rolled word-wrap with a FrameLayout + gradient + TextView (maxLines=3, ellipsize) | simplicity#7, quality#11(partial), theming#2(partial) | APPROVE | `Components.kt:438-475` ~30 lines of wrap/truncate/font-fit math with 10+ magic fractions, and the wrap loop can emit a single word longer than 14 chars unsplit; a TextView deletes the algorithm and the bug. (This also supersedes the luminance dead-zone fix of theming#2.) |
| 29 | JsonX: add `long`/`bool` accessors, use kotlinx `put` overloads for writing in the three codecs | simplicity#8 | APPROVE | Bespoke `buildJsonObject`/parse pairs with stringly reads at `Config.kt:52-74`, `Catalog.kt:96-125`, `:208-267`; small typed helpers remove the per-field duplication. Skip dropping `MediaSnapshot` (bigger change). |
| 30 | Settings form: typed `lateinit var` EditTexts instead of string-keyed map + nine `!!` | quality#13, simplicity#16(partial) | APPROVE | `SettingsActivity.kt:66-114` registers keys as literals then re-reads `fields["tmdbToken"]!!` etc.; renaming one side NPEs at runtime — store the views in typed properties and delete the map. |
| 31 | Replace `navInitiated` flag with a `fromNav` parameter through `SwipePager.goToPage` | simplicity#17 | APPROVE | `MainScreen.kt:46,107-111,125` — two callbacks mutate shared state to pass one bit; threading the boolean through `onPageSelected` deletes the mini state machine. |
| 32 | Extract `build*` helpers from long `attach`/`onCreate` functions | quality#11 | MODIFY | `MainScreen.attach` (:67-141), `SettingsActivity.onCreate` (:39-130), `DetailActivity.onCreate` (:40-125) are 75-90 lines mixing construction/wiring/logic, but do this incrementally alongside the other edits (rows 20-22, 30), not as a standalone churn commit; the onDraw case is removed by row 28. |
| 33 | Delete dead code `EmphasizedPath`, `glassDrawable`, `renderOnce` | quality#14, simplicity#12 | APPROVE | Verified by grep: zero references in main or test — `Motion.kt:32`, `Glass.kt:18`, `AuroraBackground.kt:104`; they mislead readers about which path is live. |
| 34 | Delete test-only `PorcelainLight` / `AppTheme.use` / `reset` / `compositeOver` | simplicity#12 | REJECT | Not dead: `DesignTokensTest.kt:13,96-103,130` exercises exactly these to prove the token system is swappable and to compute composited contrast; deleting them would gut the design-system tests that guard Tokens.kt's header promise. |
| 35 | Rename `MATCH`/`WRAP` constants and unify PascalCase factory function names | quality#15 | REJECT | Renaming `AppText`/`Chip`/`PrimaryButton`/… across every file is a pure mass rename — churn and merge-conflict bait with zero behavior change; the PascalCase factory style is already applied uniformly in Components.kt, and `MATCH`/`WRAP` (:39-40) are documented shorthands. |
| 36 | Rename inverted `lightIcons` → `lightBackground`; extract the 0.5 luminance threshold const | quality#12 | APPROVE | `Insets.kt:24-29`: `lightIcons = luminance(bg) >= 0.5` actually means "light background → request dark icons"; the name reads as the opposite of what it controls and a future reader will "fix" it and break icon contrast. |
| 37 | Rename derived `AppState.liveMode` → `liveActive` (keep raw flag in `AppConfig`) | quality#16 | APPROVE | `Config.kt:13` raw toggle vs `AppState.kt:28` derived `liveReady` share a name while holding different values for an incomplete config — a real name collision across layers. |
| 38 | Remove the unused `t: DesignTokens` param from `col`/`row`; complete `lp` margins only if needed | quality#8 | MODIFY | `Components.kt:44-48` — `t` is never used in the body yet every caller passes a theme (misleading signature); drop it. Do not speculatively extend `lp` (:50-54) to bottom/end margins until a caller needs it. |
| 39 | Delete stale `build.gradle` comment; convert `MainActivity.java` to Kotlin | quality#19 | APPROVE | `app/build.gradle:43` is a leftover note with no code; `MainActivity.java` is a 40-line mechanical conversion in an otherwise all-Kotlin source set. |
| 40 | Introduce domain `MediaRepository` interface + `PlaceholderRepository` + domain/data/ui package split + Null Object | architecture#1/#4/#12 | REJECT | A cross-cutting restructure of every screen and the flat package with no behavior fix — the big-refactor pattern this round should avoid; its concrete wins are much smaller standalone fixes already covered by rows 12, 18, 19, 23. |
| 41 | Replace `AppState` service locator with constructor-injected `AppContainer` + multi-subscriber listener list | architecture#3 | REJECT | Touches every Activity for a latent issue: `onConfigChanged` has exactly one subscriber today (`MainScreen.kt:67,161`), so the single-slot overwrite cannot currently happen; revisit only when a second subscriber appears. |
| 42 | Extract pure-Kotlin screen controllers behind the views for testability | architecture#8 | REJECT | Big refactor; the risky decisions it targets (poller guard row 1, cache key row 18, reload row 9, request ordering row 11) are being fixed directly in the already unit-testable pure core instead. |
| 43 | Split god-object files (Catalog.kt 390 lines, Components.kt 657, AppState.kt) | architecture#9 | REJECT | Mass file split with no behavior change; the one real coupling (status→presentation mapping) is fixed by row 23 without moving files. |
| 44 | Route `SettingsActivity.test()` through `RepositoryFactory.create` | architecture#10 | APPROVE | `SettingsActivity.kt:150` constructs `LiveRepository(cfg)` directly, bypassing the "single construction rule" documented at `LiveRepository.kt:199-204` — one-line consistency fix. |
| 45 | `DetailActivity`: re-render actions in `onResume` | architecture#15 | APPROVE | `renderActions` (`DetailActivity.kt:185-216`) is called only from onCreate/doRequest/dialog, so the action button stays "⏳ pending" while the shared poller advances the status. |
| 46 | `ImageLoader`: accept an injectable `HttpTransport` like every client | architecture#16 | APPROVE | `ImageLoader.kt:36` hardwires `realHttp` — the only fetcher without the test seam all clients already take; add `http: HttpTransport = realHttp` param. |
| 47 | `ImageLoader`: bound the work queue / skip obsolete fetches before starting I/O | robustness#9 | APPROVE | `Executors.newFixedThreadPool(3)` (`ImageLoader.kt:19`) has an unbounded queue and the `view.tag == url` check (:44) happens only after fetch+decode, so fast scrolling enqueues hundreds of obsolete 8 MB fetches. |
| 48 | `SearchField.close()` removes debounce callbacks, called from `MainScreen.detach()`; `AuroraBackground.onSizeChanged` recycles the old bitmap | quality#18 | APPROVE | `Components.kt:356` `postDelayed(pending!!, 350)` is never cancelled, so a pending runnable retains a detached MainScreen; `AuroraBackground.kt:33-41` reassigns `bmp` without `bmp?.recycle()` — leak on every rotation. |
| 49 | Replace guarded `!!` with local smart-cast values | robustness#15 | APPROVE | `DetailActivity.kt:127`, `RequestsActivity.kt:100`, `Components.kt:500`, `AuroraBackground.kt:39`, `Components.kt:356` are each one refactor away from a crash (e.g. `MediaSnapshot.toMedia` can build `Media` with `posterUrl = null`, `Catalog.kt:99-108`); `val url = m.posterUrl ?: …` proves non-null at the use site. |
| 50 | Move repeated timing/tint literals into `MotionTokens`/named consts (aurora 24_000 ms & alpha 52, debounce 350, double-tap 400, chip alphas 0.30/0.22/0.18, banner 0.18, res/anim durations) | quality#4, theming#3/#4/#9 | MODIFY | These contradict the "components never hard-code values" header (`Tokens.kt:8-12`) and the test hard-codes `0.22f` (`DesignTokensTest.kt:128-130`); token-ize the repeated values (timings, tint alphas, anim XML durations). Poster-draw fractions vanish via row 28; skip the exhaustive per-site sweep. |
| 51 | Route off-grid `dp()` literals through `SpaceTokens`; add one `minTargetDp = 48` token | theming#5/#6 | MODIFY | ~15 raw `dp(2/3/6/10/14/20)` sites bypass the tested 4dp grid (`DesignTokensTest.kt:44-51`) and `dp(48)` is hardcoded 9×; fix the 48dp token and the obvious paddings — don't invent a full `LayoutTokens` block for one-off sizes. |
| 52 | Replace raw ARGB literals with tokens; drop hardcoded `windowBackground` from themes.xml | theming#8 | APPROVE | `Components.kt:509`, `DetailActivity.kt:142-143`, `Components.kt:426-428`, `Glass.kt:36,43` hardcode white/black inks that won't re-skin; add `textOnScrim`/ink tokens and remove `#0A0C14` from `themes.xml:4` (already set at runtime in `edgeToEdge`, `Insets.kt:14`). |
| 53 | Radius consistency: skeleton uses `radius.poster`; nav bar gets its own radius token | theming#7 | APPROVE | `Components.kt:575` skeleton uses `radius.button` (16dp) in the same grid cells whose posters are 20dp (:496,502) — visible corner jump on every load; `BottomNavBar` borrows `radius.card` (:244-248). |
| 54 | Delete unused elevation tokens `level2Dp`/`level3Dp`/`shadowColor` | theming#10 | APPROVE | `Tokens.kt:76-79`: only `level1Dp` is referenced (`Components.kt:258`); deletion is the smallest honest fix (wiring the hero is a visual change, not a cleanup). |
| 55 | Press-down should use `instantMs`, not `instantMs * 2` | theming#11 | APPROVE | `Motion.kt:93-96`: ACTION_DOWN animates at 160 ms while release uses 160 ms — press-in must be the fastest response (80 ms); one-line swap. |
| 56 | Requests list: update cards in place (diff by id, update StatusPill) instead of `removeAllViews()` + `staggerIn` every tick | ux#1 | APPROVE | `RequestsActivity.kt:94,155` destroys and re-animates every card on each 1 s/5 s poll → visible flicker and TalkBack focus loss; the signature check (:72-74) already limits frequency, the rebuild is the problem. |
| 57 | Main grid: skip container `fadeIn` on non-animate re-renders | ux#2 | MODIFY | `MainScreen.kt:335-336` calls `p.grid.fadeIn(...)` even when `animate == false` after `removeAllViews()` (:327), blinking the whole grid on every status change; the minimal fix is one line (only fade when `animate` and first population), full card-diff is an optional follow-up. |
| 58 | Keep current results visible while a new search loads | ux#3 | APPROVE | `MainScreen.kt:258-259` calls `showLoading(p, loading=true, hasItems=false)`, hiding the previous query's grid behind the skeleton; use `hasItems = p.grid.childCount > 0` so only a first load shows the skeleton. |
| 59 | Failed background revalidation keeps stale results + inline error banner | ux#4 | APPROVE | `MainScreen.kt:274,285-291` replaces the just-rendered stale cache (:253-256) with a full-screen error; keep `scroll` visible and show a dismissible banner with Retry when `cache[key]` has items (the Requests screen already does the right pattern, `RequestsActivity.kt:25-27`). |
| 60 | Honor `reduceMotion()` in all motion helpers and the pager | ux#5 | APPROVE | Only `AuroraBackground.kt:45` and `Skeleton` (`Components.kt:578`) check it; `Motion.kt:38,52,65,80,95` and `SwipePager.kt:37` animate at full duration for reduced-motion users — add a one-line early return in each. |
| 61 | `SettingsActivity`: `adjustResize` + `imeOptions` chaining | ux#6 | APPROVE | `AndroidManifest.xml:15` sets `windowSoftInputMode` only on MainActivity; the 9-field form (`SettingsActivity.kt:56-100`) pans and the fields have no `imeOptions` (`Components.kt:614-631`). |
| 62 | Associate form labels with fields (`labelFor`) and expose errors via `setError` | ux#7 | APPROVE | `Components.kt:614-631` returns a bare label + EditText; `et.id = View.generateViewId(); lab.labelFor = et.id` plus `et.setError(e)` makes TalkBack announce field and error. |
| 63 | Block Save when any URL field is invalid | ux#8 | APPROVE | `SettingsActivity.kt:118-131` persists the config although the per-field watcher already computed `AppConfig.urlError` (:63-65); validate the collected config and `requestFocus()` the first invalid field. |
| 64 | Include the badge count in the top-bar action's accessible name + announce changes | ux#9 | APPROVE | `Components.kt:155,162-176` + `MainScreen.kt:340-342`: the count changes but the description stays "Requests"; set "Requests, N new" and `announceForAccessibility` on change. |
| 65 | Poster card description includes rating + request status; inner poster image not focusable | ux#10 | APPROVE | `Components.kt:530` describes only title/year/type while the ★ chip (:503) and StatusPill (:510-513) are visual-only, and the inner ImageView (:497) is a duplicate focusable node — set `importantForAccessibility = NO` on it. |
| 66 | Save button shows "Saving…" while the async encrypt runs | ux#11 | APPROVE | `SettingsActivity.kt:120` only disables the button; the sibling `test()` (:140-141) already sets "Testing…" — copy that pattern. |
| 67 | Empty-state copy distinguishes blank query from no search matches | ux#12 | APPROVE | `MainScreen.kt:205` always shows "No titles match your search." even when the query is blank and a live browse returned zero. |
| 68 | Toast before `finishDropping()` when the media id can't be resolved | ux#13 | APPROVE | `DetailActivity.kt:44-45` closes the screen with no explanation when `AppState.mediaById` returns null. |
| 69 | `hideKeyboard()` before opening detail from the grid | ux#14 | APPROVE | `MainScreen.kt:331` opens DetailActivity with the IME still up; `hideKeyboard()` (:189-192) is only called on nav taps (:110). |

## My APPROVE/MODIFY list

1. Fix `StatusPoller` busy-guard to span the async refresh + prune finished workers (AppState.kt:94-110).
2. Make the delayed `poller.start()` removable in `MainScreen.detach()` (MainScreen.kt:135,157-161).
3. Extract one shared WorkerScope for all four spawn/interrupt sites.
4. MODIFY: cancellation via executor `shutdownNow()` + cooperative flag; skip socket-disconnect plumbing.
5. Build `RequestStoreCore` + pre-warm `Catalog` off the main thread in `AppState.init` (AppState.kt:33-49).
6. Confine all store mutations/`persist()` to one serial executor.
7. *arr read methods throw on transport failure; `getOrDefault` once at the poller boundary (Clients.kt:271-323, LiveRepository.kt:64-65,147-150).
8. MODIFY: one error strategy per layer, applied to the transport/read path only this round.
9. Non-destructive `RequestStoreCore.reload` (Catalog.kt:208-227).
10. MODIFY: drop the TSV branch only after #9 lands.
11. MODIFY: inline check-first guard in `doRequest`; defer the use-case extraction (DetailActivity.kt:218-236).
12. Snapshot fallback for `mediaById` in `refreshStatuses` (LiveRepository.kt:150).
13. `associateBy` id maps + pure `markAvailability` (LiveRepository.kt:164-195).
14. Cap HTTP response body size (Http.kt:81).
15. MODIFY: re-validate SSRF on each redirect hop; skip DNS pinning (Http.kt:89-97).
16. GET+null body on 301/302/303 redirects (Http.kt:89-97).
17. Only copy `magnet:` URIs to the clipboard (DetailActivity.kt:295-300).
18. Cache key includes mode / clear on config change + LRU bound (MainScreen.kt:194,348).
19. MODIFY: companion maps → small synchronized `ResultCache` object (MainScreen.kt:344-350).
20. Split `loadItems` into `loadPlaceholder`/`loadLive` + one job guard (MainScreen.kt:233-284).
21. Single `setModeBanner(live, t)` (Components.kt:644-653, MainScreen.kt:176-184).
22. Tag the top-bar subtitle; use `findViewWithTag` instead of child-index casts (MainScreen.kt:171-173).
23. One `statusPresentation()` replacing `statusColorOf`/`statusLabelOf` (Components.kt:382-401).
24. One `posterView(...)` helper for the 3 poster sites.
25. Single `Magnet` type; delete the LiveMagnet shim (DetailActivity.kt:263-266).
26. Generic `ArrClient.states`/`queueIds` (Clients.kt:271-326).
27. `BottomNavBar`: one `List<Cell>`, drop `initialized`/`root.post` (Components.kt:233-296).
28. TextView-over-gradient poster instead of hand-rolled onDraw wrap (Components.kt:438-475).
29. JsonX `long`/`bool` accessors + kotlinx `put` in the three codecs.
30. Typed EditText properties in SettingsActivity; delete the string map + `!!` (SettingsActivity.kt:66-114).
31. `fromNav` parameter instead of `navInitiated` flag (MainScreen.kt:46,107-125).
32. MODIFY: extract `build*` helpers incrementally, bundled with other edits — not a standalone refactor.
33. Delete dead `EmphasizedPath`/`glassDrawable`/`renderOnce` (Motion.kt:32, Glass.kt:18, AuroraBackground.kt:104).
36. Rename `lightIcons` → `lightBackground` + threshold const (Insets.kt:24-29).
37. Rename `AppState.liveMode` → `liveActive` (AppState.kt:28).
38. MODIFY: drop unused `t` from `col`/`row`; don't extend `lp` speculatively (Components.kt:44-54).
39. Delete stale build.gradle comment; convert MainActivity.java → .kt.
44. Route `SettingsActivity.test()` through `RepositoryFactory` (SettingsActivity.kt:150).
45. Re-render detail actions in `onResume` (DetailActivity.kt:185-216).
46. Injectable `HttpTransport` param on `ImageLoader.load` (ImageLoader.kt:36).
47. Bound ImageLoader queue / skip obsolete fetches pre-I/O (ImageLoader.kt:19,44).
48. `SearchField.close()` on detach; recycle aurora bitmap (Components.kt:356, AuroraBackground.kt:33-41).
49. Smart-cast locals instead of the five guarded `!!` sites.
50. MODIFY: token-ize repeated timings/tint alphas/anim durations; poster fractions die with #28.
51. MODIFY: off-grid dp → `t.space.*` + one `minTargetDp = 48` token; no full LayoutTokens.
52. `textOnScrim`/ink tokens for raw ARGB literals; remove `#0A0C14` from themes.xml.
53. Skeleton → `radius.poster`; dedicated `radius.navBar` (Components.kt:575,244-248).
54. Delete unused elevation tokens (Tokens.kt:76-79).
55. Press-down uses `instantMs` (Motion.kt:93-96).
56. Requests list updates in place per status tick (RequestsActivity.kt:94,155).
57. MODIFY: skip grid `fadeIn` when `animate == false` and grid populated (MainScreen.kt:335-336).
58. Keep current results visible during search (`hasItems = grid.childCount > 0`, MainScreen.kt:258-259).
59. Failed revalidation keeps stale results + inline error banner (MainScreen.kt:274,285-291).
60. `reduceMotion()` guard in all motion helpers + pager (Motion.kt:38-95, SwipePager.kt:37).
61. `adjustResize` + IME chaining on SettingsActivity (AndroidManifest.xml:15).
62. `labelFor` + `setError` in FormField (Components.kt:614-631).
63. Block Save on invalid URLs (SettingsActivity.kt:118-131).
64. Accessible badge count + announce (Components.kt:155,162-176).
65. Card description includes rating/status; inner image not focusable (Components.kt:497,530).
66. Save button "Saving…" label (SettingsActivity.kt:120).
67. Blank-query empty-state copy (MainScreen.kt:205).
68. Toast when detail media unresolvable (DetailActivity.kt:44-45).
69. `hideKeyboard()` on poster click (MainScreen.kt:331).

## Notes

- Biggest quality wins this round are the ones where the code contradicts its own documented contract: the StatusPoller comment (row 1), the Tokens.kt "components never hard-code values" header (rows 50-52), and the ArrClient doc comment "THROW on failure" (row 7). Fixes that make comments true are cheap and high-signal.
- Rejected as big refactors per this round's guidance: the `MediaRepository` + package-split restructure (#40), the `AppContainer` DI overhaul (#41 — its single-callback hazard is latent with one subscriber, verified by grep), pure screen controllers (#42), and god-object file splits (#43). Their genuine small wins are extracted individually (rows 12, 18, 19, 23).
- Rejected as churn/edge cases: mass PascalCase→camelCase rename (#35), deleting the test-exercised theme-swap machinery (#34), and the AppState init/saveConfig race (architecture#14 — the window requires saving within ~100 ms of process start before the user can reach Settings, implausible; a version stamp can be added later if a second writer appears).
- Row 28 (TextView poster) resolves three reports at once (simplicity#7, quality#11's onDraw part, theming#2's dead-zone/scrim issue) — prefer it over patching the hand-rolled wrap algorithm.
- Row 9 (non-destructive reload) is a prerequisite for row 10 (TSV removal); don't merge them into one commit order that deletes the fallback first.
- Everything approved is small and local: no change touches more than one file except the WorkerScope extraction (row 3), which is mechanical and removes a pattern that has already drifted once.
