# Round 2 — Triage Panel: SIMPLICITY (Ousterhout / KISS / YAGNI)

Scope: all six round-2 reports deduped against the code in `app/src/main/java/com/example/appa/`, `app/build.gradle`, manifest, `res/`.
My lens: does the change *remove* code/complexity, or add abstraction for hypothetical needs? Big refactors, rename-everything sweeps, and ridiculous-edge-case handling get REJECTED unless backed by a real bug/crash/security issue.

## 1. Triage table

| # | change | proposed by | your vote | justification |
|---|--------|-------------|-----------|-----------------|
| 1 | Fix `Resp.header()` NPE: `it.key?.equals(name, ignoreCase = true) == true` | robustness | APPROVE | `HttpURLConnection.headerFields` has a null status-line key; `it.key.equals(...)` NPEs (Http.kt:10-11), `realHttp`'s runCatching turns it into status 0, so every live "Copy magnet" silently fails (Http.kt:56). One-line crash-class fix. |
| 2 | Write path must not guess: `rootFolder()`/`qualityProfileId()` throw `ApiException` instead of `?: "/tv"` / `?: 1` | quality | APPROVE | Silent defaults flow straight into the `addSeries`/`addMovie` POST bodies (Clients.kt:236-237, 293-294 → 245-246, 302-303) — a failed lookup creates the series in the wrong folder, invisibly. |
| 3 | Adopt one error contract across layers (clients throw, repo catches only where intended, UI at boundary) | quality | MODIFY | Direction is right but it's a policy sweep over every call site. Ship #2 plus a doc comment that read-paths degrade to empty *by design*; don't restructure all layers. |
| 4 | Typed errors on malformed input: `tmdbId.toIntOrNull() ?: throw ApiException`, scheme check before `as HttpURLConnection`, wrap JSON parse | robustness | APPROVE | `Clients.kt:299` `tmdbId.toInt()` throws NumberFormatException on a blank externalId (kills the request thread, button stuck at "Requesting…"); Http.kt:26 cast masks bad schemes as "network error". Small, real crash paths. |
| 5 | Persist a minimal media snapshot (title/type/year/poster/externalId) with each request | architecture | APPROVE | Real user-visible data loss: after a cold start `mediaCache` is empty, so `RequestsActivity.render`'s `mapNotNull { AppState.mediaById(...) }` silently drops live-mode requests (RequestsActivity.kt:92; LiveRepository.kt:184-187). Keep it to a few extra fields (easy once #11 lands). |
| 6 | Reset `wasWiped = false` at the start of `saveConfig` | quality | APPROVE | One line; today Settings shows "⚠ Stored config was reset" for the rest of the process even after a successful re-save (SecureStore.kt:20, 58-61; SettingsActivity.kt:82-84). |
| 7 | One `ArrClient` base class (headers/url/api/post) shared by Prowlarr/Sonarr/Radarr | quality, simplicity | APPROVE | Deletes ~40 verbatim-copied lines (Clients.kt:175-286) that already drifted (retrying vs non-retrying GET at :42-43 vs :176-180). Pure dedup, no new abstraction. |
| 8 | `SeriesState`/`MovieState` data classes instead of `Triple` returns | quality, architecture | APPROVE | Positional `it.first` / `st?.third` with a comment-only contract (Clients.kt:259,311; LiveRepository.kt:163-166) becomes named fields; ~10 lines, removes a footgun. |
| 9 | Centralize `"radarr:<id>"`/`"sonarr:<id>"` encode+parse in ONE small helper next to `Request` | quality, architecture | MODIFY | The string wire format works; a sealed interface with 3 subtypes + encode/decode is *more* code than the problem. One `parseExternal(s): Pair<kind,id>` used by LiveRepository.kt:162-168, DetailActivity.kt:200, RequestsActivity.kt:121 removes the 3-site parsing. |
| 10 | Shared status poller (`startStatusPoller(onChanged)`) used by MainScreen and RequestsActivity | quality, simplicity | APPROVE | The only concurrency-heavy loop in the app is copy-pasted (MainScreen.kt:43-60 vs RequestsActivity.kt:60-75) and already differs subtly; one helper kills both. |
| 11 | Replace hand-rolled TSV+escaping persistence with a JSON array | simplicity | APPROVE | Deletes ~30 lines of fragile positional-index + escape/unescape code (Catalog.kt:152-181) for fields that can never contain tabs; kotlinx-serialization is already a dependency (build.gradle). |
| 12 | Delete dead code: `clearSilently`, `EmphasizedPath`, `Resp.networkError`, `MediaType.fromSlug`, `detailsRequested`, `AuroraBackground.renderOnce`, `glassDrawable`, unused elevation fields | quality, simplicity | APPROVE | YAGNI — none are called; `clearSilently` even misfires `onQuery("")` (Components.kt:339-351), so it's actively misleading. Deleting is the simplest change. |
| 13 | Collapse the duplicated radarr/sonarr arms in `refreshStatuses` into one kind/id lookup; make `audiobookIndexerIds` `@Volatile` | simplicity, quality, architecture, robustness | APPROVE | Two copy-pasted `when` arms (LiveRepository.kt:161-175) become one lookup; the plain `var` at :49 is a real JMM race next to its `@Volatile` siblings at :50-51. |
| 14 | Evict expired entries from MainScreen `cache`/`signatures` in `loadItems` | quality, robustness, simplicity | APPROVE | Two lines; today the maps grow one entry per distinct `tab|query` forever (MainScreen.kt:32-33, TTL only gates refetch at :273). |
| 15 | One `Context.reduceMotion()` helper used by Skeleton and AuroraBackground | simplicity | APPROVE | Same 3-line `ANIMATOR_DURATION_SCALE` dance duplicated at Components.kt:562-565 and AuroraBackground.kt:45-48. |
| 16 | BottomNavBar: keep icon/label view references instead of `getChildAt` casts | simplicity | APPROVE | `cell.getChildAt(1) as LinearLayout` (Components.kt:275-277) throws ClassCastException far from the cause on any reorder; storing the refs is simpler *and* safer. |
| 17 | GradientPoster: delete the no-op `lines[2].removeSuffix("…") + "…"`, simplify truncation | simplicity | APPROVE | Built lines never end with "…", so `removeSuffix` is dead logic around truncation (Components.kt:456). |
| 18 | One shared internal JSON accessor file (`str/dbl/int/arr/obj`) for Config and all clients | simplicity | APPROVE | Config.kt:64-76 hand-rolls what Clients.kt:22-34 already has privately; one home for each piece of knowledge. |
| 19 | Delete `Catalog.urlEncode`, use the shared `qenc` | quality | APPROVE | Two verbatim copies of the same encoder (Http.kt:99-100, Catalog.kt:302-303). |
| 20 | Simplify the placeholder magnet generator: one quality list, one size formula, one name template | simplicity | APPROVE | ~30 lines of 7-quality × 4-size `when` matrix (Catalog.kt:250-296) exist only to make fake demo rows look realistic — textbook YAGNI. |
| 21 | StatusPill: derive label and color from one condition; drop the dead `null` branch of `statusColorOf` | simplicity | APPROVE | An available item with no request currently gets a gray "✓ Available" because label and color use different conditions (Components.kt:376-397). One `when`, no split logic. |
| 22 | `RepositoryFactory.create(cfg)` used by both AppState and the Settings "Test connection" | architecture | APPROVE | One definition of "when does a live repo exist" instead of the duplicated rule in AppState.kt:31-38 and SettingsActivity.kt:134. Tiny. |
| 23 | Hoist MainScreen `cache` to a companion object so it survives rotation | UX | APPROVE | Two lines; today rotation wipes the 45 s stale-while-revalidate cache → skeleton flash + refetch (MainScreen.kt:32-37). |
| 24 | `showError`: remove any existing `"error"`-tagged view before adding a new one | quality, robustness, UX | APPROVE | Real user-visible bug: fail → Retry → fail stacks MATCH_PARENT error overlays, and a later success removes only the first tag match, leaving the grid permanently covered (MainScreen.kt:240-255). |
| 25 | PosterCard: replace the permanent `isClickable = false` with a 400 ms timestamp guard (or drop it) | UX | APPROVE | Core navigation bug: the card is dead after the first tap until an unrelated re-render (Components.kt:517-520); opening an activity is already idempotent, so the guard can go. |
| 26 | Settings: add `isUrl` flag so only the 4 URL fields get URL validation; move `urlError`/`validUrl` to `AppConfig.Companion` | quality, simplicity, architecture | APPROVE | Real bug: the TextWatcher runs `AppConfig().urlError(...)` on all nine fields, so typing any API key shows "URL needs http:// or https://" (SettingsActivity.kt:57-66; Config.kt:38-40). |
| 27 | Settings `save()`: honest toast/dialog when live mode didn't actually engage | UX | APPROVE | `save()` toasts plain "Saved" even when `!cfg.normalized().liveReady` (SettingsActivity.kt:112-117) — user believes live mode is on, main screen says Placeholder. |
| 28 | Stop re-animating the whole grid on data-only updates | UX | MODIFY | Full per-cell diffing is a refactor; the visible 5 s blink is just `p.grid.fadeIn(...)` when `animate=false` (MainScreen.kt:255-264) — skip the fade/stagger when `!animate` and the jank is gone. |
| 29 | `android:windowSoftInputMode="adjustResize"` on MainActivity + hide IME on tab switch / card tap | UX | APPROVE | Manifest one-liner + two `hideSoftInputFromWindow` calls; today the keyboard covers the bottom nav (AndroidManifest.xml:11; MainScreen.kt:97-106). |
| 30 | "Test connection": disable button + "Testing…" while pinging; wrap in `runCatching` | UX | APPROVE | Repeated taps spawn concurrent `Thread { LiveRepository(cfg).pingAll() }` and can leave chips stuck at "…" (SettingsActivity.kt:119-141). |
| 31 | Reset scroll position only for nav-initiated tab changes | UX | APPROVE | `pager.onPageSelected` force-scrolls to top even when swiping back to a scrolled tab (MainScreen.kt:104), contradicting SwipePager's preserved-scroll contract (SwipePager.kt:6-8). A boolean flag is enough. |
| 32 | A11y one-liners: 48 dp Switch target, `contentDescription` on the *clickable* clear button, nav-cell labels, `pressable()` on request cards, banner text + label | UX | MODIFY | Approve the 1-2 line fixes (Components.kt:328-337, 618-626; RequestsActivity.kt:143-147; MainScreen.kt:83-86); skip the AccessibilityDelegate machinery for the banner — the text+label fix removes the real confusion. |
| 33 | Requests screen: show a stale/offline note when `refreshStatuses` fails | UX | MODIFY | Keep it to one status line (`lastRefreshOk` flag, RequestsActivity.kt:61-66); skip a new Retry UI — MainScreen already has one. |
| 34 | Re-render MainScreen header (subtitle/banner/hint) in `onResume` | architecture | APPROVE | Real stale-UI bug: after saving live config, a still-alive MainActivity keeps showing "Placeholder" chrome (read once at attach, MainScreen.kt:75-88) while the ticker already runs live. |
| 35 | `AtomicBoolean` in-flight guard so 5 s ticks never stack threads | robustness | APPROVE | Unbounded `Thread`-per-5 s vs a worst case of ~100 s+ per `refreshStatuses` (MainScreen.kt:43-59; Http.kt:29-30, 78-90) — threads pile up and hammer the services. Two lines. |
| 36 | Pause tickers in `onStop`, resume in `onStart` | robustness | APPROVE | Only `onDestroy` stops the ticker (MainActivity.java:21-24), so Main + Requests poll simultaneously while hidden, doubling *arr load (RequestsActivity.kt:68). |
| 37 | Interrupt registered worker threads in `onDestroy` | robustness | MODIFY | Real Activity-leak-until-timeout issue (DetailActivity has no onDestroy at all); a minimal `MutableList<Thread>` + interrupt is enough — do NOT migrate to coroutines here (see #54). |
| 38 | Move Keystore/AES/SharedPreferences work off the main thread in `AppState`/`SecureStore` | robustness | MODIFY | Real cold-start jank/ANR risk: `AppState.init` runs from `onCreate` and does prefs + `KeyStore.load` + AES-GCM on main (AppState.kt:23-33; SecureStore.kt:28, 61-70). Keep the smallest possible background wrapper inside AppState; don't restructure the screens. |
| 39 | Cap image download bytes + decode with `inSampleSize` in ImageLoader | robustness | APPROVE | Uncapped `readBytes` + full-resolution `decodeByteArray` (ImageLoader.kt:30-38; Http.kt:40) is an OOM crash on one large poster; ~15 lines, standard pattern. |
| 40 | Cap the Jellyfin pagination loop (`TotalRecordCount` / max pages) | robustness | APPROVE | `while (true)` at Clients.kt:348-358 never terminates if the server always returns a full page — hangs the refresh thread and feeds #35. |
| 41 | Dismiss dialogs in `onDestroy` | robustness | APPROVE | RequestsActivity:131 and DetailActivity:173 dialogs are never dismissed → leaked window / BadTokenException; neither activity handles it. A `dialog?.dismiss()` per screen. |
| 42 | Fix `network_security_config.xml` so LAN IP literals work | robustness | APPROVE | `<domain>192.168.0.0</domain>` never matches host `192.168.178.42` — IP literals must be exact — so LAN HTTP live mode is dead at targetSdk 35 (res/xml/network_security_config.xml:5-12). Invert base-config and pin the public API domains to TLS-only. |
| 43 | Follow redirects manually (max 3, same-host, strip credentials on cross-host) | robustness | MODIFY | Real API-key leak: `HttpURLConnection` re-sends `X-Api-Key`/`Authorization` to any 3xx target (Http.kt:31; Clients.kt:43,176,217). Implement the minimal same-host version; skip allowlist machinery. |
| 44 | Minimal validation of Prowlarr-returned `downloadUrl` (http(s) scheme only; block 169.254.169.254 / loopback unless a configured host) | robustness | MODIFY | Real unvalidated fetch (Http.kt:53-60), but keep it to a few lines — no DNS-resolution/rebinding machinery. |
| 45 | Debug main-thread-network assertion in `realHttp` + trim the 15 s/20 s timeouts | robustness | MODIFY | Take the cheap `Looper.myLooper()` guard (debug) and a modest timeout trim; skip `Thread.sleep` → suspending `delay` (requires the rejected coroutine migration). |
| 46 | Extract per-section builder methods from `DetailActivity.onCreate`, `MainScreen.attach`, `RequestsActivity.render`, `PosterCard` | quality, simplicity | APPROVE | Mechanical extraction only, zero behavior change; `onCreate` is currently a 4-deep lambda pyramid burying the happy path (DetailActivity.kt:36-160). |
| 47 | Use the constructor `t` in `BottomNavBar.select` and the captured `t` in `MainScreen.renderCurrent` | quality, theming, simplicity | APPROVE | One theme read path: `select()` re-reads `AppTheme.current` while its glass was built from `t` (Components.kt:271,278; MainScreen.kt:62,256) — a half-skinned nav bar on any swap. |
| 48 | `realHttp`: set `Content-Type` only if the caller didn't supply one | quality | APPROVE | One line; today the transport silently overrides caller headers (Http.kt:35 vs Clients.kt:222,282). |
| 49 | Named arguments at the `Magnet` → `LiveMagnet` bridge | quality | MODIFY | The positional call (DetailActivity.kt:224-227) has different field orders in the two types; fix it with named args at the single call site — no new `LiveMagnet.from` factory. |
| 50 | Neutral example IP in Settings hints | quality | APPROVE | Trivial; `http://192.168.178.42:…` reads like real user config (SettingsActivity.kt:70-74). |
| 51 | `MediaRepository` interface with two impls, deleting all `if (AppState.liveMode)` branches | architecture | REJECT | Big refactor across 4 screens to remove one-line explicit branches; no user-visible bug, and the hand-rolled Magnet bridging it deletes is 4 lines. |
| 52 | Replace `object AppState` with an `AppContainer` wired in a custom Application (DI) | architecture | REJECT | Service-locator refactor touching every screen for testability only; the singleton works today and holds one atomic state pair (AppState.kt:15-17). |
| 53 | Split `LiveRepository` into 4 services + move orchestration into use cases / screen-state holders | architecture | REJECT | Adds abstraction layers (new interfaces, wrappers) with no bug to fix; each method in LiveRepository is currently a short, honest block. |
| 54 | Migrate all raw `Thread {}` to coroutines + `AppDispatchers` | architecture, robustness | REJECT | New dependency + rewriting every call site in 4 screens — exactly the big-refactor shape to avoid; the real risks are covered cheaply by #35/#36/#37. |
| 55 | Split into core/data/ui packages/modules | architecture | REJECT | Pure structural churn; the "no Android imports in domain" rule already works as a comment plus JVM tests (Catalog.kt:6-8). |
| 56 | Drop `color1/color2` from `Media`, derive gradient palette in the UI | architecture | REJECT | No bug — two string fields are harmless data; deriving palettes by hash in the presentation layer adds code and churn across Media/Catalog/Clients/Components. |
| 57 | Remove the `t: DesignTokens` parameter from ~40 component signatures; delete `PorcelainLight` + `AppTheme.use/reset` | simplicity | MODIFY | The 40-signature change is rename-everything churn. The real defect is the divergent read path, fixed cheaply in #47; the `t` params keep components pure and testable — keep them, delete only genuinely dead theme code. |
| 58 | Explicit `Page.error` field + `show(state)` enum redesign of the error/empty views | simplicity | REJECT | #24 fixes the actual bug in 2 lines; a State enum + single show() adds machinery for one defect. |
| 59 | Rename PascalCase factories (`AppText`, `Chip`, …) to lowerCamelCase across the codebase | quality | REJECT | Convention nit that renames every call site; no bug, no simplicity win. |
| 60 | Stop overloading `View.tag`: expose `actionButtons` map on AppTopBar | quality | REJECT | No live collision today ("two actions share an emoji" is hypothetical); adds component API surface. YAGNI. |
| 61 | Named-constants sweep (`SEARCH_DEBOUNCE_MS`, timeouts, page sizes…) across 5 files | quality, theming | REJECT | Churn without a bug; name a local `val` at the point of use if a number is genuinely confusing. |
| 62 | Make `ImageLoader` injectable via the container instead of an `object` | architecture | REJECT | Depends on the rejected DI refactor (#52); the object with an LruCache works fine. |
| 63 | Move `StatusRules`/`RequestStoreCore` into a `RequestStore.kt` file | architecture | REJECT | Pure file-move churn; the companion move is already covered by #26. |
| 64 | Scrim behind `GradientPoster` title text (like image posters already have) | theming | APPROVE | A few lines in `onDraw` reusing `scrimTop/scrimBottom` tokens (Components.kt:417-458); picking white-vs-dark by threshold cannot reach 4.5:1 across a diagonal gradient, and the scrim matches `PosterCard`'s image path (:494). |
| 65 | Contrast token value fixes: darken `accentStart/End`, add `accentText` for accent-as-text, darker light-theme status colors, solid badge fill | theming | APPROVE | Pure hex changes in Tokens.kt (+1 token, ~6 use sites); the primary CTA label currently fails WCAG AA on the gradient everywhere (Components.kt:182; Tokens.kt:113-114, 152-153). |
| 66 | Derive `glassLayered` border/sheen from the `glassBorder` token; delete the dead `if (darkBg) 0xFFFFFFFF else 0xFFFFFFFF` ternary | theming | APPROVE | Glass.kt:38 is a provably dead branch and :32-36 hardcode values the token system forbids; small change, also dead-code removal. |
| 67 | Set the window background from `AppTheme.current` in `edgeToEdge()`; drop the hardcoded hex/flag from themes.xml | theming | APPROVE | One-two lines; `themes.xml:4,7` duplicates `#0A0C14` outside Tokens.kt while `Insets.kt:24-29` already computes bar appearance dynamically. |
| 68 | Replace the four `res/anim/*.xml` transitions with code-built animations from MotionTokens | theming | REJECT | The XML anims work; deleting them and rebuilding in code adds code for a motion-consistency nicety, not a bug. |
| 69 | Token-tint framework Switch / AlertDialog / ripple for the light theme | theming | REJECT | The light theme is unreachable — `AppTheme.use` is never called — so there is no live bug; building token drawables for Switch/dialogs adds code for a hypothetical. |
| 70 | Snap all raw dp literals into SpaceTokens, unify the three button atoms, add SizeTokens / RadiusTokens.thumb | theming | REJECT | Cosmetic token-ization sweep across many files; no functional bug; violates "small, high-impact". |
| 71 | Opt into predictive back (manifest attr + `OnBackInvokedCallback` → `finishDropping`) | UX | APPROVE | One manifest attribute plus a small callback reusing the existing exit transition (AndroidManifest.xml; Motion.kt:108-118). |

## 2. My APPROVE/MODIFY list

| # | change (short) | my vote |
|---|----------------|---------|
| 1 | Fix `Resp.header()` null-key NPE | APPROVE |
| 2 | Write path (`rootFolder`/`qualityProfileId`) throws instead of silent defaults | APPROVE |
| 3 | One error contract across layers | MODIFY (narrow to #2 + doc comment) |
| 4 | Typed errors on malformed input (`toIntOrNull`, scheme check, JSON wrap) | APPROVE |
| 5 | Persist minimal media snapshot with each request | APPROVE |
| 6 | Reset `wasWiped` on save | APPROVE |
| 7 | Shared `ArrClient` base class | APPROVE |
| 8 | `SeriesState`/`MovieState` data classes for `Triple` | APPROVE |
| 9 | One helper for `radarr:/sonarr:` refs (not sealed hierarchy) | MODIFY |
| 10 | Shared status poller for both screens | APPROVE |
| 11 | JSON array persistence instead of TSV+escaping | APPROVE |
| 12 | Delete dead code (7+ symbols) | APPROVE |
| 13 | Collapse refreshStatuses `when` arms + `@Volatile audiobookIndexerIds` | APPROVE |
| 14 | Evict expired MainScreen cache entries | APPROVE |
| 15 | Shared `reduceMotion()` helper | APPROVE |
| 16 | BottomNavBar view refs instead of getChildAt casts | APPROVE |
| 17 | GradientPoster no-op `removeSuffix("…")` removal | APPROVE |
| 18 | Shared JSON accessors for Config + Clients | APPROVE |
| 19 | Delete duplicate `Catalog.urlEncode` | APPROVE |
| 20 | Simplify placeholder magnet matrix | APPROVE |
| 21 | StatusPill single-condition label/color | APPROVE |
| 22 | `RepositoryFactory.create(cfg)` | APPROVE |
| 23 | Hoist MainScreen cache to companion | APPROVE |
| 24 | `showError` removes existing error view first | APPROVE |
| 25 | PosterCard 400 ms tap guard instead of permanent disable | APPROVE |
| 26 | Settings `isUrl` flag + companion `urlError` | APPROVE |
| 27 | Honest save toast/dialog when live mode didn't engage | APPROVE |
| 28 | Skip grid fade when `animate=false` | MODIFY (no per-cell diffing) |
| 29 | `adjustResize` + hide IME on tab/card tap | APPROVE |
| 30 | Test-connection busy state + runCatching | APPROVE |
| 31 | Scroll-to-top only on nav-initiated changes | APPROVE |
| 32 | A11y one-liners (Switch target, clear-button label, nav labels, card pressable, banner text) | MODIFY (skip delegate machinery) |
| 33 | Stale/offline note on Requests refresh failure | MODIFY (one status line only) |
| 34 | Re-render MainScreen header in `onResume` | APPROVE |
| 35 | AtomicBoolean in-flight guard on tickers | APPROVE |
| 36 | Pause tickers in onStop / resume in onStart | APPROVE |
| 37 | Interrupt worker threads in onDestroy | MODIFY (thread registry, no coroutines) |
| 38 | Move keystore/crypto/prefs off main thread | MODIFY (smallest async wrapper in AppState) |
| 39 | Cap image bytes + inSampleSize decode | APPROVE |
| 40 | Cap Jellyfin pagination loop | APPROVE |
| 41 | Dismiss dialogs in onDestroy | APPROVE |
| 42 | Fix network_security_config for LAN IP literals | APPROVE |
| 43 | Manual same-host redirects, strip credentials cross-host | MODIFY (minimal version) |
| 44 | Minimal downloadUrl validation (scheme + metadata IP) | MODIFY (few lines only) |
| 45 | Debug main-thread-network guard + timeout trim | MODIFY (skip coroutine delay) |
| 46 | Extract per-section builders from long onCreate/attach/render | APPROVE (mechanical only) |
| 47 | Use constructor `t` in BottomNavBar.select / captured `t` in renderCurrent | APPROVE |
| 48 | Content-Type only if absent in realHttp | APPROVE |
| 49 | Named args at Magnet→LiveMagnet call site | MODIFY |
| 50 | Neutral example IP in settings hints | APPROVE |
| 57 | Theme-param removal | MODIFY (fix divergent reads via #47, keep params) |
| 64 | Scrim behind GradientPoster title | APPROVE |
| 65 | Contrast token value fixes (accent gradient, accentText, status colors) | APPROVE |
| 66 | glassLayered from tokens + dead ternary fix | APPROVE |
| 67 | Window background from AppTheme in edgeToEdge() | APPROVE |
| 71 | Predictive back opt-in | APPROVE |

REJECTED by me: #51, #52, #53, #54, #55, #56, #58, #59, #60, #61, #62, #63, #68, #69, #70.

## 3. CONSENSUS CANDIDATES

From my seat, every item I voted APPROVE or MODIFY is a consensus candidate (the orchestrator should mark a change as consensus when ≥2 of 3 panelists vote APPROVE/MODIFY; my votes are the table in section 2):

**Candidates (my APPROVE/MODIFY):** 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 57, 64, 65, 66, 67, 71.

**Highest-confidence consensus (multi-report, small, bug-backed — I APPROVEd all):** #1 (header NPE, 1 line), #2 (silent write defaults), #7 (ArrClient dedup — proposed by quality+simplicity), #10 (shared poller — quality+simplicity), #11 (JSON persistence — simplicity), #12 (dead code — quality+simplicity), #13 (@Volatile + when-collapse — 4 reports), #14 (cache bound — 3 reports), #24 (showError stacking — quality+robustness+UX), #25 (PosterCard dead tap — UX), #26 (settings URL validation — quality+simplicity+architecture).

## 4. Notes

- **What I rejected as complexity-adding:** the architecture report's big three (MediaRepository interface, AppContainer DI, package split) and the coroutine migration are all multi-file refactors with no user-visible bug today — the `liveMode` branches, the `AppState` object, and raw threads are explicit and working. The robustness report's real risks (thread pile-up, main-thread crypto, dialog leaks, OOM, unbounded loops) are each fixable in a few lines (#35-41) without the migration; I MODIFIED those proposals to their minimal forms.
- **YAGNI calls:** theme-parameter mass removal (#57), View.tag redesign (#60), magic-constant sweeps (#61), token-ization of every dp literal (#70), framework-widget tinting for an unreachable light theme (#69), and XML→code anim replacement (#68) all add code or churn for hypothetical future needs. The one real theme inconsistency (mixed `t` vs `AppTheme.current` reads) is a two-line fix (#47).
- **Dedup is my biggest win category:** ArrClient (#7), status poller (#10), TSV→JSON (#11), dead code (#12), JSON accessors (#18), URL encoder (#19), reduceMotion (#15) — together these delete roughly 150-200 lines of duplicated/fragile code with zero behavior change.
- **Bug-backed small fixes I fully support:** #1, #2, #4, #5, #6, #24, #25, #26, #35, #39, #40, #42 — each is grounded in a crash, data loss, security, or user-visible defect visible in the code I read.
- I did not read `reviews/round1-*.md` and changed nothing under `app/`.
