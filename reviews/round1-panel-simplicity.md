# Round 1 — Panel triage, Panelist 2 of 3 (BREVITY & SIMPLICITY)

**Persona reference:** John Ousterhout, *A Philosophy of Software Design* — simplicity, deep modules,
delete code, "define errors out of existence", "simplest thing that works", pass-through variables are
an anti-pattern. Priority: **REJECT changes that add code/abstraction; APPROVE deletions and
simplifications; MODIFY over-engineered proposals down to the minimal version.**

**Scope:** read all six round-1 reports (quality, architecture, simplicity, robustness, theming, ux)
and spot-checked the cited code in `app/src/main/java/com/example/appa/` (constructor I/O, ticker,
`recreate()` sites, `pingAll` tautologies, dead symbols via grep, magic numbers, duplication sites).
Votes below are my own; the 2-of-3 panel rule is not computed here.

---

## Deduped change list

Sources key: Q=quality, A=architecture, S=simplicity, R=robustness, T=theming, U=ux.

1. **LiveRepository constructor does blocking network I/O on the main thread** → make `audiobookIndexerIds` lazy (resolve on first `magnetsFor`, which already runs off the UI thread). `LiveRepository.kt:51-55` (init block), constructed on main thread at `AppState.kt:29-30,38`. Sources: A-H4, S-5, R-1. Severity: **High** (NetworkOnMainThreadException/ANR). **Vote: APPROVE** — deletes a side effect from a constructor; simplest possible fix (`by lazy`), exactly "define errors out of existence".

2. **Status ticker: extract one shared ticker with start/stop; MainScreen has no teardown and spawns unbounded overlapping threads.** `MainScreen.kt:44-58` (ticker), `MainScreen.kt:123` (postDelayed), `MainActivity.java` (no onDestroy), duplicated at `RequestsActivity.kt:63-76` (which does tear down, L78-81). Sources: R-2, A-M5/M6, Q-M7. Severity: **High** (handler/thread leak, races). **Vote: APPROVE** the merged minimal form — one small `StatusTicker(handler, onTick)` used by both screens, `stop()` in `onDestroy`, plus a `refreshing` flag so ticks can't overlap. Do **not** adopt R-2's `ScheduledExecutorService` — that's more machinery than the problem needs.

3. **DetailActivity: replace all `recreate()` with targeted in-place re-render; delete the racy re-read.** `DetailActivity.kt:98` (details→recreate, loop still possible for empty-metadata items), `:102` (racy re-read right after `Thread{}.start()`), `:176/:196/:202` (cancel/request→recreate, replays animations + re-fires Prowlarr search). Sources: S-4, R-3, U-2/U-11, A-H3(partial). Severity: **High**. **Vote: APPROVE** — `renderActions` already exists and is rebuild-safe; updating hero/meta text in place deletes both the loop risk and ~4 lines of band-aid. Pure simplification.

4. **`pingAll()` tautologies** — `LiveRepository.kt:60` (`…isNotEmpty() || true`), `:61/:62` (`…; true`). Sources: Q-M12, S-6, A-L1, R-L1. Severity: Low. **Vote: APPROVE** minimal form: `runCatching { prowlarr.search("test", "") }.isSuccess` — no new client methods needed.

5. **LiveRepository shared mutable state unsynchronized** — `mediaCache` (`LiveRepository.kt:180-183`), `cachedMovieTmdbIds`/`cachedSeriesTmdbIds` (`:47-49`) hit from ticker + load + detail threads. Sources: R-6, A-M2. Severity: Medium. **Vote: APPROVE** minimal: `ConcurrentHashMap` for the cache, `@Volatile` for the two scalar sets. (Skip the "confine everything to one executor" variant — more restructuring than needed.)

6. **AppState: atomic (config, repo) + remove the six `repo!!`** at `MainScreen.kt:179,203`, `DetailActivity.kt:95,190,210,244`; `AppState.kt:16-17,29-41` non-atomic. Sources: R-7, A-H1/H2. Severity: Medium. **Vote: MODIFY** — do the minimal version: one `@Volatile` holder updated atomically in `saveConfig`, and `repo?.… ?: return/getOrElse` with a visible error state at call sites (ties into #8). **Reject A-H1's full `MediaRepository` interface + `PlaceholderRepository` + constructor-injected screens**: for exactly two modes in a 3,200-line app that's a new abstraction layer, not a simplification.

7. **PosterCard reads `AppState` global** (`Components.kt:459`) → add `status: RequestStatus? = null` param, pass from `MainScreen.kt:231`. Source: A-M1. Severity: Low. **Vote: APPROVE** — 3-line change, removes a global dependency from the atom layer.

8. **MainScreen conflates network error with empty results** — `runCatching{…}.getOrElse { emptyList() }` at `MainScreen.kt:179,203` renders "No titles match your search" (`MainScreen.kt:135`). Source: U-1. Severity: Medium. **Vote: APPROVE** — keep the `Throwable`, show a distinct error `EmptyState` + Retry button. Small, and it's real correctness (a failed call currently lies to the user).

9. **Detail request/copy feedback**: disable Request button ("Requesting…") during submit (double-tap currently double-adds to *arr, `DetailActivity.kt:180-203`); "Already requested" when `store.create` returns null (`:188-196`, R-14); "Resolving…" label on Copy magnet (`:241-251`, U-12). Sources: U-2/U-12, R-14. Severity: Medium. **Vote: APPROVE** — each is 2-3 lines on existing buttons.

10. **`magnetFromDownloadUrl` accepts any 3xx Location and the failure path copies a URL containing the Prowlarr apikey to the clipboard.** `Http.kt:45-51`, fallback `getOrElse { mag.downloadUrl }` at `DetailActivity.kt:242-245`; apikey in query at `Clients.kt:177`. Sources: R-5, S-ridiculous-1. Severity: **High** (secret leak). **Vote: APPROVE** — return `loc` only if it starts with `magnet:` or ends with `.torrent`, else `""`; on failure toast an error, never copy `downloadUrl`.

11. **Prowlarr resilience: re-resolve blank `audiobookIndexerIds` lazily; small retry on search/resolve.** `LiveRepository.kt:53,99`, `Http.kt:55-57`, `Clients.kt:186-199`. Source: R-4. Severity: Medium. **Vote: MODIFY** — the lazy re-resolve folds into #1 (free); keep the retry to a ~4-line `fun <T> withRetry(attempts: Int = 2, block: () -> T)` used at the two Prowlarr call sites only. Skip exponential-backoff frameworks.

12. **ImageLoader: no timeouts, pool threads can hang forever, callback fires on detached views.** `ImageLoader.kt:19,32-44`. Sources: R-8, A-clean-note. Severity: Medium. **Vote: APPROVE** minimal — set `connectTimeout`/`readTimeout` on the `URLConnection` (2 lines) and add `if (!view.isAttachedToWindow) return@post`; do **not** rewire it through `HttpTransport` (more churn than value here).

13. **Global cleartext + apikey in URL query strings.** `AndroidManifest.xml:5` (`usesCleartextTraffic="true"`), `Clients.kt:177,215,218,272,275` (`?apikey=`). Source: R-9. Severity: Medium. **Vote: MODIFY** — ship `res/xml/network_security_config.xml` (cleartext only for private/LAN ranges) now; the `X-Api-Key` header move is small and mechanical but touches every client — approve it, second priority.

14. **SecureStore: key invalidation silently wipes config.** `SecureStore.kt:23-35,46-50,57-60`. Source: R-10. Severity: Low. **Vote: APPROVE** — catch the invalidation explicitly, delete stale ciphertext, tell the user "config was reset" instead of silently returning `AppConfig()`.

15. **Lifecycle hygiene bundle**: Skeleton's infinite animator never cancelled (`Components.kt:512-530` → cancel in `onDetachedFromWindow`, mirroring `AuroraBackground.kt:57-61`); RequestsActivity posted render lacks `isDestroyed` guard (`RequestsActivity.kt:64-66`); SearchField declares `handler`/`pending` after `root` and uses `getChildAt(1)` (`Components.kt:283-328`) + debounce fires after destroy (`:321`). Sources: R-11/12/13, A-L4, Q-L3. Severity: Medium. **Vote: APPROVE** — all small, mostly deletions/reordering.

16. **MainScreen.loadItems/renderCurrent: one cache-fetch helper, one signature map keyed `"$tab|$query"`, cache key = tab+query, timestamp after fetch, filter search results to current tab.** `MainScreen.kt:152-215` (two ~identical branches), `:34-35,219-227` (two signature caches), `:184,190,207` (stale-key/timestamp bugs), `Catalog.kt:208-218` + `LiveRepository.kt:73-82` (search ignores tab). Sources: Q-M8, S-1/S-2, R-15, U-9. Severity: Medium. **Vote: APPROVE** with one amendment: keep the diff check but collapse to a single `signatureByKey` map — deleting diffing entirely (S-2's option) would rebuild the grid on every 5s poll tick and reintroduce flicker.

17. **Magic numbers → named `private const val`s at the top of each file; add only `colors.chipScrim` (0xB3000000) as a token.** Sites: `Components.kt:143,151,166,181,205,234,249,251,296,321,344,442,454-461,499`; `DetailActivity.kt:59,72,79-81`; `RequestsActivity.kt:100-116`; `MainScreen.kt:127-133,169,191`; `Motion.kt:36,72,77,91-92`; `AuroraBackground.kt:36-47,87`; `Http.kt:27-28`; etc. Sources: Q-H1/L5/L11, T-3/6/7/8/13/14, A-M3/L2. Severity: Medium. **Vote: MODIFY** — named consts per file (and `chipScrim`) capture ~all the value with zero new abstraction; **reject the full `SizeTokens`/`MotionTokens`-group expansion** (Q-H1/T-13) — a new token zoo is more interface, not less.

18. **Extract duplicated blocks**: `bg {}` helper for the `Thread{runCatching{…}; handler.post{…}}` pattern (~10 sites: `MainScreen.kt:48,178,202`; `DetailActivity.kt:94,188,209,242`; `RequestsActivity.kt:64`; `SettingsActivity.kt:113`); `ScreenScaffold` atom for the 4 identical screen scaffolds (`MainScreen.kt:66-72`, `DetailActivity.kt:37-43`, `RequestsActivity.kt:31-37`, `SettingsActivity.kt:28-34`); reuse `roundedClip` instead of re-implemented outline providers (`Components.kt:229-234`, `DetailActivity.kt:51-57`); one `PosterVisual` for the image-vs-gradient branch (`Components.kt:440-449`, `DetailActivity.kt:61-70`, `RequestsActivity.kt:100-107`); `badgeChip` factory (`Components.kt:149-153` vs `160-170`); `RatingChip` (`Components.kt:455`, `DetailActivity.kt:79`); `chips.chunked(6)` loop (`DetailActivity.kt:110-131`); split `DetailActivity.onCreate` (L36-152) into `buildHero/buildMetaCard/buildActionsRow/buildMagnetsSection`; `ArrClient` base for Sonarr/Radarr (`Clients.kt:225-238` vs `283-296`); `FieldSpec` list for Settings (`SettingsActivity.kt:56-64` vs `collect()` L90-101). Sources: Q-H2/H3/M1-M6/M9/M13, S-lows, T-12. Severity: Medium. **Vote: APPROVE** — every one of these is a pure deletion/extraction; `ArrClient` in particular is Ousterhout's "deep module, simple interface".

19. **Delete the pass-through `t: DesignTokens = AppTheme.current` parameter on atoms** and read `AppTheme.current` in the body (no call site ever passes a non-default; also fixes the snapshot inconsistency Q-L9/A-L2). `Components.kt` throughout. Source: S-3, Q-L9, A-L2. Severity: Medium. **Vote: APPROVE** — textbook pass-through variable; shorter signatures everywhere, one consistent theme policy.

20. **GradientPoster → a TextView over a gradient drawable** (`maxLines=3`, `ellipsize=END`), deleting the ~15-line hand-rolled word-wrap incl. the impossible `removeSuffix("…")` guard; paint with `t.colors.textOnAccent` instead of `Color.WHITE`; honor `EXACTLY` height in `onMeasure`. `Components.kt:386-428` (wrap L405-421, colors L390/393/406, onMeasure L397-400), hero-height inconsistency `DetailActivity.kt:59-72`. Sources: S-8, T-2, U-11. Severity: Medium. **Vote: APPROVE** — Android already does the wrapping; this deletes the fiddliest drawing code in the app and fixes the hero aspect bug.

21. **BottomNavBar.select: break the callback cycle with `if (i != selected)` (init `selected = -1`) instead of alpha-sniffing, and keep direct icon/text references instead of `getChildAt` casts.** `Components.kt:262-283` (guard L263, casts L269-271). Sources: S-9, Q-L4. Severity: Low. **Vote: APPROVE** — deletes a state-sniffing guard and fragile positional casts.

22. **Delete dead code and dead branches**: `Motion.kt:24,102-108` (+`FastOutLinearIn` L25, used only by `crossfadeOut`), `Glass.kt:18-26` `glassDrawable`, `Glass.kt:38` dead `if (darkBg) … else …` ternary, `Density.kt:10-11` `dpF`/`sp`, `AuroraBackground.kt:99` `renderOnce`, `Components.kt:329-332` `setTextSilently`, `Components.kt:515-521` 3-arm `when` with identical bodies, `SwipePager.kt:31` `pageView`, `Clients.kt:208` `magnetOf`, `Catalog.kt:20-22` `fromSlug`, `Catalog.kt:109-116` `setExternal`, `Tokens.kt:73-77,87` whole `ElevationTokens` (the one elevation is hard-coded `ctx.dp(3)`, `Components.kt:241`), `Clients.kt:167,172` `RawTorrent.indexerId/publishDate`, `AppState.kt:7` `catalog` wrapper, `Components.kt:105` `TopBarAction.label`. Sources: Q-M10/M11, S-10 + ridiculous list, A-L3, T-4. Severity: Medium. **Vote: APPROVE** — the purest Ousterhout win; verified via grep (only test references: `setExternal` in `LiveParsingTest.kt:295`, `indexerId` assertion in `LiveParsingTest.kt:145` — update those two tests). Notes: keep `dp(3)` as a named const; `renderOnce` either gets used by #32 or is inlined as `invalidate()`.

23. **Delete the unused second theme `PorcelainLight` + `AppTheme.use/reset`** (`Tokens.kt:122-159`); keep `AppTheme.current` as a `val`. Sources: S-10 (T-5 partially conflicts). Severity: Low. **Vote: MODIFY** — delete the theme and the mutators (the app ships exactly one theme; a light theme is one data class away in git history if ever needed, which also makes T-5's window/icon fixes moot); adjust `DesignTokensTest.kt:96-101` to drop the swap test but keep the contrast-invariant tests over both token sets if desired — or delete those too, simplest.

24. **RequestStoreCore: drop the "legacy 5-field line" branch** (`Catalog.kt:159-164`, exists only for a synthetic test input). Source: S-7. Severity: Low. **Vote: MODIFY** — just require all 6 fields and delete the `if (p.size > 5) … else ""` fallback; keep the TSV escaping (rewriting the whole serialization to JSON is churn, not simplification).

25. **Unify the two magnet models** (`Catalog.Magnet` vs `LiveMagnet`, converted by hand at `DetailActivity.kt:218-221`). Source: A-M4. Severity: Low. **Vote: APPROVE** — one domain `Magnet` with an optional `downloadUrl` deletes a type and a conversion site.

26. **Extract per-screen state holders + sealed-class event UDF** (`MainStateHolder`, `MainEvent`, etc.). Source: A-H3. Severity: (proposed High). **Vote: REJECT** — this *adds* an abstraction layer (holders, sealed events, event dispatch) to screens that are currently straightforward; the actual bugs underneath it (recreate loop, ticker leak, cache mess) are already fixed far more cheaply by #2, #3, #16. Ousterhout: don't build speculative generality.

27. **Status pill contrast**: use the `ModeBanner` pattern — `Chip(ctx, label, withAlpha(statusColor, 0.18f), statusColor, t)` — instead of white text on solid status colors (`Components.kt:337` default `fg = 0xFFFFFFFF`, `:348-371` `StatusPill`; measured 1.7-2.7:1, fails WCAG AA); add a `contrastRatio >= 4.5` unit test for chip pairs. Sources: T-1, U-3, Q-L8. Severity: **High** (accessibility). **Vote: APPROVE** — small change in one place, big win, and the repo already has `contrastRatio()` (`Tokens.kt:181-187`).

28. **48dp minimum touch targets**: `CircleIconButton` 40→48 (`Components.kt:143`), `minimumHeight = dp(48)` on Primary/Secondary/Danger buttons (`Components.kt:181,194,205`), FormField (`Components.kt:549-563`), padded clear "✕" (`Components.kt:309-312`). Source: U-4. Severity: Medium. **Vote: APPROVE** — constant changes, no new code.

29. **RequestsActivity: diff by signature before re-render/staggerIn** (currently re-animates every 5s poll, `RequestsActivity.kt:132` + ticker `:64-68`). Source: U-5. Severity: Low. **Vote: APPROVE** — copy the existing `MainScreen` pattern (`:220-227`), ~4 lines.

30. **Confirmation dialog for destructive delete** (requests 🗑 `RequestsActivity.kt:122-125`, cancel `DetailActivity.kt:173-176`). Source: U-6. Severity: Low. **Vote: MODIFY** — add the `AlertDialog` confirm only; skip the Undo-snackbar variant (extra state and code for little gain; the toast on success can stay).

31. **Settings: make `testResults` a wrapping/vertical column** (`SettingsActivity.kt:70-71`, chips clipped on narrow screens) **and validate URL scheme** in `liveReady`/save with inline error text (`Config.kt:24-33,43-45`, `SettingsActivity.kt:96-100`). Source: U-7/U-8. Severity: Low. **Vote: APPROVE** the layout fix + scheme check; skip U-7's parallel pings / per-service progress chips (adds threading/UI code).

32. **Honor "Remove animations"** (`Settings.Global.ANIMATOR_DURATION_SCALE`): static aurora + static skeleton when scale is 0 (`AuroraBackground.kt:46-56`, `Components.kt:522-527`). Source: U-13. Severity: Low. **Vote: APPROVE** — ~6 lines in two attach points.

33. **Motion constants to M3**: interpolators `cubic-bezier(0.2,0,0,1)` / `(0.05,0.7,0.1,1)` (`Motion.kt:23-26`), `longMs` 520→500 (`Tokens.kt:117`), drop/shorten hero overshoot (`Motion.kt:58-70`). Sources: U-14, T-9/T-10. Severity: Low. **Vote: APPROVE** — pure constant changes, zero new code.

34. **themes.xml dedupe + luminance-driven system-bar icons**: `windowBackground` → transparent, drop `textColorPrimary` (`themes.xml:4-5`); `Insets.kt:23` derive appearance from `luminance(AppTheme.current.colors.background)`. Source: T-5. Severity: Low. **Vote: APPROVE** — res-only + 3 lines (and mostly moot after #23, but correct regardless).

35. **Make `realHttp` total**: wrap the transport body in `runCatching` → `Resp(status = 0, …)` on exception, so callers don't each need `runCatching` (`Http.kt:18-33`). Source: R-16. Severity: Low. **Vote: APPROVE** — "define errors out of existence"; shrinks every call site.

36. **Kotlin-convention renames** (PascalCase atoms → camelCase, `t` → `tokens`, `lp` → `layoutParams`, `col`/`row`). Sources: Q-L1/L2. Severity: Low. **Vote: REJECT** — zero complexity change, touches nearly every line of the app; #19 already removes the `t` parameter entirely, which is the substantive part.

37. **Split `Components.kt` (587 lines) / `Clients.kt` into per-group files.** Source: Q-L6. Severity: Low. **Vote: REJECT** — file length alone is not complexity; splitting is neutral-to-negative churn for a single-developer app and makes the token→atom hierarchy harder to see. (If it grows past ~1,000 lines, revisit.)

38. **Drop `PagerMath` degenerate guards / `flingThresholdPxS` default.** Source: S-low. Severity: Low. **Vote: REJECT** — the guards are two lines, the defaults are used by `PagerMathTest`, and removing "ridiculous edge cases" saves nothing measurable here.

39. **Extra in-progress indicators** (top progress bar on background refresh, in-field spinner during search). Sources: U-10/U-18. Severity: Low. **Vote: REJECT** — new UI code for marginal feedback; cold loads already show skeletons and failures are covered by #8. Simplest thing that works.

40. **Pad bottom nav to the 80dp M3 container height.** Source: U-19. Severity: Low. **Vote: REJECT** — the compact bar is a deliberate, conformant-enough choice (cells ≥48dp, pill + icon + label per M3); not worth a layout change.

---

## Small improvements (regardless of votes)

1. **TalkBack descriptions on posters**: `contentDescription = "${m.title} poster"` on the `ImageView`/`GradientPoster` frames in `PosterCard` (`Components.kt:440-449`), detail hero (`DetailActivity.kt:61-70`), and requests thumbs (`RequestsActivity.kt:100-107`) — zero matches today (U-15).
2. **Make the placeholder `ModeBanner` tappable** → `openRisingTo(SettingsActivity::class.java)` (`MainScreen.kt:81`, `Components.kt:576-582`) — it literally tells the user to "tap ⚙" (U-16); 2 lines.
3. **Requests count label**: count only rendered rows, since `render()` silently skips unresolvable media (`RequestsActivity.kt:88,93`) — "5 total" with 4 cards today (U-17).
4. **Debug StrictMode**: `ThreadPolicy.Builder().detectAll().penaltyLog()` in `MainActivity.onCreate` when `BuildConfig.DEBUG` — would have flagged #1 immediately (R guardrail); 4 lines.
5. **Collapse the doubled `when (style)` blocks**: `AppText` computes size and color in two whens (`Components.kt:66-79`) — one `when` returning a `TextSpec(sizeSp, color)` pair halves it (Q-L7); same for `TmdbClient.toMedia`'s title/date whens (`Clients.kt:112-130`); tidy `Config.kt:44-73` to use imports instead of fully-qualified `kotlinx.serialization…` names (Q-L12).

---

*Panelist 2 vote summary: 27 APPROVE, 7 MODIFY (#6, #11, #13, #17, #23, #24, #30), 6 REJECT (#26, #36, #37, #38, #39, #40). Heaviest simplicity wins: #1, #3, #16, #18, #19, #20, #22.*
