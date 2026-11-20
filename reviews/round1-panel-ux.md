# Round 1 — Panel triage — Panelist 3 of 3 (USER EXPERIENCE)

**Reference:** Material Design 3 (m3.material.io) — errors vs empty states, feedback (snackbar/progress), 48dp touch targets, WCAG contrast (4.5:1 text / 3:1 non-text), motion, accessibility (contentDescription, reduce motion).
**Scope:** all six round-1 reports read in full; cited code spot-checked in `app/src/main/java/com/example/appa/`. Verification notes: status-pill contrast recomputed with the repo's own `contrastRatio` math (`Tokens.kt:181-187`) — white on success 1.92:1, warning 1.67:1, info 2.14:1, error badge 2.69:1 (all fail 4.5:1); the `ModeBanner` pattern (18% bg + colored fg) measures 8.25:1 (passes); white on light poster `#AEAEFE` 2.04:1; white on `0xB3000000` scrim 20.5:1 (rating chip is fine). Greps confirm: zero `contentDescription`, zero `ANIMATOR_DURATION_SCALE`, zero `imeOptions`, `usesCleartextTraffic="true"` (`AndroidManifest.xml:5`), `MainActivity.java` has no `onDestroy`.
**Vote rule reminder:** a change passes the panel at ≥2 APPROVE/MODIFY; my votes below are my own.

## Deduped change list

1. **C01 — Remove blocking network I/O from `LiveRepository` constructor (lazy `audiobookIndexerIds`).**
   Sources: architecture H4, robustness #1, simplicity #5. Severity: **Critical**.
   `LiveRepository.kt:51-55` does a synchronous HTTP call in `init`, constructed on the main thread from `AppState.init`/`saveConfig` (`AppState.kt:29-30,38`) in every Activity's `onCreate`. The inner `runCatching` (`Clients.kt:178`) swallows `NetworkOnMainThreadException`, so in practice it's a multi-second UI freeze per launch/Save (25s connect timeout, `Http.kt:22` → ANR) plus the audiobook indexer filter silently disabled (`LiveRepository.kt:99`).
   **Vote: APPROVE.** Real user-facing freeze/ANR + silently broken audiobook search. Fix as proposed: resolve lazily on first `magnetsFor` (already off-main-thread).

2. **C02 — Lifecycle teardown: MainScreen ticker, search debounce, in-flight renders, skeleton animator.**
   Sources: architecture M5/M6, quality M7, robustness #2/#12/#13, simplicity (ticker dup). Severity: **Critical**.
   `MainScreen.kt:44-58,123` reschedules itself forever with no `removeCallbacks`; `MainActivity.java` has no `onDestroy`; each tick spawns a raw `Thread`. `RequestsActivity.kt:64-66` posts `render()` with no destroyed-guard (its ticker *is* removed, `RequestsActivity.kt:78-81` — clean). `Components.kt:291-300` debounce fires after destroy. `Components.kt:521-528` skeleton `INFINITE` animator never cancelled.
   **Vote: APPROVE.** Leaked ticker keeps doing network I/O into dead views after every navigation/rotation. Add `MainScreen.detach()` from `MainActivity.onDestroy`, single-threaded scheduler, `isAttached`/`isDestroyed` guards, cancel skeleton animator in `onDetachedFromWindow`.

3. **C03 — Distinguish network error from empty results in `MainScreen`, add Retry.**
   Sources: ux #1, robustness #7 (partial). Severity: **High**.
   `MainScreen.kt:179,203` do `runCatching { … }.getOrElse { emptyList() }`, so a failed TMDB/OpenLibrary call renders the *search* empty state "No titles match your search." (`MainScreen.kt:135,146-149`) — error mislabeled as empty, no retry.
   **Vote: APPROVE.** Textbook M3 errors-vs-empty-state violation. Keep the `Throwable`, show a distinct error `EmptyState` ("Couldn't load titles") with a Retry `PrimaryButton`; reserve "no matches" for successful-but-empty search.

4. **C04 — Request double-submit guard + honest duplicate toast.**
   Sources: ux #2, robustness #14. Severity: **High**.
   `DetailActivity.kt:180,184-203`: button stays enabled during the network call (double-tap → duplicate Sonarr/Radarr entries); `store.create(...)` returning `null` on duplicate is ignored at `:191-192`, so the toast always says "Added to …". Feedback is a bare Toast.
   **Vote: APPROVE.** Classic double-submit defect + mislabeled feedback. Disable button + "Requesting…" label during the call; branch on `null` → "Already requested"; snackbar instead of Toast (see C15).

5. **C05 — Fix WCAG contrast of status pills, count badge, and `GradientPoster` text; add a contrast unit test.**
   Sources: ux #3, theming #1/#2, quality L8. Severity: **High**.
   `Chip` default fg hard-coded white (`Components.kt:337`); `StatusPill` uses solid status bg (`Components.kt:348-367`, used at `:461`, `RequestsActivity.kt:119`) → measured 1.67–2.14:1; badge white on error → 2.69:1 (`:149,:161-165`); `GradientPoster` paints `Color.WHITE` (`:390,:393,:406`) → 2.04:1 on light gradients like `#AEAEFE` (`Catalog.kt` colorPairs).
   **Vote: APPROVE.** Verified failing 4.5:1. Use the in-repo `ModeBanner` pattern (`:576-582`, measured 8.25:1): `Chip(ctx, label, withAlpha(color, 0.18f), color, t)`; `Chip` default `fg` → `t.colors.textOnAccent`; paint poster text with `textOnAccent`; add a `DesignTokensTest` case asserting `contrastRatio(fg,bg) >= 4.5` for every chip pair.

6. **C06 — 48×48dp minimum touch targets.**
   Sources: ux #4. Severity: **High**.
   `CircleIconButton` is `dp(40)` (`Components.kt:143`) yet carries every top-bar action and the 🗑 (`MainScreen.kt:76-81`, `DetailActivity.kt:47`, `RequestsActivity.kt:122`); buttons ≈39–46dp (`py = dp(10/12)`, `Components.kt:181,194,205`); search ✕ is a bare TextView (`:309-312`); `FormField` ≈43dp vs M3 56dp field (`:549-563`).
   **Vote: APPROVE.** M3/Android accessibility 48dp minimum. `dp(48)` icon button, `minimumHeight = 48dp` on buttons/fields, pad the ✕ into a 48dp hit area.

7. **C07 — `DetailActivity`: replace `recreate()` with targeted re-render; fix the recreate loop; honor hero height in `GradientPoster.onMeasure`.**
   Sources: ux #2/#11, robustness #3, simplicity #4, architecture H3 (partial), quality M5. Severity: **High**.
   `recreate()` at `DetailActivity.kt:98,176,196,202` rebuilds the whole screen, replays entrance animations and re-fires the Prowlarr search. The guard at `:96-98` (`d != media`) still loops forever when TMDB returns differing rating/year but empty genres+overview — infinite recreate loop. `:102` re-reads `mediaById` immediately after `.start()` (racy). `GradientPoster.onMeasure` (`Components.kt:397-400`) ignores an EXACTLY height spec, so the placeholder hero renders 1.5×width (~490dp) vs the 300dp image hero (`DetailActivity.kt:59,61-69`).
   **Vote: APPROVE.** Fix loop via a `detailsFetched` id-set (or in-place view update — preferred), replace `recreate()` with `renderActions(...)` re-render (already exists, `:165-183`), honor `MeasureSpec.getSize(hSpec)` when EXACTLY.

8. **C08 — Copy-magnet: validate redirect target, never put the API key on the clipboard, give in-progress feedback.**
   Sources: ux #12, robustness #5, simplicity (Http.kt:49-50 identical branches). Severity: **High**.
   `Http.kt:45-51` returns any 3xx `Location` (even an HTML page) or the raw `downloadUrl`; `DetailActivity.kt:243-245` does `getOrElse { mag.downloadUrl }` and copies it — Prowlarr URLs embed `?apikey=…` (`Clients.kt:177`), so a failed resolve copies the secret to the clipboard. No feedback during an up-to-55s resolve (`Http.kt:22-23`).
   **Vote: APPROVE.** Accept `Location` only if `magnet:` or `.torrent`; on failure show an error (and strip `apikey` from anything ever copied); disable the button + "Resolving…" until posted back.

9. **C09 — Prowlarr retry/backoff (2–3 attempts) + re-resolve blank indexer IDs.**
   Sources: robustness #4. Severity: **Medium-High**.
   `Http.kt:55-57` single attempt, throws on any non-2xx; `LiveRepository.kt:53,99` resolves `audiobookIndexerIds` once — if Prowlarr was down it stays `""` forever and audiobook searches hit all indexers; any 5xx → header "search failed" (`DetailActivity.kt:214-216`) and an empty magnet list.
   **Vote: APPROVE.** Transient failures currently masquerade as "no results" — a user-facing flakiness bug. Retry on 5xx/IOException only, re-resolve when blank.

10. **C10 — Make `pingAll()` checks real (remove `|| true` / `; true`).**
    Sources: quality M12, simplicity #6, robustness L1, architecture L1. Severity: **Medium**.
    `LiveRepository.kt:59-61`: `prowlarr.search("test","").isNotEmpty() || true` and `sonarr.seriesStates(); true` are tautologies → Settings "Test connection" always shows Prowlarr ✓ when configured (`SettingsActivity.kt:103-124`).
    **Vote: APPROVE.** Mislabeled feedback — the test tells the user a broken service is fine. Use `runCatching { … }.isSuccess` or a real `ping()`.

11. **C11 — Settings "Test connection" UX: wrapping results, per-service progress, bounded parallel pings.**
    Sources: ux #7. Severity: **Medium**.
    `testResults` is a single non-wrapping horizontal row (`SettingsActivity.kt:70`) — 5 chips clip on narrow screens; static "Testing…" (`:111`) while `pingAll()` (`:113`) runs up to 5 sequential calls × (25s connect + 30s read) with no cancel.
    **Vote: APPROVE.** Make the row wrap/vertical, set each chip to "name…" then ✓/✗, run pings in parallel with a ~30s overall cap.

12. **C12 — Validate service URLs at save; inline field errors.**
    Sources: ux #8. Severity: **Medium**.
    `Config.kt:24-33` `normalized()` only trims; `liveReady` (`:43-45`) only checks non-blank → `"192.168.178.42:9696"` without scheme is accepted, toast says "Saved — live mode on" (`SettingsActivity.kt:97-98`), then every live call fails and the user lands in C03's fake empty state.
    **Vote: APPROVE.** Require `http(s)://` scheme; show M3 text-field error text under the offending field after interaction (`SettingsActivity.kt` `field()` blocks).

13. **C13 — Scope search results to the current tab; fix search cache key/timestamp.**
    Sources: ux #9, robustness #15. Severity: **Medium**.
    Placeholder `Catalog.search` searches all types (`Catalog.kt:208-218`), live `LiveRepository.search` merges movies+series+books (`LiveRepository.kt:73-82`); results render inside the current tab's grid (`MainScreen.kt:197-206`) — movies appear under "Series". Cache keyed by query only (`MainScreen.kt:190,207`) and `loadedAt` stamped pre-fetch (`:184`).
    **Vote: APPROVE.** Wrong-content-in-tab is a real UX bug. Filter by `currentTab` (or at least a type chip on results when searching); key cache on `tab to query`, stamp after fetch.

14. **C14 — `RequestsActivity`: signature-diff render so the list doesn't re-animate every poll.**
    Sources: ux #5. Severity: **Medium**.
    `RequestsActivity.kt:132` unconditionally calls `list.staggerIn(stepMs = 30)`; the live ticker posts `render()` every 5s (`:64-68`) even when nothing changed. `MainScreen.kt:219-227` already has the diff pattern.
    **Vote: APPROVE.** M3 motion: motion should communicate change, not run constantly. Skip `render()`/`staggerIn` when the joined `id:status` signature is unchanged.

15. **C15 — Confirm destructive actions; snackbar with Undo instead of bare Toast.**
    Sources: ux #6. Severity: **Medium**.
    Delete/cancel (`RequestsActivity.kt:122-125`, `DetailActivity.kt:173-176`) acts immediately on tap with only a Toast. M3: dialogs confirm destructive actions; snackbars are the preferred in-context feedback over system Toasts.
    **Vote: APPROVE.** `AlertDialog` "Remove request for *title*?" (or snackbar + Undo); replace request-flow Toasts (`:186,195,201`) with in-context snackbars.

16. **C16 — Respect the system "Remove animations" setting.**
    Sources: ux #13, architecture L4, robustness #11. Severity: **Medium**.
    Grep: no `ANIMATOR_DURATION_SCALE` anywhere. Aurora backdrop runs a 24s INFINITE animator (`AuroraBackground.kt:46-56`); skeleton shimmer INFINITE (`Components.kt:521-528`).
    **Vote: APPROVE.** Accessibility requirement. If `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, render the aurora once and show skeletons static; also cancel the skeleton animator in `onDetachedFromWindow` (fold into C02).

17. **C17 — `contentDescription` on all non-text content (posters, icon buttons).**
    Sources: ux #15. Severity: **Medium**.
    Grep: zero `contentDescription` in the app. Bare `ImageView`s (`Components.kt:440-446`, `DetailActivity.kt:61-66`, `RequestsActivity.kt:101-106`), canvas-drawn `GradientPoster` text, emoji-only `CircleIconButton`s (`MainScreen.kt:76-81`, `DetailActivity.kt:47`, `RequestsActivity.kt:122`).
    **Vote: APPROVE.** `img.contentDescription = "${m.title} poster"`, `"Requests"`/`"Settings"`/`"Close"`/`"Delete request"` on the icon buttons, `contentDescription = m.title` on the poster frame.

18. **C18 — Make `LiveRepository` shared state thread-safe.**
    Sources: architecture M2, robustness #6. Severity: **High**.
    `mediaCache` (`LiveRepository.kt:180-195`) is a plain `MutableMap` written on main and read/mutated from ticker/load/detail threads; scalars `:47-49` unsynchronized → `ConcurrentModificationException`/torn reads surfacing as crashes or flaky statuses.
    **Vote: APPROVE.** `ConcurrentHashMap` + `@Volatile` scalars, or confine repo calls to the single executor from C02. (The repo — not `AppState.mediaById`, `AppState.kt:45` — should own the cache→catalog fallback.)

19. **C19 — Atomic `(config, repo)` swap in `AppState`; eliminate `repo!!` at call sites.**
    Sources: architecture H1/H2, robustness #7. Severity: **High**.
    `AppState.kt:22,37-41`: `liveMode` reads `config` while `repo` is assigned separately → a thread can see `liveMode==true, repo==null`; six `AppState.repo!!` sites (`MainScreen.kt:179,203`, `DetailActivity.kt:95,190,210,244`) then throw inside `runCatching` → silent empty grid (feeds C03).
    **Vote: APPROVE** the atomic `@Volatile` holder + replacing `!!` with graceful error handling routed into C03's error state.

20. **C20 — Full architecture rewrite: `MediaRepository` interface + `PlaceholderRepository` + UDF state holders (screens as pure renderers).**
    Sources: architecture H1/H3, M1/M4 (also `PosterCard`→`AppState` at `Components.kt:459`, dual `Magnet`/`LiveMagnet` models). Severity: Medium (internal).
    **Vote: MODIFY.** Approve the *non-null* repository abstraction (that's the substance of C19). Defer the UDF/state-holder rewrite and the `PosterCard` param pass-through: no user-facing defect beyond what C03/C07/C18 already fix, and it's the largest diff in the set — sequence it after the UX bug fixes, don't bundle.

21. **C21 — `ImageLoader`: timeouts, bounded queue, skip detached views.**
    Sources: robustness #8. Severity: **Medium**.
    `ImageLoader.kt:19,32-34`: `URL(url).openStream()` with no connect/read timeout on a 3-thread pool — one stuck TMDB/OpenLibrary connection permanently occupies pool threads and later posters silently never load; the main-thread callback (`:38-44`) doesn't check attachment.
    **Vote: APPROVE.** Route through `realHttp` (25s/30s timeouts, `Http.kt:22-23`) or set explicit timeouts; guard `!view.isAttachedToWindow` in the callback.

22. **C22 — Network security config: per-host cleartext instead of global; `X-Api-Key` header instead of `?apikey=` in URLs.**
    Sources: robustness #9. Severity: **Medium**.
    `AndroidManifest.xml:5` `usesCleartextTraffic="true"` disables TLS default for every host; keys go in URL query strings (`Clients.kt:177,215,218,272,275`) so *arr access logs record secrets.
    **Vote: APPROVE.** `res/xml/network_security_config.xml` limited to LAN ranges + `X-Api-Key` header (supported by Sonarr/Radarr/Prowlarr). The clipboard half of this is C08.

23. **C23 — `SecureStore` key-invalidation: tell the user instead of silently wiping config.**
    Sources: robustness #10. Severity: **Low-Medium**.
    `SecureStore.kt:23-35,46-50,57-60`: on `KeyPermanentlyInvalidatedException` a new key is generated, decrypt throws, `runCatching` swallows → user silently gets an empty `AppConfig` (loses live setup with no message).
    **Vote: APPROVE.** Catch the exception explicitly, delete stale ciphertext, surface "config was reset".

24. **C24 — In-progress feedback for background refresh and search.**
    Sources: ux #10, #18. Severity: **Medium**.
    `MainScreen.kt:146-149,174,197-199`: when stale items exist the skeleton is hidden, so the 45s-cache refresh and in-flight search give zero visual feedback.
    **Vote: APPROVE.** M3 progress-indicator guidance: indicate activity in progress — slim top progress bar over the pager during refresh, small in-field spinner while a search network call is in flight.

25. **C25 — Make the placeholder `ModeBanner` tappable → opens Settings.**
    Sources: ux #16. Severity: **Low**.
    `MainScreen.kt:81` adds the banner as plain text while its own copy says "tap ⚙ to connect your services" (`Components.kt:576-582`); the ⚙ is a separate 40dp button (see C06).
    **Vote: APPROVE.** One-line `setOnClickListener { openRisingTo(SettingsActivity::class.java) }` + `pressable()`.

26. **C26 — Requests count label must match rendered rows.**
    Sources: ux #17. Severity: **Low**.
    `RequestsActivity.kt:88` shows `requests.size` as "N total" but `:93` silently skips requests whose media no longer resolves → "5 total" with 4 cards.
    **Vote: APPROVE.** Count only rendered rows, or render a "(title unavailable)" card.

27. **C27 — Status-bar icon appearance derived from theme luminance; `themes.xml` dedupe.**
    Sources: theming #5, architecture L2 (partial), quality L9. Severity: **Low**.
    `Insets.kt:23` hard-codes dark (light-icon) appearance; `themes.xml:4-8` restates token colors. Latent today (runtime swap never happens — see C30), but the fix is tiny.
    **Vote: MODIFY.** Approve the small `Insets.kt` fix: `appearance` from `luminance(AppTheme.current.colors.background) < 0.5` (helper exists, `Tokens.kt:170-176`). Defer the theme-swap machinery itself to cleanup.

28. **C28 — "Magic numbers → tokens" sweep.**
    Sources: quality H1/L5, architecture M3/L2, theming #3,6-14. Severity: Low (as a UX item).
    **Vote: MODIFY.** Approve tokenizing only values with user-visible effect: contrast color pairs (C05), touch-target sizes (C06), `0xB3000000` chip scrim (`Components.kt:455`, `DetailActivity.kt:79-80`), 350ms debounce (`Components.kt:321`). Reject the wholesale dp/radius/spacing migration (e.g. `roundedClip(12)` `RequestsActivity.kt:100`, `dp(6)`/`dp(10)` gaps) — zero user-visible change, large churn.

29. **C29 — Internal dedup/refactor bundle** (ScreenScaffold atom, `bg{}` helper for `Thread{…handler.post{…}}`, `roundedClip` reuse, badge/`PosterVisual`/`RatingChip` atoms, `ArrClient` base, `loadItems` collapse, `ChipFlow`, `BottomNavBar.select` alpha-guard + `getChildAt` refs, `TmdbClient.toMedia` when-merge, `SwipePager.onDown`, `SearchField` hoisting, Settings `FieldSpec`, `DetailActivity.onCreate` split).
    Sources: quality H2/H3/M1-M9/M13/L3/L4, simplicity #1/#9 + additional. Severity: Low.
    **Vote: MODIFY.** No standalone refactor round — these change nothing user-visible. Fold each into the change that already touches that path (C02/C03/C07); the `loadItems` collapse should happen *while* implementing C03/C13 so error handling isn't written twice.

30. **C30 — Dead-code deletion** (`LinearOutSlowIn`, `crossfadeOut`, `glassDrawable`, `dpF`/`sp`, `renderOnce`, `setTextSilently`, `pageView`, `magnetOf`, `fromSlug`, `setExternal`, `ElevationTokens`, `PorcelainLight`+`AppTheme.use/reset`, `AppState.catalog`, `RawTorrent.indexerId/publishDate`, dead branches in `Glass.kt:38`, `Skeleton` when, `Http.kt:49-50`).
    Sources: quality M10/M11, architecture L3, simplicity #10. Severity: Low.
    **Vote: REJECT (for this round).** Not wrong, but zero user-visible effect — my persona rejects cosmetic-only churn mixed into UX fixes. Schedule as one dedicated cleanup PR after the bug fixes land.

31. **C31 — Naming-convention renames (PascalCase atoms → camelCase, `t`→`tokens`, `lp`→`layoutParams`) and file splits (`Components.kt` 587 lines, `Clients.kt`).**
    Sources: quality L1/L2/L6. Severity: Low.
    **Vote: REJECT.** Pure mechanical churn across every file; no user-facing effect; makes the real fixes harder to review.

32. **C32 — Align motion with M3 tokens** (standard curve `cubic-bezier(0.2,0,0,1)` / emphasized `(0.05,0.7,0.1,1)` instead of legacy M2 `FastOutSlowIn`; `longMs` 520→≤500 `long2`; hero `popIn` overshoot).
    Sources: ux #14, theming #9/#10. Severity: **Low**.
    `Motion.kt:23-26,60-67`, `Tokens.kt:117` (`longMs = 520` drives the hero entrance).
    **Vote: MODIFY.** Motion is user-perceived, so I want the durations capped at M3 `long2 = 500ms` and the M3 standard curve for enters — but treat it as polish, not a defect; current motion works. Keep or soften the overshoot, panel's call.

33. **C33 — Other internal-only items:** bottom-nav 80dp container (ux #19 — compact bar with ≥48dp cells is a deliberate, acceptable pattern), `RequestStoreCore` TSV→JSON + legacy 5-field drop (simplicity #7 — internal storage format, migration risk, no UX gain), single `Magnet` model / delete `LiveMagnet` (architecture M4 — internal types).
    **Vote: REJECT** as UX changes — none affect the user; revisit in a cleanup/architecture round.

34. **C34 — Enable `StrictMode` `detectAll().penaltyLog()` in debug builds.**
    Sources: robustness "suggested guardrail". Severity: Low.
    **Vote: APPROVE.** Debug-only, zero release impact, and it would have flagged C01/C02 immediately. Guard against regressions.

## Small improvements (regardless of votes)

1. **IME "Search" action + hide keyboard on submit** — `SearchField`'s `EditText` has no `imeOptions`/`setOnEditorActionListener` anywhere (grep confirmed); set `imeOptions = ACTION_SEARCH` and hide the soft keyboard when a query fires, so results aren't covered by the keyboard (`Components.kt:296-308`, `MainScreen.kt:88-91`).
2. **Double-tap guard on poster cards** — `PosterCard` opens `DetailActivity` on every click (`MainScreen.kt:231-233`); a double-tap starts two detail activities. Disable/guard on first click (same class of bug as C04).
3. **Scroll to top on tab switch** — `pager.onPageSelected` (`MainScreen.kt:106-110`) calls `loadItems()` but leaves the `ScrollView` wherever the previous tab was scrolled; `scroll.smoothScrollTo(0, 0)` per page keeps context predictable.
4. **Retry action in the magnet error/empty state** — "🧲 Magnets — search failed: …" (`DetailActivity.kt:214-216`) and the "No magnets" `EmptyState` (`:229-231`) are dead ends; add a small Retry `SecondaryButton` re-calling `loadMagnets` (complements C03/C09).
5. **Tappable request cards** — in `RequestsActivity` only the 🗑 is interactive (`RequestsActivity.kt:122-131`); make the whole card open `DetailActivity` for that title, matching the main grid's behavior (`MainScreen.kt:231-233`).

---
*Panelist 3 (UX) — round 1. Read-only: only this file was written; `app/` and `.github/` untouched.*
