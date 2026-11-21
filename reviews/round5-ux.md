# Round 5 — UX Review (fresh, master HEAD @ cd6abab, v4.5)

Scope: `app/src/main/java/com/example/appa/` (~22 files, programmatic Views, no androidx) + manifest/res. Lens: navigation flow, feedback, empty/loading/error states, gestures, animations. Principles applied: immediate feedback, predictable navigation, graceful degradation, no dead ends (Material 3 / HIG).

Strengths worth keeping: distinct error vs. empty states with Retry in the main grid (MainScreen.kt:296-322), stale-while-revalidate with an inline "showing cached results" banner (MainScreen.kt:255-266, 282-286), process-wide result cache that survives rotation without skeleton flash (MainScreen.kt:355-361), debounced search with clear button (Components.kt:296-346), confirm dialogs before destructive deletes (DetailActivity.kt:196-207, RequestsActivity.kt:124-137), disabled-state styling on the request button with "Requesting…" in-flight label (DetailActivity.kt:225-227), `reduceMotion()` honored in all animation helpers (Motion.kt:31, 43, 58, 75, 91), edge-to-edge + cutout padding (Insets.kt), WCAG contrast unit-tested (DesignTokensTest.kt), and touch targets ≥48dp throughout.

---

## Findings (ranked by severity)

### HIGH

**H1. Skeleton loading cells render at 0 height — the loading state is invisible.**
- `MainScreen.kt:197-203` (`buildPage`): `Skeleton(activity, 0, 150, t)` sets `layoutParams = ViewGroup.LayoutParams(0, dp(150))` inside `Skeleton()` (Components.kt:561-564), but the caller then **replaces** it with `GridLayout.LayoutParams().apply { width = 0; columnSpec = spec(UNDEFINED, 1f) }`. `GridLayout.LayoutParams()` defaults `height = WRAP_CONTENT`, and a plain `View` with a drawable background and no content measures **0** tall. Result: on a cold start in live mode (empty cache) the skeleton grid is visible-but-empty, `showLoading` hides the empty state (MainScreen.kt:234-237), and the user stares at a **completely blank page** for the whole network round-trip (up to 15s connect timeout, Http.kt:104) with zero feedback. Same blank happens on a first search that has no cache entry.
- Why it matters: "immediate feedback / no dead-feeling screens" — a blank grid reads as a hang or crash, and there is no Retry because no error occurred.
- Fix: in `buildPage`, keep an explicit height on the `GridLayout.LayoutParams` (e.g. `height = activity.dp(225)` to match the 2:3 poster + text, or `height = 0` with `rowSpec = spec(UNDEFINED, 1f)` so rows share the MATCH_PARENT grid). Add a unit/robolectric-style assertion or manual check that skeleton children measure > 0.

**H2. Rotation wipes the tab and the search query — navigation state is not preserved.**
- `MainActivity.java:17-18` calls `showMainScreen(this)` and **ignores `savedInstanceState`**; `MainScreen.kt:22-25` likewise. `MainScreen` re-initializes `currentTab = MOVIES`, `query = ""` (MainScreen.kt:34-35). The manifest has no `configChanges`, so every rotation recreates the activity: the user lands back on Movies with an empty search box even if they were deep in an Audiobooks search. The result cache survives (MainScreen.kt:355-361) but is keyed by tab+query, so the grid silently re-renders the default browse list.
- Why it matters: predictable navigation — users expect orientation change to be lossless (Android `ViewModel`/`onSaveInstanceState` contract).
- Fix: pass the `Bundle` through `showMainScreen(activity, savedInstanceState)` and restore `currentTab`/`query` (and the `SearchField` text), or hold them in a process-level holder next to `cache`. Also persist `pager.current`.

### MEDIUM

**M1. No progress indication while results are already on screen (search / stale-while-revalidate).**
- `MainScreen.kt:276-279`: when a query has no fresh cache entry but the grid already has items, `showLoading(loading=true, hasItems=true)` hides the skeleton and keeps the **old** results visible with no spinner, and the cache path (MainScreen.kt:271-273) does the same. The user types a new query and old results sit there looking current until the fetch resolves — stale data masquerading as fresh.
- Fix: while `loadJob` is in flight, show a small indeterminate indicator — e.g. swap the search field's trailing clear icon for a spinner, or add a 2dp progress bar under the search field / top of the page.

**M2. Failed poster image leaves a blank gray box — no fallback to the gradient poster.**
- `ImageLoader.kt:53-57`: on decode/network failure the `ImageView` keeps only `surfaceGlassStrong` background. `PosterCard` (Components.kt:493-501) and the detail hero (DetailActivity.kt:121-128) chose the `ImageView` branch solely because `posterUrl != null`, so a 404/time-out yields an empty gray rectangle where a poster should be.
- Why it matters: graceful degradation — the app already has a beautiful `GradientPoster` fallback; use it.
- Fix: add a failure callback to `ImageLoader.load(url, view, onError)` that swaps the `ImageView` for `GradientPoster(media)` (or draws the gradient into the ImageView background) and sets a contentDescription.

**M3. Remote posters change cell height on load → grid reflow / layout shift.**
- `Components.kt:493-498`: the poster `ImageView` uses `MATCH_PARENT`/`WRAP_CONTENT` with `CENTER_CROP` and only `minimumHeight = 150dp`. While loading it measures ~150dp; when the bitmap arrives, `ImageView.onMeasure` re-measures to the drawable aspect (2:3 → `width*1.5`), so every card jumps taller and the whole grid reflows mid-scroll. `GradientPoster` avoids this with a fixed 2:3 `onMeasure` (Components.kt:466-472) — the image branch should too.
- Fix: give the image branch the same fixed 2:3 `onMeasure` (or subclass `ImageView` with aspect-ratio measurement) so height is constant before and after load.

**M4. Settings form can render empty due to an async-config race, and never refreshes.**
- `SettingsActivity.kt:30-31` calls `AppState.init(this)` (whose config load is async, AppState.kt:38-56) and then reads `AppState.config` synchronously at `SettingsActivity.kt:52` (and `liveChecked` at :102). If the user opens Settings before the background load posts (fast tap after cold start), all fields start blank; nothing re-populates them because Settings never hooks `AppState.onConfigChanged` nor overrides `onResume`.
- Fix: populate the form in `onResume()` when fields are not user-dirty, or register a temporary `AppState.onConfigChanged` that re-fills untouched fields.

**M5. System back on sub-screens bypasses the custom exit transition.**
- `AndroidManifest.xml:6` sets `android:enableOnBackInvokedCallback="true"`, which disables the legacy `onBackPressed` path; no `OnBackInvokedCallback` is registered anywhere. So ✕ calls `finishDropping()` (slide-down, Motion.kt:126-129) but the system back button / gesture finishes with the default animation — inconsistent, "wrong-feeling" exit.
- Fix: in each sub-activity (Detail/Requests/Settings) `onCreate`, `onBackInvokedDispatcher.registerOnBackInvokedCallback(...)` → `finishDropping()`.

**M6. Requests list replays the full stagger animation on every status tick.**
- `RequestsActivity.kt:154`: `if (list.childCount > 0) list.staggerIn(stepMs = 30)` runs on **every** render that passes the signature check — the comment claims "only the initial fill", but a status change (poller ticks every 1s placeholder / 5s live, AppState.kt:152-153) changes the signature (RequestsActivity.kt:81) and re-animates the entire list, so cards visibly flicker/rise repeatedly while you watch.
- Fix: stagger only when the list was previously empty or the item count changed (e.g. track `lastCount`); on pure status changes, update pills in place or animate only the changed card.

**M7. "Test connection" gives ✓/✗ with no reason — a diagnosis dead end.**
- `SettingsActivity.kt:160-167`: chips are set to `"$name ✓/✗"` with a color change only. A ✗ for a typo'd API key, a 401, a DNS failure and a timeout all look identical; the user must guess. (The URL fields do get inline validation, SettingsActivity.kt:60-70 — good — but runtime failures don't.)
- Fix: put a short reason in the chip or a line under it, e.g. `sonarr ✗ 401` / `radarr ✗ timeout`, from the thrown `ApiException.status`/message.

**M8. Unsaved Settings edits are silently discarded on ✕ / back.**
- SettingsActivity has a ✕ leading action (`SettingsActivity.kt:38`) and system back; neither checks whether any field differs from the stored config. The user edits five URLs, mis-taps ✕, and loses everything with no confirmation.
- Fix: track a `dirty` flag (TextWatcher on all fields + switch); on close with dirty=true show "Discard changes?" dialog.

### LOW

**L1. Aurora backdrop animator runs while the app is paused.**
- `AuroraBackground.kt:45-63`: the 24s infinite `ValueAnimator` starts `onAttachedToWindow` and is only cancelled `onDetachedFromWindow` (AuroraBackground.kt:75-79). When the activity is merely stopped (not destroyed), the view stays attached and the animator keeps invalidating — wasted CPU/battery. `reduceMotion()` is honored (static frame, :47-50) — good.
- Fix: override `onWindowVisibilityChanged`/`onVisibilityChanged` and `anim?.pause()`/`resume()` (or cancel + restart on attach).

**L2. `withPressScale` reduced-motion branch is inverted vs. intent.**
- `Motion.kt:104`: when `reduceMotion()` is true it sets a static `scaleX/Y = 0.97` (a visual change), while non-reduced users get the animated 0.94. Reduced-motion users should get **no** scale change (matching `skipAnim()` elsewhere, Motion.kt:31).
- Fix: `if (v.context.reduceMotion()) { /* no-op */ } else animate…`.

**L3. Re-tapping the active bottom-nav tab does nothing.**
- `Components.kt:290`: `select()` early-returns when the index is unchanged, so there is no scroll-to-top on re-tap (Material convention). MainScreen even has the scroll-to-top logic gated behind `navInitiated` (MainScreen.kt:110-114) but it can never fire for the already-selected tab.
- Fix: in `BottomNavBar.onSelect` (or MainScreen), if `i == currentTab`, `scroll.smoothScrollTo(0,0)` instead of a no-op.

**L4. Live detail-metadata fetch fails silently.**
- `DetailActivity.kt:65-69`: `runCatching { … }.getOrDefault(media)` — a failed TMDB details call leaves the sparse list metadata with no indication. Graceful (no crash, no dead end) but silent.
- Fix: on failure, show a one-line "Couldn't refresh details — showing basic info" note in the meta card (same pattern as the magnets header, DetailActivity.kt:236-238).

**L5. Poster images swap in abruptly; no fade-in.**
- `ImageLoader.kt:50-52` sets the bitmap with no transition (cache hits are instant by design — good — but first loads pop). Fix: a short `fadeIn()` (Motion.kt:53) on first decode for polish.

**L6. No pull-to-refresh on any list.**
- Grids and the requests list rely on polling + error-Retry; there is no manual refresh gesture. Given the 45s cache TTL (MainScreen.kt:358) this is defensible; adding a swipe-refresh on the main grid would improve perceived control. Enhancement, not a defect.

---

## What's already good (do not regress)
- Distinct loading / empty / error states with Retry in the main grid, and "never masquerade error as no-results" (MainScreen.kt:296-322, 255-266).
- Stale-while-revalidate cache (45s TTL) with inline warning banner when refresh fails (MainScreen.kt:269-286).
- Debounced search (350ms) with clear button, IME search action hides keyboard (Components.kt:296-346); keyboard hidden on poster tap and nav tap (MainScreen.kt:110-115, 335-338).
- SwipePager: horizontal-only interception (|dx|>|dy|), fling snapping, per-page scroll preservation, no re-animation on data ticks (SwipePager.kt, MainScreen.kt:363).
- Confirm dialogs before every delete; toasts for request/copy outcomes; in-flight "Requesting…" button state (DetailActivity.kt:196-232).
- Poller paused when screens are covered, cancelled on destroy; in-flight guard (AppState.kt:100-135).
- Magnets section has its own loading ("searching indexers…"), failure and Retry states (DetailActivity.kt:234-266).
- Requests screen: empty state, stale-refresh warning, badge count with accessible description (RequestsActivity.kt:26-31, 78-84; Components.kt:139-141).
- Edge-to-edge with cutout padding on every screen; 48dp touch targets; `reduceMotion()` respected in all enter/exit animations; predictive-back flag set (though see M5).

## Suggested priority order
1. H1 (skeleton 0-height) — one-line fix, restores the core loading state.
2. H2 (state restore on rotation) — small, high UX payoff.
3. M1 (in-flight indicator), M2/M3 (poster fallback + aspect) — all in the grid's happy path.
4. M4–M8 (settings race, back transition, requests re-animation, test reasons, dirty guard).
5. L1–L6 polish.
