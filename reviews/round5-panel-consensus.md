# Round 5 — Panel consensus (quality / simplicity / ux) → v4.6

Scope: master @ cd6abab (v4.5). Inputs: round5-{quality,architecture,simplicity,robustness,theming,ux}.md;
triage: r5panel-{quality,simplicity,ux}.md (2-of-3 consensus rule). All items below endorsed by >= 2 panels.

## Implemented in v4.6
Crash / data-loss class (3/3):
1. Startup StackOverflowError: BottomNavBar `initialized` set after `select()` + unconditional callback
   chain select→goToPage→onPageSelected→select. Fix: flag first, `selected` starts -1, same-index guards
   on both MainScreen callbacks (Components.kt, MainScreen.kt).
2. `WorkerScope.cancelAll()` from any Activity teardown killed unrelated workers (incl. the one-shot
   config load → stuck in placeholder mode). Removed from DetailActivity/MainScreen; spawn/cancelAll now
   atomic under one lock; `AppState.init` is @Synchronized + retryable (AppState.kt, DetailActivity.kt, MainScreen.kt).
3. `saveConfig` raw `Thread{}` unguarded → Keystore failure crashed the process + stuck "Saving…".
   Now WorkerScope.spawn + runCatching + onDone(ok) with error toast (AppState.kt, SettingsActivity.kt).
4. Settings Save-before-load wiped all stored keys: `configLoaded` latch; Save/Test guarded; form
   (re)filled in onResume once loaded (AppState.kt, SettingsActivity.kt).
5. `doRequest` double-add race: atomic `store.create` reservation FIRST, external POST after,
   `setExternal` on success, delete reservation on failure (DetailActivity.kt).

Lifecycle / persistence (2-3/3):
6. Rotation lost tab + query: Bundle threaded through showMainScreen + onSaveInstanceState (MainActivity.java, MainScreen.kt).
7. Rotation clobbered the new screen's onConfigChanged: compare-and-set on detach (MainScreen.kt).
8. `wasWiped` read before loadConfig set it → warning never fired: read after (AppState.kt).
9. Persistence: `commit()` instead of `apply()`; corrupt store quarantined to `requests_corrupt`;
   JSON serialized outside the lock; daemon persist thread (AppState.kt, Catalog.kt).
10. `AppState.init` disk read moved off the main thread (`deferLoad` + reload in worker) (AppState.kt, Catalog.kt).
11. Invisible 0-height skeleton on cold start: explicit height in GridLayout params (MainScreen.kt).

Security/robustness (2/3, cheap closures only):
12. realHttp validates the INITIAL url with isSafeExternalUrl (covers poster fetches); redirects must stay
    same-origin (scheme+host+port) to keep credentials; magnet redirect Location accepts only `magnet:`
    or a validated external .torrent (Http.kt).

UX / theming (2/3):
13. GradientPoster ink by worst gradient endpoint + mid-zone scrim; new DesignTokensTest asserts WCAG AA
    (>= 4.5) for title and 0.85-alpha label on all 12 catalog pairs (Tokens.kt, Components.kt, DesignTokensTest.kt).
14. Poster cells: PosterImageView keeps 2:3 aspect before bitmap arrives (no grid reflow); GradientPoster
    always behind the photo (no gray box on load failure) — grid + detail hero (Components.kt, DetailActivity.kt, ImageLoader.kt).
15. Requests list staggers only on first fill / count change, not every status tick; poller lambda checks
    lifecycle before touching views; `cancel()` alias removed (RequestsActivity.kt, AppState.kt).
16. Reduced-motion press feedback: no scale at all (Motion.kt). Aurora bitmap recycled on detach (AuroraBackground.kt).
17. Settings ping routed through RepositoryFactory (single construction rule) (SettingsActivity.kt).

## Rejected by consensus (backlog, not v4.6)
Module split / DI / interactor refactors (over-engineering for 22 files); dead-code sweeps; DNS-rebinding
pinning; spinner/back-gesture/dirty-guard/pull-to-refresh polish; badge tint-alpha tokens; light-theme picker.

Time note: round 5 started 05:58 CET, shipped after 06:00 CET → loop ends (5 iterations, max 6).
