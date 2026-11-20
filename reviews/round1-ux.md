# UX Review — round1-ux (lens: User Experience)

Repo: `/home/dev/app-a` (Kotlin, programmatic Views, minSdk 26 / target 35). Read-only review of the core flows: **browse → search → detail → request → requests → settings/test**.

## References consulted (Material Design 3, m3.material.io + Android accessibility)

- **Touch targets** — M3 "Designing structure" / Android Accessibility Help: *"Interactive targets for all elements meet Material's 48x48dp minimum touch target requirement"* (https://m3.material.io/foundations/designing/structure; https://support.google.com/accessibility/android/answer/7101858).
- **Motion** — M3 "Easing and duration" tokens-specs: standard/emphasized easing `cubic-bezier(0.2, 0, 0, 1)`, emphasized-decelerate `cubic-bezier(0.05, 0.7, 0.1, 1)`, emphasized-accelerate `cubic-bezier(0.3, 0, 0.8, 0.15)`; duration tokens short1=50ms … medium2=300ms … long1=450ms, long2=500ms, extra-long=1000ms **for ambient use only** (https://m3.material.io/styles/motion/easing-and-duration/tokens-specs). M1 duration guidance: *"transitions that exceed 400ms may feel too slow"* (https://m1.material.io/motion/duration-easing.html). M3 "Motion overview": motion should communicate change/state (https://m3.material.io/styles/motion/overview). Android accessibility: respect the user's **"Remove animations"** setting (`Settings.Global.ANIMATOR_DURATION_SCALE`) — no code in this repo reads it.
- **Feedback / loading** — M3 "Progress indicators" / "Loading indicator": *"use animation to … indicate that an activity is in progress"* (https://m3.material.io/components/progress-indicators/guidelines, …/loading-indicator/guidelines). M3 "Snackbars": snackbars are *"the preferred mechanism for displaying feedback messages to users, because they can be displayed in the context of the UI where the action occurred"* — over system Toasts (https://m3.material.io/components/snackbar; material-components Snackbar docs).
- **Errors / empty states** — Material "Errors" pattern: *"Help users fix input errors as soon as they are detected"*; text-field error text after interaction (https://m1.material.io/patterns/errors; M3 text-fields guidelines). Material "Empty states": distinguish a genuinely empty result from an error and give an actionable next step (https://m2.material.io/design/communication/empty-states.html).
- **Navigation** — M3 "Navigation bar": 3–5 destinations, icon + label, pill-shaped active indicator (https://m3.material.io/components/navigation-bar).
- **Accessibility / contrast** — M3 "Accessible design": text contrast ≥ 4.5:1 (WCAG AA); non-text content needs alternatives (https://m3.material.io/foundations/accessible-design).

## Findings

### High

1. **high — Network errors are shown as "No titles match your search" (error state mislabeled as empty, no retry).**
   `MainScreen.kt:179` and `MainScreen.kt:203` do `runCatching { AppState.repo!!.browse(tab) }.getOrElse { emptyList() }` / same for `search`, so a failed TMDB/OpenLibrary call renders the same `EmptyState("🔍", "Nothing here", "No titles match your search.")` built at `MainScreen.kt:135` via `showLoading(p, loading=false, hasItems=false)` (`MainScreen.kt:146-149, 233`). The user cannot tell "server down" from "no results", and there is no Retry anywhere.
   **Fix:** keep the `Throwable`; on failure show a distinct error state (e.g. `EmptyState(ctx, "🌐", "Couldn't load titles", msg, t)`) with a **Retry** `PrimaryButton { loadItems() }`; reserve the "No titles match your search" message for a successful-but-empty *search* only. (M3 Errors pattern: recover as soon as detected; Empty states: don't conflate error with empty.)

2. **high — Request button stays tappable during submit → duplicate requests in Sonarr/Radarr; feedback is only a Toast + full-screen `recreate()`.**
   `DetailActivity.kt:180` wires `PrimaryButton("＋ Request") { doRequest(media) }` and `doRequest` (`DetailActivity.kt:185-203`) fires the network call without disabling the button or any in-progress indicator; a double-tap calls `AppState.repo!!.request(media)` twice (duplicate *arr entries). On completion it does `recreate()` (`DetailActivity.kt:196, 202`), replaying all entrance animations and re-running `loadMagnets` (extra Prowlarr search) just to swap one button.
   **Fix:** set the button `enabled=false` with label `"Requesting…"` for the duration of the call, restore on failure; replace `recreate()` with a targeted `renderActions(actions, media, t)` re-render. (M3 loading-indicator guidance: indicate activity in progress; snackbar guidance: in-context feedback instead of bare Toast.)

3. **high — Status pills and the top-bar badge fail WCAG contrast (white 11sp text on solid status colors).**
   `Components.kt:365-367` builds `StatusPill` as `Chip(ctx, label, statusColorOf(status, t), t = t)` and `Chip` defaults `fg = 0xFFFFFFFF` (`Components.kt:337`). White on `success 0xFF34D399` ≈ **1.9:1**, on `warning 0xFFFBBF24` ≈ **1.8:1**, on `info 0xFF38BDF8` ≈ **2.0:1**; the badge chip (`Components.kt:149`, `Components.kt:161-165`) is white on `error 0xFFFB7185` ≈ **2.8:1** — all far below the 4.5:1 AA minimum for 11sp text. The repo already has `contrastRatio()` (`Tokens.kt:181-187`) and `DesignTokensTest` checks text-token contrast, but chip fg/bg combos are never checked.
   **Fix:** mirror the `ModeBanner` pattern (`Components.kt:576-582`): `Chip(ctx, label, withAlpha(color, 0.18f), color, t)`; add a unit test asserting `contrastRatio(fg, bg) >= 4.5` for every `Chip` color pair. (M3 Accessible design / WCAG 4.5:1.)

### Medium

4. **med — Multiple interactive elements are below the 48×48dp minimum touch target.**
   `CircleIconButton` is `ctx.dp(40)` (`Components.kt:143`) and is used for every top-bar action (📥 requests, ⚙ settings, ✕ close — `Components.kt:111-130`, `MainScreen.kt:76-81`, `DetailActivity.kt:47`) and the 🗑 delete in `RequestsActivity.kt:122`. `DangerButton` is ≈39dp tall (`py = dp(10)`, `Components.kt:205`); `PrimaryButton`/`SecondaryButton` are borderline ≈46dp (`py = dp(12)`, `Components.kt:181, 194`); the search clear "✕" is a bare 14sp TextView with no hit-area padding (`Components.kt:309-312`); `FormField` EditTexts are ≈43dp tall (`Components.kt:549-563`) vs the M3 56dp text-field height.
   **Fix:** `val size = ctx.dp(48)` in `CircleIconButton`; `minimumHeight = ctx.dp(48)` on all buttons and `FormField` fields; give the ✕ clear button 48dp padding (or wrap in a 48dp `FrameLayout`).

5. **med — Requests list re-animates every poll (constant motion, no change communication).**
   `RequestsActivity.render()` unconditionally calls `list.staggerIn(stepMs = 30)` (`RequestsActivity.kt:132`), and the live-mode ticker posts `render()` every 5s even when nothing changed (`RequestsActivity.kt:64-68`).
   **Fix:** diff by signature like `MainScreen` already does (`MainScreen.kt:220-227`): skip `render()` when the joined `id:status` list is unchanged; only `staggerIn` on actual change. (M3 Motion overview: motion should communicate change.)

6. **med — Destructive actions (delete request / cancel request) have no confirmation and only Toast feedback.**
   `RequestsActivity.kt:122-125` and `DetailActivity.kt:173-176` delete immediately on tap, with a `Toast` as the only feedback.
   **Fix:** show an `AlertDialog` ("Remove request for *title*?") for destructive deletes, and/or use an in-context snackbar-style view with an **Undo** action instead of `Toast` (M3 Snackbar guidance: in-context feedback preferred over system Toast; M3 dialogs: confirm destructive actions).

7. **med — Settings "Test connection" results overflow the screen; no per-service progress, no timeout.**
   `testResults` is a single non-wrapping horizontal row (`SettingsActivity.kt:70-71`), so the 5 result chips (`SettingsActivity.kt:115-122`) clip off-screen on narrow devices. `test()` (`SettingsActivity.kt:103-124`) shows a static "Testing…" while `pingAll()` (`LiveRepository.kt:57-65`) runs up to 5 sequential HTTP calls with 25s connect / 30s read timeouts (`Http.kt:22-23`) — potentially minutes with no cancel.
   **Fix:** make `testResults` a vertical/wrapping column; set each service's chip to "name…" before its ping and ✓/✗ after; run pings in parallel with an overall ~30s cap.

8. **med — Settings inputs are not validated; bad URLs save as "live mode on" and fail silently later.**
   `AppConfig.normalized()` (`Config.kt:24-33`) only trims; `liveReady` (`Config.kt:43-45`) only checks non-blank, so `"192.168.178.42:9696"` without a scheme is accepted. `save()` (`SettingsActivity.kt:96-100`) then toasts "Saved — live mode on", but every live call fails and the user lands on finding #1's fake empty state. No inline field errors are shown.
   **Fix:** validate scheme (`startsWith("http://") || startsWith("https://")`) in `liveReady`/save and show M3 text-field error text under the offending field after interaction (M3 text-fields: "show error text after user interaction").

9. **med — Search ignores the current tab: results from all media types render inside one tab's grid.**
   Placeholder: `AppState.catalog.search(q)` (`MainScreen.kt:160`) searches all types (`Catalog.kt:208-218`); live: `AppState.repo!!.search(key)` merges movies+series+books (`LiveRepository.kt:73-82`). Results are shown in the current tab (`MainScreen.kt:197-206`), so movies appear under the "Series" tab.
   **Fix:** filter `results.filter { it.type == currentTab }` in `loadItems`, or at minimum show a type chip on each `PosterCard` when `query` is non-blank.

10. **med — No loading indicator during background refresh of cached content (and no pull-to-refresh).**
    `showLoading(p, loading = true, hasItems = true)` (`MainScreen.kt:174`, logic at `146-149`) hides the skeleton whenever stale items exist, so the 45s-cache refresh and 5s status ticker give zero visual feedback that data is updating.
    **Fix:** add a slim top linear progress indicator over the pager while a refresh is in flight (M3 progress-indicators: indicate activity in progress), or add swipe-to-refresh.

11. **med — Detail hero height is inconsistent and `recreate()` replays the whole screen on data arrival.**
    `GradientPoster.onMeasure` ignores the incoming height spec and forces 3:2 (`Components.kt:397-400`), so the placeholder hero (`DetailActivity.kt:68-69`, `heroH = dp(300)` at line 59) renders ~1.5×width tall (≈490dp on a 360dp screen) while the TMDB image hero is exactly 300dp (`DetailActivity.kt:61-66`). Also `recreate()` at `DetailActivity.kt:98, 176, 196, 202` replays `popIn`/`slideUpIn` and re-fires `loadMagnets` (duplicate Prowlarr search) just to reflect new details/status.
    **Fix:** in `onMeasure`, honor `MeasureSpec.getSize(hSpec)` when `MeasureSpec.getMode(hSpec) == EXACTLY`; replace `recreate()` with targeted re-render of the meta card / actions / magnet header.

12. **med — "Copy magnet" gives no in-progress feedback during a blocking redirect resolution.**
    `copyMagnet` (`DetailActivity.kt:241-251`) does a network call (up to 55s, `Http.kt:22-23`) and only toasts "Magnet link copied" at the end.
    **Fix:** disable the `SecondaryButton` and set its label to "Resolving…" until the handler posts back; toast/snackbar on success *and* failure (currently failure silently copies `downloadUrl`).

13. **med — Infinite animations ignore the system "Remove animations" preference.**
    The aurora backdrop runs a 24s `INFINITE` `ValueAnimator` invalidating every frame (`AuroraBackground.kt:46-56`), and the skeleton shimmer repeats infinitely (`Components.kt:522-527`); nothing in the repo reads `Settings.Global.ANIMATOR_DURATION_SCALE` (grep: no matches).
    **Fix:** on attach, if `Settings.Global.getFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f`, render the aurora once (`renderOnce()`) and start no animator; render skeletons static. (Android accessibility: respect reduced motion.)

### Low

14. **low — Motion curves/durations deviate from M3 tokens.** `FastOutSlowIn = PathInterpolator(0.4f, 0f, 0.2f, 1f)` (`Motion.kt:23`) is the legacy M2 standard curve, not M3's standard/emphasized `cubic-bezier(0.2, 0, 0, 1)` / decelerate `(0.05, 0.7, 0.1, 1)`; `longMs = 520` (`Tokens.kt:117`) exceeds M3 `long2 = 500ms` (extra-long 1000ms is ambient-only) and drives the hero `popIn` with `OvershootInterpolator(1.6f)` (`Motion.kt:26, 66`) — a long, bouncy hero entrance; "transitions >400ms may feel too slow".
    **Fix:** map tokens to M3 durations (≈50/150/300/500) and use `PathInterpolator(0.2f, 0f, 0f, 1f)` / `(0.05f, 0.7f, 0.1f, 1f)` for enter/emphasis; drop overshoot on the hero or shorten to mediumMs.

15. **low — Posters are invisible to TalkBack.** No `contentDescription` is set anywhere (grep: zero matches); remote posters are bare `ImageView`s (`Components.kt:440-446`, `DetailActivity.kt:61-66`, `RequestsActivity.kt:101-103`) and `GradientPoster` draws the title on a canvas (`Components.kt:386-428`).
    **Fix:** `img.contentDescription = "${m.title} poster"` / set `contentDescription = m.title` on the poster frame in `PosterCard` and the detail hero. (M3 accessible design: non-text content needs a text alternative.)

16. **low — Mode banner tells the user to "tap ⚙" but the banner itself is not tappable.** `MainScreen.kt:81` adds `ModeBanner` (`Components.kt:576-582`) as plain text; the ⚙ it references is a separate 40dp icon button (finding #4).
    **Fix:** `banner.setOnClickListener { openRisingTo(SettingsActivity::class.java) }` + `pressable()`.

17. **low — Requests count label can disagree with the list.** `countLabel` shows `requests.size` (`RequestsActivity.kt:88`) but `render()` silently skips requests whose media no longer resolves (`RequestsActivity.kt:93` `?: return@forEachIndexed`) — "5 total" with 4 cards.
    **Fix:** count only rendered rows, or show the missing one as a generic "(title unavailable)" card.

18. **low — Stale search results render with no "updating" indication.** When a cached search result exists but is >45s old, `MainScreen.kt:197-199` renders it instantly and refreshes silently; the 350ms debounce (`Components.kt:321`) also means the grid swaps content with no spinner while a new query loads from cache-miss only.
    **Fix:** show a small in-field spinner / "updating…" while a search network call is in flight for the current query.

19. **low — Bottom nav container is ~64dp tall vs the M3 navigation-bar spec (80dp container); otherwise conformant.** Cells (`Components.kt:219-260`) are ≥48dp and the pill indicator + icon+label match the M3 navigation-bar pattern.
    **Fix:** pad `nav.root` vertically to reach the 80dp container height, or leave as a deliberate compact bar.

## Areas that are clean (one-liners)

- **Navigation pattern conforms**: 3 top-level tabs in a bottom bar with pill active indicator + icon + label, matching M3 "Navigation bar" (3–5 destinations) — `Components.kt:219-260`, `MainScreen.kt:105-110`.
- **First-load loading state exists**: per-tab skeleton grid (`MainScreen.kt:127-144`, `Skeleton` at `Components.kt:512-529`) — good; magnets section has explicit "searching indexers…" and "search failed: …" states (`DetailActivity.kt:208-215`); settings test has "Testing…" + per-service ✓/✗ chips (`SettingsActivity.kt:110-122`).
- **Empty states exist** with emoji + title + message (`EmptyState`, `Components.kt:496-510`) in main grid, requests, and magnets — per Material empty-state guidance (content itself is mislabeled on error, see #1).
- **Main grid avoids flicker** via signature-based diffing (`MainScreen.kt:220-227`) — good pattern, worth copying into `RequestsActivity` (see #5).
- **Press feedback present** on all interactive atoms (ripple foreground + press scale, `Components.kt:96-100`, `Motion.kt:88-100`) and poster cards are large tappable cells (`Components.kt:430-483`).
- **Edge-to-edge handled**: content padded for status/nav bars and cutout (`Insets.kt`), so no content hides under system UI.
- **Request flow has basic success/failure toasts** (`DetailActivity.kt:186, 195, 201`) — present, but Toast-based and without in-flight state (see #2, #6).
