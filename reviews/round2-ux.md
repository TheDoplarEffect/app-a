# Round 2 UX Review — /home/dev/app-a (lens: UX / Material 3 motion & states / accessibility)

Scope: current code under `app/` only. References cited per finding: Material 3 motion
(https://m3.material.io/styles/motion/overview), Android accessibility guide
(https://developer.android.com/guide/topics/ui/accessibility), M3 48dp minimum touch target,
Nielsen's 10 usability heuristics, M3 guidance on empty/loading/error states.

---

## Findings (ranked)

### 1. HIGH — Tapping a poster permanently kills that card's click handler
**Components.kt:517-520** (`PosterCard`)
```kotlin
setOnClickListener {
    if (!isClickable) return@setOnClickListener // double-tap guard
    isClickable = false
    onClick()
}
```
`isClickable` is set to `false` on the first tap and **never re-enabled**. Cards are only
re-created when `renderCurrent` runs (i.e., when the ticker detects a signature change in
`MainScreen.kt:250-264`). So after opening a detail page and coming back, tapping the same
poster again does nothing — no feedback, no navigation — until some unrelated status change
re-renders the grid. It also removes the card from TalkBack activation (clearing `CLICKABLE`
deactivates the accessibility action).
**Why it matters:** core navigation silently breaks after one use; violates Nielsen #3
(user control & freedom — actions must be reversible/consistent) and the Android
accessibility guide (every interactive element must remain actionable). A first-time user
who backs out of a detail page and re-taps the poster thinks the app is frozen.
**Fix:** drop the guard entirely (opening an activity is already idempotent), or use a
time-based guard that resets, e.g.:
```kotlin
private var lastTap = 0L
setOnClickListener {
    val now = SystemClock.uptimeMillis()
    if (now - lastTap < 400) return@setOnClickListener
    lastTap = now
    onClick()
}
```

### 2. HIGH — Error view stacks and never clears after repeated failures; stays visible during retry
**MainScreen.kt:212-242 (`showError`) and :255 (`renderCurrent`)**
`showError` does `p.root.removeView(p.empty)` then adds a new `errView` tagged `"error"` —
but it never removes a *previous* error view, and `renderCurrent` removes only the **first**
view found via `findViewWithTag("error")`. Sequence: load fails → error view A shown. Tap
Retry → `showLoading` shows the skeleton but error view A is still on top (z-order: added
last). Second failure → error view B added on top of A. Next successful load removes only
A; **B remains permanently** and overlays the grid forever.
**Why it matters:** M3 error-state guidance — an error state must be recoverable and must
disappear when data loads; Nielsen #1 (visibility of system status). As written, the screen
can wedge in a permanent error overlay, and the "Retry in progress" state is visually
contradictory (error + skeleton shimmer at once).
**Fix:** make the error view a permanent per-page child (add it once in `buildPage` like
`skeleton`/`empty`) and only toggle `visibility` in `showError`/`showLoading`/
`renderCurrent`; or at minimum call `p.root.findViewWithTag<View>("error")?.let { p.root.removeView(it) }`
at the top of `showError` and hide it in `showLoading`.

### 3. HIGH — Whole grid rebuilt + full fade/stagger on every refresh tick and every search keystroke (flicker/jank)
**MainScreen.kt:255-264 (`renderCurrent`), :177 (placeholder search), ticker at :55-72; RequestsActivity.kt:91-150**
`renderCurrent` does `p.grid.removeAllViews()`, re-adds every card, then runs
`p.grid.fadeIn(...)` (animate=false) or `staggerIn()` (animate=true). In live mode the
ticker fires `renderCurrent(animate=false)` every 5s whenever any request status changed →
the entire grid's alpha is set to 0 and faded back in → a visible blink of all posters. In
placeholder mode, every debounced keystroke (350ms, `SearchField`) calls
`renderCurrent(animate = true)` at MainScreen.kt:177 → the whole grid re-staggers (45ms ×
18 items + 300ms) while typing. `RequestsActivity.render()` likewise rebuilds the whole list
and runs `list.staggerIn(stepMs = 30)` on every status change.
**Why it matters:** M3 motion guidance — motion should clarify hierarchy, not re-animate
entire containers on every data change; repeated full-list animations read as flicker/jank
and burn frames (this is exactly the "flicker on refresh" failure). Nielsen #1/#6.
**Fix:** animate only on first load of a page or tab switch; for data updates, update in
place — diff the signature per item and only rebind/insert changed cells, and never set
container alpha to 0 on `animate=false` (just swap children). E.g. in `renderCurrent`, when
`!animate`, skip the `fadeIn` entirely and update only the `StatusPill` of changed cards.

### 4. MEDIUM — Live-mode "Save" silently falls back to placeholder mode with an ambiguous toast
**SettingsActivity.kt:112-117 (`save`)**
If the user enables "Use live services" but the config isn't `liveReady` (missing TMDB
token or both Sonarr/Radarr), `save()` still persists and toasts just `"Saved"` — the main
screen then shows "Placeholder mode" with no explanation of *what* was missing.
**Why it matters:** Nielsen #1 (visibility of system status) and #5 (error prevention):
the user believes live mode is on; the app contradicts them on the next screen. M3
error-state guidance wants the state and the remedy stated, not implied.
**Fix:** in `save()`, if `liveChecked && !cfg.normalized().liveReady`, show an AlertDialog
listing the missing pieces ("Live mode needs a TMDB token plus Sonarr or Radarr URL+key")
and toast the *actual* resulting mode: `"Saved — placeholder mode (live needs TMDB + Sonarr/Radarr)"`.

### 5. MEDIUM — Search keyboard is never dismissed except via the IME search key; bottom nav is covered while typing
**AndroidManifest.xml:11 (MainActivity has no `windowSoftInputMode`), Components.kt:297-350 (SearchField), MainScreen.kt:97-106**
The IME search action hides the keyboard (Components.kt:322-328 — good), but there is no
`adjustResize`, no dismissal when tapping a result or the pager, and no dismissal on tab
switch. With edge-to-edge drawing, the soft keyboard covers the bottom nav bar and part of
the grid, and the pager does not resize.
**Why it matters:** Nielsen #3 (user control & freedom — provide easy exits from a state)
and the Android guide's guidance on keyboard/IME handling; a first-time user searching
then trying to tap the bottom nav hits the keyboard instead.
**Fix:** add `android:windowSoftInputMode="adjustResize"` to `MainActivity` in the manifest,
and hide the IME when the pager page changes or a grid card is tapped:
```kotlin
val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
imm.hideSoftInputFromWindow(pager.windowToken, 0)
```

### 6. MEDIUM — Switch touch target is well under the 48dp minimum
**Components.kt:618-626 (`SwitchRow`), used at SettingsActivity.kt:79**
The `Switch` sits in a wrap-content row, so its height wraps the ~14sp label (~20-24dp) —
far below the 48×48dp minimum touch target.
**Why it matters:** Android accessibility guide + M3 touch-target guidance (48dp minimum);
mis-taps on adjacent fields are likely, and motor-impaired users are disproportionately
affected.
**Fix:** in `SwitchRow`, add `minimumHeight = ctx.dp(48); minimumWidth = ctx.dp(48)` (and
vertical padding so the row clears 48dp), or wrap the switch in a row with
`setPadding(0, dp(12), 0, dp(12))`.

### 7. MEDIUM — Search "clear" button is unlabeled for screen readers
**Components.kt:328-337**
`contentDescription = "Clear search"` is set on the **inner TextView**, but the clickable,
focusable element is the outer `FrameLayout` (`setOnClickListener` at :336). TalkBack
focuses the clickable container, which has no content description → an unlabeled
"double-tap to activate" control.
**Why it matters:** Android accessibility guide — every interactive control must expose a
meaningful label via `contentDescription` on the *clickable* node.
**Fix:** move the label to the clickable view:
```kotlin
val clear = FrameLayout(ctx).apply {
    contentDescription = "Clear search"
    ...
}
```

### 8. MEDIUM — Mode banner looks like a static status chip but is a hidden button, and its text points at the wrong target
**MainScreen.kt:83-86; Components.kt:632-639 (`ModeBanner`)**
The banner is a `Chip` with a click listener (opens Settings) and `pressable()`, but has no
`contentDescription`/action role for TalkBack, and in placeholder mode its text says
"tap ⚙ to connect your services" while tapping the banner *itself* also opens Settings —
two different instructions, one of them misleading.
**Why it matters:** Nielsen #1/#6 (affordances must be visible; system status must be
unambiguous) — a first-time user reads "tap ⚙", looks for a gear *inside* the banner, and
may never discover the banner is tappable; screen-reader users get no "action available"
hint.
**Fix:** change placeholder text to "🧪 Placeholder mode — tap to connect your services",
add `banner.contentDescription = "Placeholder mode. Tap to open settings and connect services"`,
and set an accessibility delegate marking it as a button (`AccessibilityDelegateCompat`-style
via `ViewCompat` is unavailable without androidx — use `banner.setAccessibilityDelegate(...)`
with `onInitializeAccessibilityNodeInfo` setting `ACTION_CLICK` + `ClickableSpanInfo`-free
`info.addAction(AccessibilityNodeInfo.ACTION_CLICK)`).

### 9. MEDIUM — "Test connection" has no in-flight disabled/loading state; repeated taps spawn concurrent ping threads
**SettingsActivity.kt:119-141 (`test`)**
Chips show "…" while pinging (partial feedback), but the button itself stays enabled and
unchanged — each tap clears `testResults` and starts another `Thread { LiveRepository(cfg).pingAll() }`,
so results can interleave.
**Why it matters:** Nielsen #3 / M3 loading-state guidance — a long-running action must show
a busy state on the control itself and prevent duplicate submission.
**Fix:** capture the button, `btn.isEnabled = false; btn.text = "Testing…"`, and re-enable in
the `handler.post` that updates the chips (also wrap `pingAll()` in `runCatching` so a crash
can't leave chips stuck at "…" forever — today an unexpected throw in the thread silently
skips the result update).

### 10. LOW — Predictive back gesture not enabled for targetSdk 35
**AndroidManifest.xml:11-15**
No `android:enableOnBackInvokedCallback="true"` on `<application>` or the activities, so on
Android 15 devices the system back gesture is disabled and back falls back to the legacy
finish without the animated transition; the screens only offer the custom "✕" buttons.
**Why it matters:** Android 15 predictive-back guidance (developer.android.com/guide/navigation/predictive-back-gesture)
— apps targeting 35 should opt in so users get the gesture + transition; M3 motion expects
consistent enter/exit transitions.
**Fix:** add `android:enableOnBackInvokedCallback="true"` to `<application>` (or per
activity), and register an `OnBackInvokedCallback` that calls `finishDropping()` for the
custom slide-down exit animation.

### 11. LOW — Request cards give no press feedback before opening detail
**RequestsActivity.kt:143-147**
`card.setOnClickListener { openRisingTo(...) }` with `contentDescription` but no
`pressable()` — unlike `PosterCard` and the banner, tapping a request row produces no
ripple/scale, so there is a dead moment before the transition starts.
**Why it matters:** M3 state-layer/feedback guidance and Nielsen #3 — every action needs
immediate perceptible feedback.
**Fix:** add `card.pressable()` after the click listener.

### 12. LOW — Requests screen silently shows stale statuses when live refresh fails (no error/offline affordance)
**RequestsActivity.kt:61-66**
`runCatching { AppState.repo?.refreshStatuses(...) }` discards the failure; the list keeps
showing old pills with no "offline / last updated" indication and no retry (unlike
MainScreen and the magnet list, which both have error + Retry).
**Why it matters:** M3 empty/loading/error guidance — the user must be able to tell fresh
data from stale data; Nielsen #1.
**Fix:** track a `lastRefreshOk`/`lastRefreshAt` and render a small warning chip
("⚠ Couldn't reach services — showing status from HH:mm") plus a Retry button when
`refreshStatuses` throws.

### 13. LOW — Every tab switch force-scrolls the page to the top, contradicting the "scroll positions preserved" pager
**MainScreen.kt:104**
`pager.onPageSelected` calls `pages[currentTab]?.scroll?.smoothScrollTo(0, 0)` on *every*
page selection — including swiping back to a tab the user had scrolled down in, even though
`SwipePager`'s doc comment (SwipePager.kt:6-8) promises preserved scroll positions.
**Why it matters:** Nielsen #6 (recognition rather than recall) — the user loses their
place every time they glance at another tab.
**Fix:** only reset scroll when the selection comes from the bottom nav (pass a flag from
`nav`'s callback), or remove the reset entirely.

### 14. LOW — Bottom-nav cells have no explicit contentDescription; announcement depends on child text merging
**Components.kt:255-263 (`BottomNavBar` cell)**
The clickable cell contains an emoji TextView and a label TextView but no
`contentDescription` on the cell itself; TalkBack behavior for merged descendant text on
clickable containers is inconsistent, and the emoji may be announced raw.
**Why it matters:** Android accessibility guide — icon+text actions should expose one
clean label.
**Fix:** `cell.contentDescription = "${item.icon} ${item.label}"` and set
`label.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO` on the children.

### 15. LOW — Per-screen cache is lost on rotation → skeleton flash + refetch
**MainScreen.kt:37 (`cache` is an instance field; Activity is recreated on rotation)**
Rotating the device destroys `MainScreen`, so the 45s stale-while-revalidate cache is gone;
the grid flashes skeletons and refetches even though data was seconds old.
**Why it matters:** perceived jank on a routine action (M3 motion guidance: avoid
unnecessary loading flashes; Nielsen #1).
**Fix:** hoist the cache to a `companion object`/process-level holder (it already outlives
screens by design) or persist last results via `onSaveInstanceState`.

---

## Top 5 changes I would make

1. **Fix the PosterCard one-shot click guard** (Components.kt:517-520) — re-enable via a
   400ms timestamp guard or remove it; today the tapped poster is dead after returning from
   detail.
2. **Make the main-screen error view a single toggleable child** (MainScreen.kt:212-242,
   :255) so repeated failures can't stack overlays and the error always clears on success
   and hides during retry.
3. **Stop re-animating whole lists on data updates** (MainScreen.kt:255-264, :177;
   RequestsActivity.kt:91-150) — animate only on tab/first load; update changed cells in
   place instead of `removeAllViews()` + `fadeIn`/`staggerIn` on every 5s tick and every
   search keystroke.
4. **Tell the truth about mode + keyboard** — Settings `save()` must say when live mode
   didn't actually engage and why (SettingsActivity.kt:112-117); add
   `windowSoftInputMode="adjustResize"` and dismiss the IME on tab switch/card tap
   (AndroidManifest.xml:11, MainScreen.kt:101-106).
5. **Close the accessibility gaps** — 48dp Switch target (Components.kt:618-626),
   contentDescription on the *clickable* clear button (Components.kt:328-337), actionable
   banner label (MainScreen.kt:83-86), nav-cell labels (Components.kt:255-263), and opt into
   predictive back (AndroidManifest.xml).
