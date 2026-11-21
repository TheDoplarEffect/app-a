# Round 3 triage — panelist: SIMPLICITY (Ousterhout / KISS / YAGNI)

Scope: all six round-3 reports, deduped, voted against the code in `app/src/main/java/com/example/appa/`.
My lens: prefer small, high-impact, low-risk edits; reject abstraction for hypothetical needs, mass renames, and speculative-infrastructure refactors.

Proposed-by key: Q=quality, A=architecture, S=simplicity, R=robustness, T=theming, U=ux.

## Triage table

| # | change | proposed by | your vote | justification |
|---|--------|-------------|-----------|---------------|
| 1 | StatusPoller: hold `busy` until the async refresh callback runs; stop `workers` growing (remove finished threads, or single-thread executor + `shutdownNow` in `cancel()`) | Q1, A5, S3, R1, R10 | APPROVE | `AppState.kt:94-110` releases `busy` in `finally` right after `th.start()`, so each 5 s tick spawns another `refreshStatuses` while the last is running, and `workers.add(th)` (:104) is never undone. Simplest correct fix is one single-thread executor — less code than the hand-rolled list. |
| 2 | Make the delayed `poller.start()` cancellable: named runnable + `removeCallbacks` in `detach()` + `attached` guard | R2 | APPROVE | `MainScreen.kt:135` posts an anonymous lambda; `detach()` (:157-161) only removes the poller's tick runnable, so the poller can start after `onDestroy` and leak the Activity via the Handler. |
| 3 | *arr read methods (`seriesStates`/`movieStates`/`queue*`) throw `ApiException` instead of `runCatching{}.getOrElse{emptyList()}`; handle once at the poller/UI boundary | Q2 (part of Q9) | APPROVE | `Clients.kt:271-323` swallow their own errors, so `LiveRepository.kt:64-65` `runCatching{ sonarr.seriesStates() }.isSuccess` is always true — Settings shows ✓ for a down Sonarr/Radarr (`SettingsActivity.kt:133-164`). `refreshStatuses` already wraps calls in `runCatching`, so deleting the inner ones is safe. |
| 4 | `RequestStoreCore.reload`: parse into a local list, swap `items` only on success; never persist over corrupt data | Q3 | APPROVE | `Catalog.kt:208-227` does `items.clear()` then swallows parse errors; the next `persist()` from any mutation permanently wipes the request history. |
| 5 | `doRequest`: check `store.byMediaId(media.id)` before calling `repo.request(media)` | Q17, A2 (reorder part) | APPROVE | `DetailActivity.kt:218-236` adds to *arr first, then `store.create` returns null → orphaned *arr entry the user can't cancel. Three-line reorder fixes it. |
| 6 | MainScreen cache: include live/placeholder mode in the key (or clear on config change) and cap its size (LRU) | Q7, A6, A13, S2, R13 | APPROVE | `cacheKey = "$tab|$q"` (`MainScreen.kt:194`) is mode-blind, so `onConfigChanged → loadItems()` serves placeholder results as "live" for up to 45 s (TTL :348); one entry per keystroke, evicted only by age. No concurrency wrapper needed — every access is on the main thread today. |
| 7 | Split `loadItems()` into `loadPlaceholder`/`loadLive` with one shared job-guard helper | S1 | APPROVE | `MainScreen.kt:233-284` is five jobs in one method; the placeholder path (:236-241) bypasses the `loadJob` guard entirely, so a late placeholder render can't be cancelled. |
| 8 | Merge `statusColorOf`/`statusLabelOf` into one `statusPresentation()` returning label+color | Q5 | APPROVE | Two parallel `when` blocks over identical conditions (`Components.kt:382-394`) feeding `StatusPill`; a new status must be added twice and can drift. |
| 9 | `AppTopBar`: expose the subtitle view via a handle; expose the action button via a typed accessor instead of the `"top_action_📥"` tag | Q6, S9 | APPROVE | `MainScreen.kt:171-173` does `getChildAt(1) as? TextView` — the `as?` can't save an `IndexOutOfBoundsException` if the subtitle is ever absent; `renderRequestsBadge` (:341) finds the button via a string tag built inside the atom (`Components.kt:118`). |
| 10 | Single-source the mode-banner text: `TextView.setModeBanner(live, t)` in Components.kt used by both `ModeBanner` and `refreshChrome` | Q6, S4 | APPROVE | Identical strings/contentDescriptions copy-pasted at `Components.kt:644-653` and `MainScreen.kt:176-184`. |
| 11 | One shared `WorkerScope` (spawn + shutdown) used by MainScreen, DetailActivity, StatusPoller, and the Settings test thread | Q10, S6, R7, R8 (partial) | APPROVE | The `synchronizedList<Thread>` + spawn + interrupt-on-destroy pattern is copied 3× (`MainScreen.kt:59-63`, `DetailActivity.kt:37,101-106`, `AppState.kt:87,104`) and `SettingsActivity.kt:149-160` spawns an untracked thread that survives `onDestroy`. One tiny class deletes all four copies. |
| 12 | `SearchField.close()`: `handler.removeCallbacksAndMessages(null)`, called from `MainScreen.detach()` | Q18 | APPROVE | `Components.kt:356` leaves the 350 ms debounce runnable pending forever; the placeholder path in `loadItems` (`MainScreen.kt:243-248`) has no `attached` check, so it can fire on a dead screen. |
| 13 | `AuroraBackground.onSizeChanged`: `bmp?.recycle()` before reassigning | Q18 | APPROVE | `AuroraBackground.kt:38-41` allocates a new offscreen bitmap every resize and leaks the old one. |
| 14 | Delete zero-reference dead code: `EmphasizedPath`, `glassDrawable`, `renderOnce`, and unused `ElevationTokens.level2/3/shadowColor` | Q14, S12 (partial), T10 | MODIFY | `Motion.kt:32`, `Glass.kt:18`, `AuroraBackground.kt:104` have zero callers — delete them plus the unused elevation fields (`Tokens.kt:76-79`). But keep `PorcelainLight`/`AppTheme.use`/`compositeOver`: they are `DesignTokensTest` fixtures, i.e. the design system's tested swappability proof, not production dead code (see row 65). |
| 15 | Rename `lightIcons` → `lightBackground` (+ named luminance threshold const) in `edgeToEdge()` | Q12 | APPROVE | `Insets.kt:24-29`: a *light background* means requesting *dark* icons; the name reads as the opposite of what it controls and invites a future "fix" that breaks icon contrast. |
| 16 | Settings form: typed field refs (drop the `fields` map + nine `!!`); validate URLs once in `save()` and refuse to save on error | Q13, S16, U8 | APPROVE | `SettingsActivity.kt:66-114` registers and re-reads nine string keys with `fields["…"]!!`; `save()` (:118-131) persists invalid URLs even though the per-field watcher already detected them. |
| 17 | Settings keyboard/a11y: `adjustResize` on SettingsActivity, `IME_ACTION_NEXT` chaining, `labelFor` + `setError()` in `FormField` | U6, U7 | APPROVE | Manifest gives `adjustResize` only to MainActivity; `FormField` (`Components.kt:614-631`) returns an unlinked label + EditText. Small, contained. |
| 18 | DetailActivity: re-render actions in `onResume()` | A15 | APPROVE | `renderActions` (`DetailActivity.kt:185-216`) runs only on create/doRequest/cancel, so the button stays "⏳ pending" while the poller advances the status. |
| 19 | `refreshStatuses`: resolve media via the request's snapshot when `mediaById` misses; precompute `associateBy` maps instead of per-request `firstOrNull` scans | A7, S15 | APPROVE | `LiveRepository.kt:150` `?: continue` skips every live request after a cold start (empty `mediaCache`) — statuses freeze until the user re-browses; :164-165 are O(n×m) scans. |
| 20 | Move the first prefs disk read (RequestStoreCore load) off the main thread | R3 | MODIFY | `AppState.kt:33-38` constructs the store on main and `init{ reload() }` reads prefs synchronously (StrictMode `DiskReadViolation`, cold-start ANR budget). But `MainScreen.attach` uses `AppState.store` immediately (:81), so build the store inside the existing background thread, guard UI access with `::store.isInitialized`, and refresh the badge in `onConfigChanged`. |
| 21 | Route store mutations/`persist()` off the main thread (serial executor or inside the poller worker) | A11, R11 | APPROVE | Poller tick (`AppState.kt:96`) and create/delete (`DetailActivity.kt:239`, `RequestsActivity.kt:138`) run `persist()` — full JSON rebuild + prefs write (`Catalog.kt:249-264`) — on the UI thread, holding the store lock. |
| 22 | Cap HTTP response body read (bounded copy + `contentLengthLong` pre-check) | R4 | APPROVE | `Http.kt:81` does `stream.readBytes()` unbounded; only ImageLoader caps (8 MB, `ImageLoader.kt:33`). A hostile/misconfigured URL can OOM the app. |
| 23 | Only ever copy `magnet:` URIs to the clipboard | R6 | APPROVE | `DetailActivity.kt:295-300` accepts any URL containing `.torrent`, and `magnetFromDownloadUrl` (`Http.kt:109-116`) returns the apikey-bearing `downloadUrl` unchanged on a 200 — the Prowlarr key lands on the shared clipboard. |
| 24 | Redirect handling: on 301/302/303 switch to GET with `body = null`; keep method+body only for 307/308 | R12 | APPROVE | `Http.kt:89-97` re-sends the POST body to the redirect target — a trailing-slash/http→https redirect on *arr executes `addMovie`/`addSeries` twice. |
| 25 | ImageLoader: bounded queue (`DiscardOldestPolicy`) + tag/attach check *before* enqueueing and before the HTTP call | R9 | APPROVE | `ImageLoader.kt:19-30` enqueues onto an unbounded `LinkedBlockingQueue`; the recycled-view tag check happens only after fetch+decode (:44). |
| 26 | Pre-warm `Catalog` (touch `Catalog.all.size`) in the `AppState.init` background thread | R14 | APPROVE | `Catalog.all`/`magnetsByMediaId` are `by lazy` and first touched on the main thread (`MainScreen.kt:236-241`, `DetailActivity.kt:266`). One line in the existing worker. |
| 27 | `GradientPoster`: replace the hand-rolled `onDraw` text layout with a `TextView` (maxLines=3, ellipsize=END) over the gradient + scrim covering the label | S7, T2 | APPROVE | `Components.kt:438-475` is ~30 lines of wrap/truncate/font-fit math with magic fractions, a luminance dead zone (0.183) where neither text color passes, and an unscrimmed top label; a TextView deletes the algorithm and fixes all three. Biggest pure-simplification win on the board. |
| 28 | Clean up the three bespoke JSON codecs: add `long()` etc. to `JsonX.kt`, use typed `put()` overloads in `Config.toJson`/`persist()` | S8 | MODIFY | Stringly reads like `o["requestedAt"].str().toLongOrNull() ?: 0L` (`Catalog.kt:208-227`, `Config.kt:52-74`) are type-unsafe; a `JsonElement?.long()` accessor + typed `put` shrinks them. Do NOT merge `MediaSnapshot` into `Media` — that changes the persistence format. |
| 29 | `BottomNavBar`: one `List<Cell(root,pill,icon,label)>` instead of four parallel lists; call `select(initial, animate=false)` directly, drop `initialized`/`root.post` | S10 | APPROVE | `Components.kt:233-296` keeps four index-aligned lists in sync and defers first selection via `root.post`; nothing in `select` needs measured width. |
| 30 | Unify `Magnet`/`LiveMagnet` into one data class; delete the field-by-field shim in DetailActivity | S11 | APPROVE | `DetailActivity.kt:263-266` maps one to the other with a `downloadUrl = ""` sentinel; one type removes the shim and the fake empty string. |
| 31 | `ArrClient`: add generic `states(path, mapper)` / `queueIds(path, field)`; Sonarr/Radarr become one-liners | S14 | APPROVE | `Clients.kt:271-287` vs `310-326` are clones differing only in endpoint/field name; the retry/URL plumbing already lives once in `ArrClient`. |
| 32 | Replace the `navInitiated` flag with a `fromNav` parameter threaded through `goToPage` → `onPageSelected` | S17 | APPROVE | `MainScreen.kt:46,107-111,125`: two callbacks mutating shared state to pass one bit. (Even simpler: always scroll-to-top on page change — the distinction is barely visible.) |
| 33 | One `posterView(ctx, m, …)` helper for the three poster sites (grid card, detail hero, requests thumb) | S5 | APPROVE | Same `if (posterUrl != null) ImageView+ImageLoader else GradientPoster` + clip + description copied at `Components.kt:492-503`, `DetailActivity.kt:120-130`, `RequestsActivity.kt:105-115`. |
| 34 | Requests list: update status pills in place, `staggerIn` only newly added rows, `announceForAccessibility` on change | U1 | APPROVE | `RequestsActivity.kt:94` `removeAllViews()` + :155 `staggerIn` on every 1 s/5 s tick — the whole list flickers and TalkBack focus is destroyed each status change. |
| 35 | Main grid: don't `fadeIn` the container from alpha 0 on data updates; update pills in place where the signature changed | U2 | APPROVE | `MainScreen.kt:335-336` fades the whole grid even when `animate == false`; :327 rebuilds all cards on every poller-driven re-render. |
| 36 | During a new search, keep previous results visible + inline progress; skeleton only on first load of a tab | U3 | APPROVE | `MainScreen.kt:258-259` hides the scroll (`showLoading(loading=true, hasItems=false)`) → results → skeleton → results flash per query. |
| 37 | On refresh failure with cached items: keep stale content + dismissible error banner; full-screen error only when empty | U4 | APPROVE | `showError` (`MainScreen.kt:274,285-291`) sets `scroll.visibility = GONE` and replaces visible data with the error screen. |
| 38 | Honor `reduceMotion()` in all motion helpers + `SwipePager.goToPage` | U5 | APPROVE | Only `AuroraBackground` and `Skeleton` check it; `slideUpIn/fadeIn/popIn/staggerIn/withPressScale` (`Motion.kt:38,52,65,80,95`) and the pager scroll (`SwipePager.kt:37`) animate at full duration. One-line guard per helper. |
| 39 | Requests badge: include the count in `contentDescription` and `announceForAccessibility` on change | U9 | APPROVE | `setBadge` (`Components.kt:162-176`) only swaps the chip text; the action's description stays "Requests" (:155). |
| 40 | Poster card description: append rating + request status; set `importantForAccessibility = NO` on the image inside a described clickable card | U10 | APPROVE | `Components.kt:530` describes title/year/type only; :497 adds a second focusable node. |
| 41 | Save button: `saveBtn.text = "Saving…"` while the async save runs | U11 | APPROVE | `SettingsActivity.kt:120` disables without a label change; the sibling `test()` (:140-141) shows the intended pattern. |
| 42 | Empty-state copy: pick message by whether the query is blank | U12 | APPROVE | `MainScreen.kt:205` always shows "No titles match your search." even with an empty search box. |
| 43 | Toast before `finishDropping()` when the media id can't be resolved | U13 | APPROVE | `DetailActivity.kt:44-45` closes the screen silently. |
| 44 | `hideKeyboard()` in the PosterCard click handler before opening detail | U14 | APPROVE | `MainScreen.kt:331` opens the detail with the IME still up; `hideKeyboard()` (:189-192) is only called on nav taps. |
| 45 | Contrast: add computed `effectiveSurface` (glass over worst-case aurora) + ≥4.5:1 assertions in `DesignTokensTest`; minimal retune of glass/blob alphas | T1 | APPROVE | Tests assert contrast only against the flat `background` (`Tokens.kt:87-95`); with glass (`Glass.kt:24-46`) + blob alpha 52 (`AuroraBackground.kt:94`) on-screen composites measure 2.6–4.3:1 — a real WCAG 1.4.3 failure. Keep it to test + value retune, no restructuring. |
| 46 | Radius consistency: `Skeleton` uses `t.radius.poster`; give `BottomNavBar` its own `radius.navBar` | T7 | APPROVE | `Components.kt:575` skeleton at `radius.button` (16dp) jumps to 20dp when posters load (:496,502); the nav bar borrows `radius.card` (:244,248). Two-line fix. |
| 47 | Press-down animation uses `instantMs` (not `instantMs * 2`) | T11 | APPROVE | `Motion.kt:93-96`: the press-in is slower than the release — M3 press feedback should be the fastest. |
| 48 | Introduce `MediaRepository` interface + domain/data/ui package split + `PlaceholderRepository` (Null Object) | A1, A4, A12 | REJECT | Big refactor: the live/placeholder branching exists at 3-4 call sites (`MainScreen.kt:243-244`, `DetailActivity.kt:248,264`, `AppState.kt:70-79`); an interface hierarchy + package split is abstraction for hypothetical reuse and churns every screen. |
| 49 | Replace `AppState` singleton with constructor-injected `AppContainer` + multi-subscriber listener list | A3 | REJECT | There is exactly one `onConfigChanged` subscriber today (`MainScreen.attach`), so the single-callback slot has no current victim; the refactor touches every screen for a hypothetical second subscriber. |
| 50 | Extract `RequestMediaUseCase` / `StatusRefreshUseCase` classes | A2, A7 | REJECT | Use-case layer is speculative abstraction; the actual ordering bug is the 3-line reorder in row 5. |
| 51 | Extract pure-Kotlin screen controllers for JVM-testable UI layer | A8 | REJECT | Speculative test infrastructure; the risky pure core is already tested, and controllers would duplicate what the views do today. |
| 52 | Split god-object files (`Catalog.kt` → Media/RequestStore/Format, `StatusPresentation.kt`, slim `AppState`) | A9 | REJECT | File moves and extra files are pure churn — no behavior change, and `statusPresentation` (row 8) already removes the one real duplication in Components.kt. |
| 53 | Codebase-wide error-handling strategy migration | Q9 | REJECT | The concrete failures (rows 3, 4) fix the real bugs; rewriting every `runCatching`/`catch` site for "one strategy" is a large diff with no user-visible gain. |
| 54 | Split long `onCreate`/`attach` functions into build* helpers across all screens | Q11 | REJECT | Big mechanical refactor; the concrete fragilities it would help (rows 9, 10) are fixed directly with far less churn. |
| 55 | Token-migration campaigns: chip alphas/poster fractions/timings/aurora values into tokens, kill all raw `dp()` literals, `LayoutTokens`/`TouchTokens`, raw ARGB → tokens, `res/anim` durations → `MotionTokens` | Q4, T3, T4, T5, T6, T8, T9 | REJECT | The runtime theme swap (`PorcelainLight`) is test-only today, so this migrates ~50 literals across every screen for zero user-visible change. The one place values must move (contrast retune) is row 45. |
| 56 | Rename `MATCH`/`WRAP` constants and PascalCase→camelCase component factory names | Q15 | REJECT | Mass rename across the whole package; zero user-visible value. |
| 57 | Rename `AppState.liveMode` → `liveActive` to fix the collision with `AppConfig.liveMode` | Q16 | REJECT | Cosmetic collision; the rename touches every screen and risks confusion mid-diff for no behavior change. |
| 58 | Remove the unused `t` parameter from `col`/`row`; extend `lp` to all margins | Q8 | REJECT | Dead parameter is cosmetic; changing the signatures touches every call site in the app. |
| 59 | Delete the legacy TSV branch in `RequestStoreCore.reload` | S13 | REJECT | Cannot confirm a JSON-format release has shipped to all users; deleting the read path risks silently dropping stored requests for anyone still on TSV. Small code, real data risk. |
| 60 | Pin the resolved IP / re-validate per hop to close the DNS-rebinding TOCTOU in `isSafeExternalUrl` | R5 | REJECT | The validated URLs come from the user's own configured Prowlarr (which already receives the API key); proper pinning through `HttpURLConnection` needs a custom `SocketFactory` — real complexity for a hypothetical attacker. |
| 61 | `CancellationToken`/`disconnect()` plumbing so `interrupt()` aborts in-flight socket I/O | R8 | REJECT | The residual window is bounded by the 20 s read timeout and `shutdownNow` (row 11) ends the leak; threading a token through every HTTP layer is complexity for a bounded case. |
| 62 | Replace the five `!!` on guarded paths with smart-cast locals | R15 | REJECT | All five (`DetailActivity.kt:127`, `RequestsActivity.kt:100`, `Components.kt:500`, `AuroraBackground.kt:39`, `Components.kt:356`) sit behind adjacent null checks on immutable `val`s — churn, no realistic crash. |
| 63 | Route `SettingsActivity.test()` through `RepositoryFactory` | A10 | REJECT | No behavior difference: `test()` already checks `cfg.liveReady` before constructing (`SettingsActivity.kt:140-146`). |
| 64 | Version-stamp `AppState.init`/`saveConfig` posts against the stale-load race | A14 | REJECT | Requires a save landing inside the ~100 ms cold-start decrypt window before any UI exists — a ridiculous edge case. |
| 65 | Delete `PorcelainLight` / `AppTheme.use`/`reset` / `compositeOver` | S12 (partial) | REJECT | They are referenced by `DesignTokensTest` — the design system's own swappability proof and test fixture, not dead production code. |
| 66 | Give `ImageLoader` an injectable `HttpTransport` | A16 | REJECT | Test seam for a component with no tests planned; the default-parameter pattern is fine. |
| 67 | Delete stale `// CI retry trigger` comment in build.gradle; migrate `MainActivity.java` → Kotlin | Q19 | MODIFY | Delete the orphaned comment (`app/build.gradle:43`) — it's dead weight. Skip the Java→Kotlin migration: a 40-line forwarding file has no user-visible win. |

## My APPROVE/MODIFY list

1. StatusPoller busy-guard spans the async refresh; single-thread executor / no unbounded `workers` growth (AppState.kt:94-110)
2. Cancellable delayed `poller.start()` (MainScreen.kt:135,157-161)
3. *arr read methods throw; ping/refresh handle at boundary (Clients.kt:271-323, LiveRepository.kt:64-65)
4. Non-destructive `RequestStoreCore.reload` (Catalog.kt:208-227)
5. Check-before-add in `doRequest` (DetailActivity.kt:218-236)
6. Cache key includes mode / clear on config change + LRU cap (MainScreen.kt:194,344-350)
7. Split `loadItems` into placeholder/live + shared job guard (MainScreen.kt:233-284)
8. Merge `statusColorOf`/`statusLabelOf` (Components.kt:382-394)
9. AppTopBar subtitle handle + typed badge accessor (MainScreen.kt:171-173,341)
10. Single-sourced mode-banner text (Components.kt:644-653, MainScreen.kt:176-184)
11. One shared WorkerScope incl. Settings test thread (MainScreen/DetailActivity/AppState/SettingsActivity)
12. `SearchField.close()` clears debounce callbacks (Components.kt:356)
13. Recycle aurora bitmap on resize (AuroraBackground.kt:38-41)
14. MODIFY — delete `EmphasizedPath`/`glassDrawable`/`renderOnce`/unused elevation tokens; keep test-anchored `PorcelainLight`/`compositeOver`
15. Rename `lightIcons` → `lightBackground` (Insets.kt:24-29)
16. Settings form: typed field refs (drop map + `!!`) + block save on invalid URLs (SettingsActivity.kt:66-131)
17. Settings `adjustResize` + IME chaining + `labelFor`/`setError` (Manifest, Components.kt:614-631)
18. DetailActivity re-render actions in `onResume()` (DetailActivity.kt:185-216)
19. `refreshStatuses` snapshot fallback + `associateBy` maps (LiveRepository.kt:150,164-165)
20. MODIFY — move first prefs read off main thread, guarding `::store.isInitialized` (AppState.kt:33-38)
21. Store mutations/persist off the main thread (AppState.kt:96, DetailActivity.kt:239, RequestsActivity.kt:138)
22. Cap HTTP response body size (Http.kt:81)
23. Only copy `magnet:` URIs to clipboard (DetailActivity.kt:295-300)
24. 301/302/303 redirects → GET without body (Http.kt:89-97)
25. ImageLoader bounded queue + pre-fetch skip (ImageLoader.kt:19-44)
26. Pre-warm Catalog in init worker (AppState.kt:40-49)
27. GradientPoster → gradient + TextView + scrim; delete hand-rolled text layout (Components.kt:438-475)
28. MODIFY — JsonX `long()` accessor + typed `put`; keep `MediaSnapshot` format (Catalog.kt, Config.kt)
29. BottomNavBar single `List<Cell>`, drop `initialized`/`post` (Components.kt:233-296)
30. Unify Magnet/LiveMagnet (DetailActivity.kt:263-266)
31. Generic `ArrClient.states/queueIds` (Clients.kt:271-326)
32. `fromNav` param replaces `navInitiated` (MainScreen.kt:46,107-111)
33. One `posterView()` helper for 3 poster sites
34. Requests list in-place pill updates + stagger only new rows (RequestsActivity.kt:94,155)
35. Main grid: no container fade on data updates (MainScreen.kt:335-336)
36. Keep results visible during search + inline progress (MainScreen.kt:258-259)
37. Keep stale content + inline banner on refresh failure (MainScreen.kt:274,285-291)
38. Honor `reduceMotion()` in all motion helpers + pager (Motion.kt, SwipePager.kt:37)
39. Badge count in contentDescription + announce (Components.kt:155,162-176)
40. Poster card description: rating + status; image not focusable (Components.kt:497,530)
41. Save button "Saving…" label (SettingsActivity.kt:120)
42. Empty-state copy by query-blank (MainScreen.kt:205)
43. Toast before silent `finishDropping()` (DetailActivity.kt:44-45)
44. `hideKeyboard()` on poster click (MainScreen.kt:331)
45. Effective-surface contrast tests + minimal alpha retune (Tokens.kt, DesignTokensTest)
46. Skeleton → `radius.poster`; `radius.navBar` for nav bar (Components.kt:244,575)
47. Press-down uses `instantMs` (Motion.kt:93-96)
67. MODIFY — delete stale gradle comment only; skip MainActivity.java migration

## Notes

- **Dedupe map:** the StatusPoller guard was proposed 4× (Q1/A5/S3/R1) and the cache-key/size issue 4× (Q7/A6/A13/S2/R13) — voted once each. The Settings form appeared in Q13/S16/U6/U7/U8 — voted as two contained packages (rows 16-17). The GradientPoster text layout appeared as a simplicity win (S7) and a contrast bug (T2) — one fix covers both (row 27). The worker-registry dedupe (Q10/S6) also covers the Settings test-thread leak (R7) and the workers-list growth (R10).
- **Where I pushed back hardest:** the architecture report's interface/DI/use-case layer (rows 48-51) and the theming report's blanket token migrations (row 55). Both add abstraction for capabilities the app doesn't currently use (theme swap and second subscribers are test-only/hypothetical); the real bugs inside them are fixed by the small rows instead.
- **Rejected as edge cases or churn:** TSV branch deletion (data risk, unverifiable), DNS-rebinding pinning (disproportionate), `!!` cleanup and renames (cosmetic), init/save race (sub-second cold-start window), CancellationToken plumbing (bounded by existing timeouts).
- Nothing under `app/` was modified; this file is the only artifact.
