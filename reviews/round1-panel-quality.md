# Round 1 — Panel triage (Panelist 1 of 3: Code Quality & Structure)

**Tag:** `round1-panel-quality` · **Repo:** `/home/dev/app-a` · **Wrote only this file.**
**Lens / reference:** Kotlin Coding Conventions + Clean Architecture (Dependency Rule, composition root) + SOLID (SRP/DIP/OCP). Priority: structure & testability wins that don't cost churn; reject pure churn or abstraction without payoff.
**Method:** all six `round1-*.md` reports read in full; every cited site spot-checked against `app/src/main/java/com/example/appa/` (e.g. `LiveRepository.kt:51-55` ctor I/O confirmed, `MainScreen.kt:44-58` ticker with no teardown confirmed, `repo!!` at `MainScreen.kt:179,203` / `DetailActivity.kt:95,190,210,244` confirmed, dead-code symbols grep-verified as zero-call-site). Duplicate findings across reports were merged — e.g. the `LiveRepository` main-thread network call appeared in architecture-H4, robustness-#1 and simplicity-#5 → single entry **C-01**.

Severity scale: **Critical** (crash/ANR/security) · **High** · **Medium** · **Low**.

---

## Deduped change list

**C-01 — Remove blocking network I/O from `LiveRepository`'s constructor; resolve `audiobookIndexerIds` lazily (and re-resolve when blank).**
Sources: architecture-H4, robustness-#1, simplicity-#5 · Severity: **Critical** (`LiveRepository.kt:51-55` init calls `prowlarr.indexerIdByName` synchronously; constructed on the main thread from `AppState.init`/`saveConfig` (`AppState.kt:30,38`) → `NetworkOnMainThreadException`/ANR).
Vote: **APPROVE.** A constructor with hidden blocking I/O violates SRP and is the crash the robustness report documents. `by lazy` (mirroring the other clients at `LiveRepository.kt:40-43`) is the minimal, structure-preserving fix.

**C-02 — Repository layer overhaul: `MediaRepository` interface owned by the domain layer, `PlaceholderRepository` + `LiveRepository` impls, non-null `AppState.repo`, atomic `(config, repo)` update, `mediaById` live-cache→catalog fallback moved into the repo; delete the six `repo!!` sites.**
Sources: architecture-H1/H2/M2, robustness-#7 · Severity: **High** (`AppState.kt:16-17,45`; `MainScreen.kt:179,203`; `DetailActivity.kt:95,190,210,244`).
Vote: **MODIFY.** Approve the interface + non-null repository + placeholder implementation — that is textbook DIP/OCP at the Clean Architecture boundary and removes the `!!`s. Do **not** constructor-inject into the `Activity` subclasses: without a DI framework that is pure churn; keep `AppState` as the composition root ("main") exposing a narrow, typed accessor. Fold in the atomic `(config, repo)` holder and the repo-owned `mediaById` fallback.

**C-03 — Synchronize `LiveRepository`'s shared mutable state** (`mediaCache` `LiveRepository.kt:180-198`, scalar fields `:47-49`) via `ConcurrentHashMap`/`@Volatile`, or confine all repo calls to one single-thread executor.
Sources: architecture-M2, robustness-#6 · Severity: **High** (concurrent `refreshStatuses` vs main-thread `remember` → CME/torn reads).
Vote: **APPROVE.** Pairs naturally with C-04's single-thread executor; `RequestStoreCore`'s lock style (`Catalog.kt:98`) is the in-repo precedent.

**C-04 — Screen lifecycle/threading hygiene: `MainScreen.detach()` called from `MainActivity.onDestroy`; one shared `StatusTicker`/poller for `MainScreen` + `RequestsActivity`; single-thread executor so ticks can't overlap; `isDestroyed` guard on posted renders and debounce callback.**
Sources: architecture-M5/M6, robustness-#2/#12/#13, quality-M7, simplicity (ticker finding) · Severity: **High** (`MainScreen.kt:44-58,123` ticker never cancelled — `MainActivity.java` has only `onCreate`; `RequestsActivity.kt:63-81` shows the correct pattern).
Vote: **APPROVE.** The leak is real and confirmed; extracting one ticker kills the duplication between `MainScreen.kt:44-58` and `RequestsActivity.kt:63-76` at the same time.

**C-05 — Replace the four `recreate()` calls in `DetailActivity` with targeted in-place re-render; delete the racy re-read at `DetailActivity.kt:102`; fix the recreate-loop guard for empty-metadata items.**
Sources: simplicity-#4, robustness-#3, ux-#2/#11, architecture-H3 · Severity: **High** (`DetailActivity.kt:98,176,196,202`; guard `d != media` can still loop forever per robustness-#3).
Vote: **APPROVE.** `recreate()` re-runs all of `onCreate` (network re-fire, animation replay) to swap one button — the "all code in an Activity" anti-pattern; in-place re-render matches the diff-render pattern already praised in `MainScreen.renderCurrent` (`MainScreen.kt:219-235`).

**C-06 — Collapse `MainScreen.loadItems` browse/search branches into one cached-load helper; key the search cache by `tab+query`; stamp `loadedAt` after the fetch; unify the two signature caches into one `signatureByKey`.**
Sources: quality-M8, simplicity-#1/#2, robustness-#15 · Severity: **Medium** (`MainScreen.kt:152-211,184,190,207,219-227`).
Vote: **APPROVE** (keep the diff-based rendering — it's a good pattern — but under a single `"$tab|$query"` key). Pure dedup that also fixes two real cache bugs.

**C-07 — Distinguish error from empty in the main grid: keep the `Throwable`, show a distinct error `EmptyState` with a Retry button; reserve "No titles match your search" for successful-but-empty searches.**
Sources: ux-#1, robustness-#7 · Severity: **High** (`MainScreen.kt:179,203` swallow errors into `emptyList()` → `EmptyState` at `:135`).
Vote: **APPROVE.** Small state addition (folds into C-06's tri-state), reuses the existing `EmptyState` atom.

**C-08 — Magic-number migration: add missing token groups to `Tokens.kt` (control sizes/heights, `colors.chipScrim`, `RadiusTokens.thumb`, hero/scrim dimensions, `MotionTokens.debounceMs`/`ambientMs`, glass border/sheen roles, disabled-alpha) and replace literals; purely local values (`GRID_COLUMNS`, `CACHE_TTL_MS`, poll intervals, `take(25)`, HTTP limits) become `private const val`s in the using file.**
Sources: quality-H1/L5/L11, architecture-M3/L2, theming-#3/#6-#11/#13/#14 · Severity: **Medium** (`Tokens.kt:10-11` contract broken at `Components.kt:143,151,181,234,251,296,344,442,454-455,499`; `DetailActivity.kt:59,72,79-80`; `RequestsActivity.kt:100-116`; `MainScreen.kt:127,169,191`; `Motion.kt:36,77,92`; `AuroraBackground.kt:47,87`; `Glass.kt:32-46`).
Vote: **MODIFY.** Direction is right, but split it: design values belong in `Tokens.kt`; app constants (grid columns, TTL, poll ms) belong as file-level `const val`s — forcing them into `DesignTokens` would bloat the token layer with non-design values.

**C-09 — Extract one `ScreenScaffold` atom used by all four screens** (aurora root → padded body → top bar → scroll area).
Sources: quality-H2 · Severity: **Medium** (`MainScreen.kt:66-72`, `DetailActivity.kt:37-43`, `RequestsActivity.kt:31-37`, `SettingsActivity.kt:28-34`).
Vote: **APPROVE.** Four verbatim copies; one atom removes them with zero behavior change.

**C-10 — Atom-internal dedup: reuse `roundedClip` at `Components.kt:229-234` + `DetailActivity.kt:51-57`; one `badgeView` factory for `Components.kt:149-153`/`160-170`; one `PosterVisual(ctx, m, radiusDp)` atom for the image-vs-gradient branch at `Components.kt:440-449`, `DetailActivity.kt:61-70`, `RequestsActivity.kt:98-107`; one `RatingChip` for `Components.kt:455` + `DetailActivity.kt:79-80` (scrim color into `colors.chipScrim`, see C-08).**
Sources: quality-M1/M2/M3/M4, theming-#12/#3, simplicity (badge) · Severity: **Medium**.
Vote: **APPROVE.** All four are copy-paste extractions with a single existing helper to build on; removes ~50 duplicated lines.

**C-11 — Split `DetailActivity.onCreate` (~115 lines) into `buildHero`/`buildMetaCard`/`buildActionsRow`/`buildMagnetsSection`, and extract a `ChipFlow` (chunked rows) for the `take(6)`/`drop(6)` hack at `DetailActivity.kt:110-131`.**
Sources: quality-M5/M6, simplicity (chip wrap) · Severity: **Medium**.
Vote: **APPROVE.** SRP/small-functions; also the prerequisite that makes C-05 (targeted re-render) clean.

**C-12 — `SettingsActivity`: one `FieldSpec` list driving both the form UI and `collect()`** (`SettingsActivity.kt:56-64` vs `:90-101`).
Sources: quality-M9 · Severity: **Low**.
Vote: **APPROVE.** Removes a silent-typo hazard between declaration and collection.

**C-13 — Extract shared `ArrClient` base class for `SonarrClient`/`RadarrClient` (`api`/`post`/`rootFolder`/`qualityProfileId`).**
Sources: quality-M13, simplicity (LOW-MED) · Severity: **Medium** (`Clients.kt:225-238` vs `:283-296`).
Vote: **APPROVE.** ~50 duplicated lines collapse into one deep module with a simple interface.

**C-14 — Delete the dead-code cluster:** `LinearOutSlowIn` (`Motion.kt:24`), `crossfadeOut`+`FastOutLinearIn` (`Motion.kt:25,102-108`), `glassDrawable` (`Glass.kt:18-26`), `dpF`/`sp` (`Density.kt:10-11`), `renderOnce` (`AuroraBackground.kt:99`), `setTextSilently` (`Components.kt:329-332`), `pageView` (`SwipePager.kt:31`), `magnetOf` (`Clients.kt:208`), `MediaType.fromSlug` (`Catalog.kt:20-22`), `setExternal` (`Catalog.kt:109-116`), `TopBarAction.label` (`Components.kt:105`), `RawTorrent.indexerId`/`publishDate` (`Clients.kt:167,172`), `AppState.catalog` wrapper (`AppState.kt:7`). Consume `ElevationTokens` at `Components.kt:241` instead of deleting it.
Sources: quality-M10, architecture-L3, simplicity-#10 (all grep-verified zero-call-site) · Severity: **Low**.
Vote: **MODIFY.** Approve deleting everything listed — except keep `PorcelainLight` + `AppTheme.use/reset` (`Tokens.kt:122,174-178`): the swappable second theme is what makes the token layer *verifiably* swappable (`DesignTokensTest`), i.e. it buys testability, not speculative generality. Adjust the two tests that touch removed symbols.

**C-15 — Fix `pingAll()` tautologies** (`LiveRepository.kt:59,61`: `.isNotEmpty() || true`, `; true`) → real success checks / a real `ping()` per client.
Sources: quality-M12, architecture-L1, simplicity-#6 · Severity: **Medium** (Settings "Test connection" always shows Prowlarr ✓).
Vote: **APPROVE.** Two-line fix of a genuinely misleading result.

**C-16 — `Glass.kt` cleanup: delete the identical-branch ternary at `Glass.kt:38`; move border/sheen colors and the `26`/`16` alpha boosts into tokens or named consts.**
Sources: quality-M11, simplicity (MED), theming-#4 · Severity: **Low**.
Vote: **APPROVE.** Dead branch + magic alphas in a file whose header promises "all values from tokens".

**C-17 — Magnet resolution hardening: in `Http.kt:45-51` collapse the identical `return loc` branches and accept a 3xx `Location` only if `magnet:`/`.torrent`; in `DetailActivity.copyMagnet` (`DetailActivity.kt:241-251`) show an error on failure instead of copying `downloadUrl`, and strip `apikey` from anything ever copied.**
Sources: simplicity (MED), robustness-#5 · Severity: **Critical-ish/High** (Prowlarr API key currently lands on the clipboard, `Clients.kt:177` embeds `?apikey=`).
Vote: **APPROVE.** Dead branch removal + a real secret-leak fix in one small edit.

**C-18 — HTTP layer: small retry helper (2–3 attempts, ~500ms/1.5s backoff) for Prowlarr search/resolve; make `realHttp` total by returning `Resp(status = 0, …)` on `IOException` instead of throwing raw.**
Sources: robustness-#4/#16 · Severity: **Medium** (`Http.kt:18-33`).
Vote: **APPROVE.** A total `Resp` type removes the "every caller must remember `runCatching`" tax — errors defined out of existence at the boundary.

**C-19 — Replace global `android:usesCleartextTraffic="true"` (manifest) with a `network_security_config` limited to LAN/private ranges; send *arr keys via the `X-Api-Key` header instead of `?apikey=` query strings.**
Sources: robustness-#9 · Severity: **High** (MASVS-NETWORK-1; keys in server access logs).
Vote: **APPROVE.** Manifest/xml + a mechanical header change in `Clients.kt` `api()`/`post()`.

**C-20 — `ImageLoader`: fetch through `HttpTransport` (or set explicit connect/read timeouts), bounded queue, skip the main-thread callback when the view is detached.**
Sources: robustness-#8, architecture (clean-note) · Severity: **Medium** (`ImageLoader.kt:19,32` — no timeouts, pool of 3 can all hang).
Vote: **APPROVE.** Makes the last non-injected I/O component consistent with the `HttpTransport` DIP pattern used everywhere else.

**C-21 — One domain `Magnet`: merge `LiveMagnet` (`LiveRepository.kt:11-19`) into `Catalog.Magnet` with an optional `downloadUrl`; delete the manual conversion at `DetailActivity.kt:218-221`.**
Sources: architecture-M4 · Severity: **Medium**.
Vote: **APPROVE.** Two parallel models for the same concept is a structural smell; the merge is small and mechanical.

**C-22 — `PosterCard` takes a `status: RequestStatus?` parameter instead of reading `AppState`** (`Components.kt:459`).
Sources: architecture-M1 · Severity: **Medium**.
Vote: **APPROVE.** Restores the atom layer's own contract ("exposes only semantic parameters", `Components.kt:24-31`) and makes atoms testable without the global.

**C-23 — Status-pill contrast fix: build `StatusPill` with the `ModeBanner` pattern (`Chip(ctx, label, withAlpha(color, 0.18f), color, t)`) or add on-status color roles; change `Chip`'s default `fg` from `0xFFFFFFFF` (`Components.kt:337`) to `t.colors.textOnAccent`; add a `contrastRatio(...) >= 4.5` unit test for all chip fg/bg pairs.**
Sources: theming-#1, ux-#3, quality-L8 · Severity: **High** (WCAG AA fail, measured 1.7–2.8:1 at `Components.kt:348-368`).
Vote: **APPROVE.** Small change, and the contrast math already exists in `Tokens.kt:181-187` — pinning it with a test fits the existing `DesignTokensTest` invariant style.

**C-24 — `GradientPoster` rework: paint with `t.colors.textOnAccent` (not `Color.WHITE`, `Components.kt:390,393,406`); replace the hand-rolled canvas word-wrap + dead `removeSuffix("…")` guard (`:405-421`) with a `TextView` overlay (`maxLines=3`, `ellipsize=END`) over the gradient; honor an `EXACTLY` height spec in `onMeasure` (`:397-400`).**
Sources: theming-#2, simplicity-#8, ux-#11 · Severity: **Medium**.
Vote: **MODIFY.** Approve token colors and the `EXACTLY` fix unconditionally; for the wrap, prefer the `TextView` overlay (platform does wrapping, and it fixes TalkBack/C-25 in one move) — but if the canvas look must stay, at minimum delete the impossible `removeSuffix` guard.

**C-25 — `BottomNavBar.select`: replace the alpha-sniffing guard (`Components.kt:263`) with a plain `i != selected` check, and store direct child references instead of `getChildAt(1)`/`getChildAt(0/1)` casts (`:269-271`).**
Sources: simplicity-#9, quality-L4 · Severity: **Low**.
Vote: **APPROVE.** `i != selected` alone breaks the callback cycle; positional casts are fragile.

**C-26 — `SearchField`: hoist `handler`/`pending` above `root` (`Components.kt:326-327` vs `:293-324`), keep a direct `input` field instead of `getChildAt(1)` (`:328`), and remove pending callbacks on detach.**
Sources: quality-L3, simplicity (LOW), robustness-#13 · Severity: **Low**.
Vote: **APPROVE.** Fixes a class-layout convention violation plus a real post-destroy callback.

**C-27 — Animator hygiene: make `Skeleton` cancel its `INFINITE` shimmer in `onDetachedFromWindow` (`Components.kt:512-530`); honor `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (render aurora once, static skeletons).**
Sources: architecture-L4, robustness-#11, ux-#13 · Severity: **Medium** (leak + reduced-motion a11y; `AuroraBackground.kt:57-61` already shows the correct cancel pattern).
Vote: **APPROVE.**

**C-28 — `RequestStoreCore` persistence: drop the "legacy 5-field line" branch (`Catalog.kt:159-164`); keep the TSV format.**
Sources: simplicity-#7 · Severity: **Low**.
Vote: **MODIFY.** The 5-field compat exists only for a synthetic test — delete it. But do **not** switch to JSON-lines: that would silently wipe existing users' persisted requests with no migration, which is churn with a data-loss cost for ~15 lines of escaping code that is already tested and corruption-tolerant.

**C-29 — 48dp minimum touch targets:** `CircleIconButton` `dp(40)`→`dp(48)` (`Components.kt:143`), `minimumHeight = 48dp` on all buttons and `FormField` fields, padded hit area for the search ✕ (`Components.kt:309-312`).
Sources: ux-#4 · Severity: **Medium** (a11y).
Vote: **APPROVE.** Constant changes inside atoms only.

**C-30 — Request-flow feedback: disable the Request button + "Requesting…" label while the submit is in flight; branch on `store.create() == null` → "Already requested" instead of always "Added" (`DetailActivity.kt:188-196`, `Catalog.kt:108-110`); confirmation dialog for destructive deletes (`RequestsActivity.kt:122-125`, `DetailActivity.kt:173-176`).
Sources: ux-#2/#6, robustness-#14 · Severity: **Medium**.
Vote: **APPROVE.** The null-branch is a real "lying toast" bug; the rest is standard in-progress/error feedback.

**C-31 — Settings validation: require an `http(s)://` scheme in `liveReady`/save (`Config.kt:43-45`) and show inline field errors after interaction.**
Sources: ux-#8 · Severity: **Medium**.
Vote: **APPROVE.** The check belongs in the pure `AppConfig` (unit-testable), not in the activity.

**C-32 — Search respects the current tab: filter results by `type == currentTab` in `loadItems`** (`MainScreen.kt:160,197-206`; live search merges all types at `LiveRepository.kt:73-82`).
Sources: ux-#9 · Severity: **Low**.
Vote: **MODIFY.** Approve the one-line filter; skip the per-card type chip variant (extra atom surface for no real gain).

**C-33 — Settings "Test connection" UI: wrapping/vertical results row, per-service "pinging…" chip, parallel pings with an overall ~30s cap** (`SettingsActivity.kt:70-71,103-124`).
Sources: ux-#7 · Severity: **Low**.
Vote: **APPROVE.** Small layout + concurrency change in one function.

**C-34 — `RequestsActivity.render()`: signature-diff the `id:status` list so the 5s poll doesn't re-`staggerIn` unchanged content** (`RequestsActivity.kt:64-68,132`).
Sources: ux-#5 · Severity: **Low**.
Vote: **APPROVE.** Reuses the pattern `MainScreen` already has.

**C-35 — Background-refresh indication: small in-search-field spinner while a network search is in flight.**
Sources: ux-#10/#18 · Severity: **Low**.
Vote: **MODIFY.** Approve only the tiny in-field spinner; skip pull-to-refresh / a top progress bar — that's feature churn, not a fix.

**C-36 — `contentDescription` on all posters** (`Components.kt:440-446`, `DetailActivity.kt:61-66`, `RequestsActivity.kt:101-103`; zero matches today).
Sources: ux-#15 · Severity: **Medium** (a11y).
Vote: **APPROVE.** Three one-line additions.

**C-37 — Misc tiny UX fixes: make the `ModeBanner` tappable → opens Settings (`MainScreen.kt:81`); requests count label counts only rendered rows (`RequestsActivity.kt:88,93`).**
Sources: ux-#16/#17 · Severity: **Low**.
Vote: **APPROVE.**

**C-38 — Motion tokens to M3 scale** (durations ≈50/150/300/500, standard/emphasized curves) — token-value changes only, no call-site churn (`Tokens.kt:117`, `Motion.kt:23-26`).
Sources: ux-#14, theming-#9/#10 · Severity: **Low**.
Vote: **APPROVE.** Editing the token vals is free; rewriting interpolators per call site would be churn.

**C-39 — `themes.xml` dedupe (transparent `windowBackground`, drop `textColorPrimary`) + derive system-bar icon appearance from `luminance(AppTheme.current.colors.background)` in `Insets.kt:23`.**
Sources: theming-#5 · Severity: **Low**.
Vote: **APPROVE.** Removes token duplication that breaks the (currently unused) light theme.

**C-40 — Theme-read policy: capture `t = AppTheme.current` once per screen build and use the captured value everywhere (incl. `BottomNavBar.select`, `Components.kt:265,272`); document the policy.**
Sources: quality-L9, architecture-L2 · Severity: **Low**.
Vote: **APPROVE.** One consistent policy beats the current mix.

**C-41 — Rename PascalCase atom factories (`AppText`, `Chip`, …) to camelCase.**
Sources: quality-L1 · Severity: **Low**.
Vote: **REJECT.** Pure churn: ~16 renames across every screen with zero structural or testability payoff. PascalCase view factories are an established programmatic-UI DSL convention (they read as constructors); the Kotlin-conventions exception is arguably intended for exactly these. Style-only deviation, not a structure problem.

**C-42 — Mechanical rename of terse names (`col`/`lp`/`t`) across the codebase.**
Sources: quality-L2 · Severity: **Low**.
Vote: **REJECT.** ~200-site mechanical rename, no payoff; `t: DesignTokens` is unambiguous in context. Churn.

**C-43 — Split `Components.kt` (587 lines) into per-group files (`Components.Buttons.kt`, `.Nav.kt`, `.Cards.kt`, …); leave `Clients.kt` as one file.**
Sources: quality-L6 · Severity: **Low**.
Vote: **MODIFY.** Approve splitting `Components.kt` (exceeds the conventions' few-hundred-lines guidance and mixes unrelated atoms); reject one-file-per-client for `Clients.kt` — 358 cohesive lines is fine and the split is churn.

**C-44 — `AppText`: merge the two `when (style)` blocks into one returning a `TextSpec(sizeSp, defaultColor)`.**
Sources: quality-L7 · Severity: **Low** (`Components.kt:66-79`).
Vote: **APPROVE.** Trivial, halves the branching.

**C-45 — `SecureStore`: catch `KeyPermanentlyInvalidatedException` explicitly, delete the stale ciphertext, and surface "config was reset" instead of silently returning `AppConfig()`** (`SecureStore.kt:57-60`).
Sources: robustness-#10 · Severity: **Medium** (silent config wipe on keystore invalidation).
Vote: **APPROVE.** Small, defensive, at the right boundary.

**C-46 — Micro-cleanups:** extract `onDown(ev)` in `SwipePager.kt:69-79,96-101`; merge the two `when (type)` blocks in `TmdbClient.toMedia` (`Clients.kt:100-107`); import `JsonPrimitive` once in `Config.kt:44-73` (reuse the `str/dbl/int` helper style from `Clients.kt:18-32`); dedupe the radarr/sonarr external-ref parsing in `LiveRepository.refreshStatuses` (`LiveRepository.kt:143-157`).
Sources: quality-L10/L12, simplicity (LOWs) · Severity: **Low**.
Vote: **APPROVE.** All are local, behavior-preserving dedups.

---

## Small improvements (suggested regardless of the votes above)

1. **Enable `StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()` in debug builds** (e.g. in `MainActivity.onCreate`) — it would have flagged C-01 on first launch; zero production cost.
2. **Hoist a single `DateTimeFormatter` in `formatTime`** (`Components.kt:585-586` allocates a `SimpleDateFormat` per call — not thread-safe if ever shared; `java.time` is fine at minSdk 26).
3. **Drop the `showMainScreen` wrapper indirection** in `MainActivity.java` — call `MainScreen(this).attach()` directly; one less hop for zero benefit lost.
4. **Even if C-08 (full token migration) is rejected, hoist the five named consts** — `SEARCH_DEBOUNCE_MS = 350L`, `GRID_COLUMNS = 3`, `CACHE_TTL_MS = 45_000L`, `TICK_LIVE_MS = 5_000L`, and the `0xB3000000` scrim — zero-risk, kills the worst magic numbers.
5. **Pin chip contrast with a unit test**: assert `contrastRatio(fg, bg) >= 4.5` for every `Chip` color pair in `DesignTokensTest`, reusing `Tokens.kt:181-187` — catches the whole C-23 class permanently.

---
*Panelist 1 vote summary: 33 APPROVE · 7 MODIFY (C-02, C-08, C-14, C-24, C-28, C-32, C-35, C-43) · 2 REJECT (C-41, C-42 — pure churn).*
