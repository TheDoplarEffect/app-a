# Round 3 UX review — com.example.appa

Lens: M3 motion (m3.material.io/styles/motion), Android accessibility guide, 48dp touch
targets, Nielsen heuristics, M3 empty/loading/error states. Scope: current code under
`app/src/main/` only.

Coverage note: interactive touch targets were audited and **pass** — CircleIconButton
(Components.kt:144), all buttons (Components.kt:185,208,624), search clear
(Components.kt:341), nav cells (Components.kt:267-274), Switch (Components.kt:637) and
the mode banner (Components.kt:651) all meet the 48dp minimum (Android accessibility
guide, touch target size). Destructive actions (delete/cancel request) both have
AlertDialog confirmations + Toast feedback (RequestsActivity.kt:133-146,
DetailActivity.kt:224-236) — good per Nielsen #5/#9. The findings below are what remains.

---

## Ranked findings

### 1. HIGH — Requests list is torn down and re-animated on every status tick (flicker on refresh)
**File:** `RequestsActivity.kt:94` (`list.removeAllViews()`) and `:155` (`list.staggerIn(stepMs = 30)`), triggered from the poller callback at `:25-27`.
**What:** whenever the shared StatusPoller reports a change (every 1s placeholder tick, every 5s live tick), `render()` destroys **all** card views and re-adds them, each animating alpha 0→1 + translationY. The whole list visibly flashes every time any request moves pending→processing→available; TalkBack focus is also destroyed and falls back to the top of the screen.
**Why it matters:** M3 motion says animate *what changed*, not the whole container — re-running full entrance animations on a data update is exactly the flicker M3 warns against. Nielsen #1 (visibility of system status) is satisfied here only by making the entire list blink.
**Fix:** diff by request id — keep existing card views, update only the `StatusPill` text/color in place (the pill is already a separate view), call `staggerIn` only for newly added rows, and `announceForAccessibility("…is now processing")` on status change. Restore focus to the updated card if it had focus.

### 2. HIGH — Main grid rebuilt + container fade-blink on every poller-driven re-render
**File:** `MainScreen.kt:327` (`p.grid.removeAllViews()`) and `:335-336` (`p.grid.fadeIn(...)` even when `animate == false`), reached from the poller at `:48-51` and from cached renders at `:254`/`:280` (`animateFirst == false`).
**What:** `fadeIn()` (Motion.kt:52) sets `alpha = 0` then animates to 1, so every status change on any poster (or every cached search render) makes the **entire grid** fade from invisible. Same focus-loss problem as #1.
**Why it matters:** M3 motion — containers should not re-run entrance animations on content updates; Nielsen #1 — the update should be visible *without* the content disappearing.
**Fix:** when the grid is already populated and only the signature changed, update in place: diff cards by media id, update status pills, animate only inserted/removed cards; skip the container-level `fadeIn` unless it is the first population of that page.

### 3. MEDIUM — Typing a new search wipes the visible results and flashes the skeleton
**File:** `MainScreen.kt:258-259` (`showLoading(p, loading = true, hasItems = false)`) with `showLoading` at `:216-220` (scroll → GONE, skeleton → VISIBLE); debounce at `Components.kt:356` (350 ms).
**What:** for any query whose `tab|query` cache key is missing (i.e. every new keystroke after debounce), the current grid is hidden and replaced by the 9-cell skeleton, then swapped again when results arrive. The user sees results → skeleton → results on every search.
**Why it matters:** M3 loading states — when content is already on screen, use a *contained* progress indicator (e.g. a small spinner in the search row or a top progress line); the skeleton is for first load only. This is the classic "flicker on refresh".
**Fix:** keep the previous items visible while a new query is in flight and show a small inline `ProgressBar` (indeterminate, glass-styled) anchored under the search field; only show the full skeleton when the tab has never been loaded.

### 4. MEDIUM — A failed background revalidation replaces visible results with a full-screen error
**File:** `MainScreen.kt:274` (`showError(p, …)`) → `:285-291` (`p.scroll.visibility = GONE`, full-screen error view), on the stale-while-revalidate path that deliberately kept items visible at `:253-256`.
**What:** if a 45s-stale cache refresh fails (offline, *arr down), the grid the user is looking at disappears and is replaced by the "Couldn't load titles" screen — the data was fine a moment ago.
**Why it matters:** M3 error-state guidance — full-screen error is for when there is *no* content; on refresh failure keep the stale content and show an inline error/snackbar with Retry (the app already does exactly this pattern correctly on the Requests screen, `RequestsActivity.kt:25` staleLabel).
**Fix:** in `showError`, if `cache[key]` has items, keep `scroll` VISIBLE and add a dismissible error banner at the top of the page (plus Retry); reserve the full-screen error for the empty-cache case.

### 5. MEDIUM — Motion helpers ignore the system "reduced motion" setting
**File:** `Motion.kt:38` (slideUpIn), `:52` (fadeIn), `:65` (popIn), `:80` (staggerIn), `:95` (withPressScale), and `SwipePager.kt:37` (scroller animation in `goToPage`).
**What:** `reduceMotion()` (Motion.kt:17, reads `ANIMATOR_DURATION_SCALE`) is honored only by `AuroraBackground.kt:45` and `Skeleton` (Components.kt:578). Every entrance/press/page-swap animation runs at full duration regardless.
**Why it matters:** Android accessibility guide — honor the animator-duration-scale=0 preference by removing non-essential motion; M3 motion likewise expects a reduced-motion path. Users with vestibular disorders currently get every animation.
**Fix:** at the top of each helper: `if (context.reduceMotion()) { alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f; return this }` (and `animate = false` in `SwipePager.goToPage` when reduced motion is on).

### 6. MEDIUM — Settings form has no `adjustResize`; keyboard can cover fields and the Save/Test buttons
**File:** `AndroidManifest.xml:15` — only `MainActivity` (`:7`) declares `android:windowSoftInputMode="adjustResize"`; `SettingsActivity` (9 single-line `EditText`s in a ScrollView, `SettingsActivity.kt:56-100`) gets the default pan behavior. The fields also set no `imeOptions` (Components.kt:614-631), so the IME action does nothing.
**Why it matters:** Android keyboard/input guidance — `adjustResize` keeps the focused field and the action buttons visible above the IME; with `adjustPan` the focused URL field and the Save button can be pushed off-screen with no way to reach them except dismissing the keyboard. Keyboard handling gap on the only form screen.
**Fix:** add `android:windowSoftInputMode="adjustResize"` to `SettingsActivity`; set `imeOptions = IME_ACTION_NEXT` on all fields except the last (`IME_ACTION_DONE`) with a `OnEditorActionListener` that focuses the next field, and `scrollTo` the focused field.

### 7. MEDIUM — Form labels are not programmatically associated with their fields; inline errors are detached from the field
**File:** `Components.kt:614-631` (`FormField` returns a bare label `TextView` + `EditText`); `SettingsActivity.kt:56-70` adds the error `TextView` separately and never links anything.
**What:** no `labelFor`, no generated view id, and validation messages are a sibling `TextView` (`:58,64-65`), not `EditText.setError()`.
**Why it matters:** Android accessibility guide — use `labelFor` so TalkBack announces "Sonarr URL, edit box, <value>" instead of an unlabeled field; errors should be exposed via `setError()`/announced so they are found when the field is focused.
**Fix:** in `FormField`, `et.id = View.generateViewId()`, `lab.labelFor = et.id`, and have the validator call `et.setError(e)` (keeping the inline text if desired for sighted users).

### 8. MEDIUM — Invalid URLs don't block Save (validation is advisory only)
**File:** `SettingsActivity.kt:63-65` shows the URL error, but `save()` at `:118-131` never checks it and persists the config anyway (the follow-up Toast even admits it stayed in placeholder mode).
**Why it matters:** Nielsen #5 (error prevention) — the UI detects the slip but lets it through; the user "saves successfully" a config that cannot go live.
**Fix:** in `save()`, run `AppConfig().urlError(...)` over the URL fields; if any error, Toast + `requestFocus()` on the first invalid field and return without saving; optionally disable the Save button from the `TextWatcher` while any error is visible.

### 9. MEDIUM — Requests badge count is invisible to screen readers and never announced
**File:** `Components.kt:155` (contentDescription fixed to "Requests"), `:162-176` (`setBadge` only swaps the badge Chip's text), `MainScreen.kt:340-342` (`renderRequestsBadge`).
**What:** the numeric badge on the 📥 top-bar action changes (request created/removed) but the accessible name stays "Requests" and no accessibility event is fired.
**Why it matters:** Android accessibility guide — dynamic content changes should be reflected in the accessible name or announced via `announceForAccessibility()`; a TalkBack user cannot tell they have 3 pending requests.
**Fix:** set `contentDescription = "Requests, $n new"` in `setBadge` and call `announceForAccessibility("Requests updated, $n total")` when `n` changes.

### 10. LOW — Poster card description omits rating and request status; poster image duplicates the card node
**File:** `Components.kt:530` (`contentDescription = "${m.title}, ${m.year}, ${m.type.label}"`); rating chip at `:503`, status pill at `:510-513`; duplicate image description at `:497` (same in `RequestsActivity.kt:109,116`).
**What:** the ★ rating and the ✓/⏳/⚙ status are visual-only; TalkBack hears just title/year/type. The poster `ImageView`'s own contentDescription also creates a second focusable node inside the already-described clickable card.
**Why it matters:** Android accessibility guide — content descriptions must convey the meaningful state of the item; duplicate nodes add traversal noise.
**Fix:** card description = `"$title, $year, ${type.label}, rated %.1f" + statusLabelOf(...)`; set `importantForAccessibility = NO` on the poster image inside a described clickable card.

### 11. LOW — Save button gives no in-progress feedback while the Keystore encrypt runs
**File:** `SettingsActivity.kt:120` — `saveBtn.isEnabled = false` but the label stays "Save" during the async AES-GCM save (which the code itself notes can take 100ms+); `test()` at `:140-141` correctly switches to "Testing…".
**Why it matters:** Nielsen #1 (visibility of system status) — an action was triggered, the button silently disabled; the inconsistent sibling button shows the intended pattern.
**Fix:** `saveBtn.text = "Saving…"` alongside `isEnabled = false`.

### 12. LOW — Empty-state copy is wrong when the query is blank
**File:** `MainScreen.kt:205` — `EmptyState(…, "No titles match your search.")` is also shown when `query` is blank and a live browse returns zero items (`:334-335` → `showLoading(loading=false, hasItems=false)`).
**Why it matters:** M3 empty states — the supporting text must match the situation; "no titles match your search" with an empty search box is confusing.
**Fix:** choose the message by query: blank query → "Nothing trending right now — pull to retry" style copy; non-blank → the search-specific copy.

### 13. LOW — Detail screen silently closes when the media id can't be resolved
**File:** `DetailActivity.kt:44-45` — `?: run { finishDropping(); return }`.
**What:** tapping a card whose id is no longer resolvable (e.g. after a config wipe emptied the live cache) just closes the screen with no message.
**Why it matters:** Nielsen #1 — the system should tell the user what happened instead of vanishing.
**Fix:** `Toast.makeText(this, "This title is no longer available", …).show()` before `finishDropping()`.

### 14. LOW — Keyboard stays open when opening a detail from the grid
**File:** `MainScreen.kt:331` (PosterCard onClick → `openRisingTo(DetailActivity…)`); `hideKeyboard()` (`:189-192`) is only called on nav taps (`:110`) and the IME-search action (`Components.kt:329-336`) hides it, but tapping a result while the keyboard is up does not.
**Why it matters:** Android keyboard handling — navigating away from a screen should dismiss the soft input; otherwise the IME can linger over the new activity (both activities default soft-input mode).
**Fix:** call `hideKeyboard()` in the PosterCard click handler before `openRisingTo`.

---

## Top 5 changes

1. **Stop whole-container re-animation on data updates** — diff and update in place on the Requests list (`RequestsActivity.kt:94,155`) and the main grid (`MainScreen.kt:327,335-336`); animate only added/changed items. Kills the flicker from the 1s/5s pollers and preserves TalkBack focus. *(M3 motion; Nielsen #1)*
2. **Keep content visible during search and failed refreshes** — show a contained inline progress indicator instead of the skeleton when results already exist (`MainScreen.kt:258-259`), and on refresh failure keep stale results + inline error banner with Retry instead of the full-screen error (`MainScreen.kt:274,285-291`). *(M3 loading/error states)*
3. **Honor reduced motion everywhere** — gate `slideUpIn/fadeIn/popIn/staggerIn/withPressScale` and the pager scroll on `reduceMotion()` (`Motion.kt:38,52,65,80,95`, `SwipePager.kt:37`). *(Android accessibility guide; M3 motion)*
4. **Fix Settings form keyboard + accessibility wiring** — `adjustResize` on `SettingsActivity` (`AndroidManifest.xml:15`), IME_ACTION_NEXT chaining, `labelFor` + `setError()` in `FormField` (`Components.kt:614-631`), and block Save on invalid URLs (`SettingsActivity.kt:118-131`). *(Android keyboard & accessibility guides; Nielsen #5)*
5. **Make dynamic state accessible** — announce/include the requests badge count in the top-bar action description (`Components.kt:155,162-176`, `MainScreen.kt:340-342`) and add rating + request status to poster-card descriptions (`Components.kt:530`). *(Android accessibility guide)*
