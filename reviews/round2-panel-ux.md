# Round 2 triage — UX panelist (focus: Material 3 motion/states, accessibility, 48dp targets, Nielsen heuristics, WCAG contrast)

Scope reviewed: all six round-2 reports plus `app/src/main/java/com/example/appa/*.kt`, `app/build.gradle`, `AndroidManifest.xml`, `res/`. Contrast claims re-computed with the same WCAG math shipped in `Tokens.kt` (`luminance`/`contrastRatio`) — the theming report's numbers reproduce exactly.

Voting lens: does the change fix a real user-visible problem (bug users hit, crash/ANR, flicker, missing feedback, a11y barrier, contrast failure)? Small/high-impact/low-risk preferred; big refactors and rename-everything churn rejected.

## 1. Triage table

| # | change | proposed by | your vote | justification |
|---|--------|-------------|-----------|---------------|
| 1 | Make `rootFolder()`/`qualityProfileId()` failures fatal in `addSeries`/`addMovie` (no silent `"/tv"`/`1` defaults in write POSTs) | quality | APPROVE | `Clients.kt:236-237,293-294` swallow lookup failures and POST a guessed root folder/profile — user sees "Added to Sonarr" while the series lands in the wrong place; existing `doRequest` toast already surfaces the thrown error (`DetailActivity.kt:200-207`). |
| 2 | One error-handling contract across all layers (clients throw, repo catches only where intended) | quality | REJECT | Broad cross-layer refactor; the user-visible part (error states, stale data) is already covered by #7, #66, #41 — no additional user-facing win. |
| 3 | Collapse Prowlarr/Sonarr/Radarr into one `ArrClient` base class | quality, simplicity | MODIFY | Pure dedupe, no behavior change — fine, but land it only after #34 (header NPE fix) so the refactor can't mask it; keep retry/header logic identical. |
| 4 | Replace `Triple` returns with named `SeriesState`/`MovieState` data classes | quality, architecture | APPROVE | `Clients.kt:259,311` + positional `it.first`/`st?.third` in `LiveRepository.kt:163-166` — two lines of data classes remove a real swap-risk, zero UI risk. |
| 5 | Typed sealed `ExternalRef` instead of `"radarr:<id>"` strings | quality, architecture | MODIFY | Right idea, but keep the wire format byte-identical and keep `parse` able to read already-persisted `radarr:`/`sonarr:` strings (`Catalog.kt` store), or existing users' requests break. |
| 6 | Settings: validate only the four URL fields as URLs (`isUrl` flag); move `urlError` to companion | quality, simplicity | APPROVE | Real user-visible bug confirmed: `SettingsActivity.kt:57-66` attaches `AppConfig().urlError(...)` to all nine fields and `Config.kt:38-40` rejects anything not starting with `http(s)`, so the first keystroke in any API-key field shows a bogus red "URL needs http:// or https://". |
| 7 | `showError` must not stack error views / must clear on success and hide during retry (single toggleable error child) | quality, robustness, ux | APPROVE | Confirmed in `MainScreen.kt:212-243` (adds `"error"`-tagged view without removing the previous one; detaches `p.empty`) and `:255` (removes only first tag match) — after fail→Retry→fail→success one MATCH_PARENT error view stays over the grid forever; also error+skeleton shown simultaneously during retry. |
| 8 | Split long `onCreate`/`attach`/`render`/`PosterCard` into per-section builders | quality, simplicity | REJECT | Pure readability refactor (~90-line functions); no user-visible bug, no crash, high diff churn — defer. |
| 9 | Named constants for all magic numbers (timeouts, debounce, page sizes…) | quality | REJECT | Renaming churn across five files with zero user-visible effect; the only UX-relevant number (350 ms debounce) works fine. |
| 10 | Status polling: one shared poller + in-flight guard + pause in `onStop` + cancel on destroy | quality, simplicity, architecture, robustness | MODIFY | Real risk confirmed (`MainScreen.kt:43-60`, `RequestsActivity.kt:60-75`: new `Thread` every 5 s/1 s, no overlap guard, no `onStop` pause → thread pile-up and doubled *arr load while another screen is open). But `build.gradle` has **no coroutines dependency**, so implement with `AtomicBoolean` skip + `handler.removeCallbacks` in `onStop`/`onStart` — do not migrate to `CoroutineScope`. |
| 11 | Delete dead code (`clearSilently`, `EmphasizedPath`, `Resp.networkError`, `MediaType.fromSlug`, `detailsRequested`, `renderOnce`) | quality, simplicity | APPROVE | Six unused symbols verified by grep; `clearSilently` even lies (it fires `onQuery("")`), misleading readers about tab-switch behavior. Zero-risk deletion. |
| 12 | Bound `MainScreen` `cache`/`signatures` maps (TTL eviction or cap) | quality, robustness, simplicity | REJECT | Slow memory growth only after many distinct searches; no user-visible symptom in realistic sessions — not worth round-2 risk. |
| 13 | Make `LiveRepository.audiobookIndexerIds` `@Volatile` like its siblings | quality, architecture, simplicity, robustness | APPROVE | One annotation; `LiveRepository.kt:49` is the only non-`@Volatile` mutable field written from ticker/magnet threads — real (if benign-today) JMM race. |
| 14 | Reset `SecureStore.wasWiped` on successful `saveConfig` | quality | APPROVE | Real user-visible bug: `SecureStore.kt:20,67` sets the flag and `saveConfig` never clears it, so `SettingsActivity.kt:82-84` shows "⚠ Stored config was reset" for the rest of the process even after the user re-enters and saves keys. One line. |
| 15 | Rename PascalCase factories to lowerCamelCase; dedupe `qenc`/`urlEncode` | quality | REJECT | Rename-everything churn across the whole UI layer with no user-visible payoff. |
| 16 | Stop overloading `View.tag`; expose `actionButtons` map on `AppTopBar` | quality | REJECT | Internal coupling only; `findViewWithTag("top_action_📥")` works today — no user-visible failure. |
| 17 | Misc leaky details bundle (hint IP string, `LiveMagnet` positional bridge, Content-Type override, stub comment) | quality | REJECT | No user-visible bug in any item; the `LiveMagnet` field-order risk is covered by #4's named-type direction. |
| 18 | `MediaRepository` interface + delete all `if (AppState.liveMode)` branches in screens | architecture | REJECT | Big refactor (~10 call sites, new interface + 2 impls) — the actual user-visible symptom (stale header after saving settings) is fixed far more cheaply by #22. |
| 19 | Replace `object AppState` with injectable `AppContainer` + immutable snapshot | architecture | REJECT | Large DI refactor of every screen; no direct user-visible bug — defer to a dedicated architecture round. |
| 20 | Persist a minimal media snapshot (title/type/year/poster/externalId) with each request | architecture | APPROVE | Real data-loss bug: live-mode requests are dropped after cold start because `RequestsActivity.kt:92` `mapNotNull { AppState.mediaById(...) }` finds nothing in the empty `LiveRepository.mediaCache` (`LiveRepository.kt:184-187`). Small additive change to the store format. |
| 21 | Split `LiveRepository` into services; extract use-case/screen-state holders from views | architecture | REJECT | God-object cleanup is a big refactor with no user-visible bug of its own. |
| 22 | Re-render top-bar subtitle/banner/search hint on `onResume` (or make config observable) | architecture | APPROVE | Confirmed: `MainScreen.attach()` reads `AppState.liveMode` once (`MainScreen.kt:75,83,88`) while the ticker re-reads it — after saving live config in Settings and returning, the header still says "Placeholder" while behavior is live. Minimal fix: refresh chrome in `MainActivity.onResume`. |
| 23 | Drop `color1`/`color2` from `Media`, derive gradient palette in presentation layer | architecture | REJECT | Refactor with no visible change today; the actual visible problem (contrast) is #44. |
| 24 | Split into `core`/`data`/`ui` packages/modules | architecture | REJECT | Structural refactor, compile-time enforcement nice-to-have, zero user-visible effect this round. |
| 25 | `RepositoryFactory` used by both container and Settings test | architecture | REJECT | Two-line duplication with no user-visible consequence. |
| 26 | Make `ImageLoader` injectable instead of a global object | architecture | REJECT | Testability-only change; no user-visible issue (OOM risk handled by #36). |
| 27 | Replace hand-rolled TSV+escaping persistence with a JSON array | simplicity | MODIFY | Good simplification (~30 fragile lines in `Catalog.kt:152-181`), but require a one-time read of the legacy TSV format on first load or existing users' stored requests are wiped. |
| 28 | `StatusPill`: derive label and color from one condition (available ⇒ success color) | simplicity | MODIFY | `Components.kt:383-397` computes label from `media.available` but color from `status`, so an available-but-unrequested item gets a gray "✓ Available" pill — one `when` fixes the split; keep it a 3-line change. |
| 29 | One `Context.reduceMotion(): Boolean` helper in `Motion.kt` | simplicity | APPROVE | Both call sites (`Components.kt:562-565` Skeleton, `AuroraBackground.kt:45-48`) already honor reduce-motion — deduping keeps that accessibility behavior consistent; 5-line helper. |
| 30 | `BottomNavBar`: keep view references instead of `getChildAt` casts | simplicity | REJECT | Hypothetical `ClassCastException` only if children are reordered — no current user-visible bug. |
| 31 | `GradientPoster` truncation no-op cleanup (`removeSuffix("…")`) | simplicity | REJECT | Dead but harmless expression; text renders correctly today — ridiculous edge case. |
| 32 | Shared `JsonX` accessors for `Config` + clients | simplicity | REJECT | Internal dedupe, no user-visible effect. |
| 33 | Simplify the 7-quality×4-size placeholder magnet generator | simplicity | REJECT | Demo-data realism only in placeholder mode; users don't read fake sizes — YAGNI cut but no user win. |
| 34 | Fix `Resp.header()` NPE on the null status-line key (`it.key?.equals(...) == true`) | robustness | APPROVE | Highest-impact one-liner in round 2: `Http.kt:10-11` — `getHeaderFields()` always contains a null key, so `magnetFromDownloadUrl` (`Http.kt:56`) throws on every 3xx and "Copy magnet" always fails in live mode with a generic "Magnet resolve failed: null" toast (`DetailActivity.kt:261-278`). |
| 35 | Move SharedPreferences/KeyStore/AES-GCM I/O off the main thread in `AppState.init`/`saveConfig` | robustness | MODIFY | Real cold-start jank/ANR risk (`AppState.kt:23-39` runs disk+keystore+crypto in every activity's `onCreate`), but keep it minimal: load lazily/async before first live use and encrypt on a background executor — don't restructure `AppState`. |
| 36 | Cap HTTP body read + decode bitmaps with `inSampleSize` in `ImageLoader` | robustness | APPROVE | Real OOM crash risk: `Http.kt:40` reads unbounded bytes and `ImageLoader` decodes full-resolution posters; one oversized cover can kill the app. |
| 37 | Fix `network_security_config.xml` so LAN HTTP actually works (the `10.0.0.0`-style `<domain>` entries never match real IPs) | robustness | APPROVE | Confirmed: `<domain>` matches hostnames/exact IPs only, so `192.168.0.0` never matches `192.168.178.42` and the very URL the settings hint suggests (`SettingsActivity.kt:71`) fails with a confusing generic "network error" — the core live feature is broken on LAN. Approve the inversion (base cleartext on, public API domains pinned to TLS-only) as the only workable option. |
| 38 | Follow redirects manually; strip `Authorization`/`X-Api-Key` on cross-origin hops | robustness | APPROVE | Security issue qualifies: `HttpURLConnection` re-sends API keys to any 3xx target (`Http.kt:31,67,84`). |
| 39 | Validate Prowlarr-returned `downloadUrl` before fetching | robustness | MODIFY | Approve a minimal version only: require `http(s)` scheme and reject loopback/link-local/metadata IPs — skip the full SSRF framework and the settings-side public-host warning. |
| 40 | Cap the unbounded `while(true)` pagination in `JellyfinClient.itemTmdbIds` | robustness | APPROVE | `Clients.kt:348-358` loops forever on a misbehaving server, wedging the refresh thread (feeds #10); two-line cap + `TotalRecordCount` check. |
| 41 | Typed parse errors (`toIntOrNull`, scheme check, JSON wrap → `ApiException`) | robustness | REJECT | `doRequest` already wraps everything in `runCatching` and shows a "Failed: …" toast (`DetailActivity.kt:200-207`) — no crash, no stuck button; only error-message quality improves. |
| 42 | Dismiss `AlertDialog`s in `onDestroy` (`dialog?.dismiss()`) | robustness | APPROVE | Real `BadTokenException`/window-leak class: `RequestsActivity.kt:131` and `DetailActivity.kt:173` show dialogs, `DetailActivity` has no `onDestroy` at all. 4-line fix. |
| 43 | Remove the `t: DesignTokens` parameter from ~40 functions; delete `PorcelainLight` + `AppTheme.use/reset` | simplicity | REJECT | Massive signature churn; `AppTheme.use` is never called, so deleting the dead theme is safe, but rewriting every call site is rename-everything scope with no visible change. |
| 44 | Fix `GradientPoster` title contrast — scrim behind text (as image posters already do) instead of the broken luminance threshold | theming | APPROVE | Verified with WCAG math: white text on the brightest gradient endpoint is 2.04–3.48:1 on 9 of 12 `Catalog.kt:190-193` pairs (`Components.kt:417-422` picks white when avg luminance ≤ 0.45); `labelPaint` at 0.85 alpha is worse. Poster titles are the primary grid content — a real WCAG SC 1.4.3 failure. |
| 45 | Darken `accentStart`/`accentEnd` so the white primary-button label passes 4.5:1 across the whole gradient | theming | APPROVE | Verified: dark `0xFF818CF8→0xFFC084FC` gives 2.98/2.64:1, light `0xFF6366F1→0xFF8B5CF6` 4.47/4.23:1 — the "＋ Request"/"Retry"/"Save" CTA fails AA everywhere in both themes (`Components.kt:182`, `Glass.kt:56-64`); proposed values pass (6.29/5.38 dark, 7.90/5.70 light) and are a pure token edit. |
| 46 | Add an `accentText` token for accent-colored *text* on glass (dark needs ≥0.237 luminance) | theming | APPROVE | Verified: `accentSolid` 0xFF6D5AE0 as text on dark glass is 3.28:1 (`Components.kt:194` SecondaryButton, `:534` SectionHeader action); proposed `0xFFA5B4FC` = 8.25:1. Two call sites + one token. |
| 47 | Darken status colors for tinted-chip rendering + solid badge fill | theming | APPROVE | Verified: light-theme status text on its own 22% tint over light glass is 3.62–4.18:1 (all fail), dark error-on-tint 3.64–4.27 — proposed values (success `0xFF036B4C` etc.) pass at 4.54–5.24; badge should be `textOnAccent` on solid `error` (`Components.kt:150,166,397,372,634`). Request status is core info. |
| 48 | `glassLayered`: fix the dead ternary at `Glass.kt:38` and derive border/sheen from `glassBorder` token; delete duplicate `glassDrawable` | theming | MODIFY | The ternary `if (darkBg) 0xFFFFFFFF else 0xFFFFFFFF` is genuinely dead code, but full tokenization is optional polish — approve only the small fix (single expression + token-derived border), not a redesign. |
| 49 | Remove hardcoded `#0A0C14`/`windowLightStatusBar` from `themes.xml`, set window bg from tokens in `edgeToEdge()` | theming | REJECT | Only matters if `AppTheme.use(PorcelainLight)` is ever called — it isn't (verified by grep); `Insets.kt:24-29` already computes bar appearance dynamically. No user-visible change today. |
| 50 | Replace `res/anim/*` XML transitions with token-driven code animations | theming | REJECT | Transitions render fine (180–320 ms is plausible M3 timing); rewriting four XML files into code is churn with no user-visible defect. |
| 51 | One theme-source policy (`BottomNavBar.select`/`MainScreen.renderCurrent` use captured `t` vs `AppTheme.current`) | theming, quality | REJECT | Only produces a half-skinned UI on a runtime theme swap that never happens — no visible bug. |
| 52 | Tint framework widgets (Switch, AlertDialog, ripple) for the light theme | theming | REJECT | PorcelainLight is never activated; under the active dark theme the framework widgets match. (The real Switch issue is touch-target size — #60.) |
| 53 | Unify the three button atoms (one padding, one `TITLE` style) | theming | MODIFY | `DangerButton` uses `TextStyle.BODY` while `PrimaryButton`/`SecondaryButton` use `TITLE` with three different paddings (`Components.kt:183,197,209`) — a visible inconsistency on the same actions row; approve only the button unification, skip the 2/3/6/10dp snapping. |
| 54 | Add `RadiusTokens.thumb` for list thumbnails and use `radius.poster` for Skeletons | theming | APPROVE | Visible shape mismatch: `RequestsActivity.kt:105,112` thumbs and `Components.kt:559` skeletons use `radius.button` (16dp) where poster cards use `radius.poster` (20dp) — the loading→loaded transition visibly jumps shape. Two-line token fix. |
| 55 | Add `SizeTokens` (named 48dp touch target, hero/thumb sizes) | theming | REJECT | Pure renaming — 48dp `minimumHeight` is already set on buttons/fields (`Components.kt:144,185,199,211`); the real 48dp gap is the Switch (#60) and banner (#62). |
| 56 | Fix the `PosterCard` one-shot click guard (`isClickable = false` never reset) | ux | APPROVE | Confirmed in `Components.kt:517-520`: after opening a detail page and coming back, the same poster is dead (no navigation, no TalkBack click action) until an unrelated status change re-renders — core navigation silently breaks after one tap. Time-based 400 ms guard or plain removal. |
| 57 | Stop rebuilding + re-animating the whole grid on every 5 s tick and every search keystroke | ux | APPROVE | Confirmed: `renderCurrent(animate=false)` still calls `p.grid.fadeIn(...)` which sets alpha=0 then fades (`MainScreen.kt:263-264`) → visible blink on every status change; placeholder search calls `renderCurrent(animate=true)` per debounced keystroke (`MainScreen.kt:177`) → full stagger while typing; `RequestsActivity.kt:150` re-staggers on every poll. Minimal fix: skip the fade when `!animate`, stagger only on first load/tab switch. |
| 58 | Settings `save()` must state the actual resulting mode and why live didn't engage | ux | APPROVE | Confirmed `SettingsActivity.kt:112-117`: with live switch on but `liveReady` false it just toasts "Saved" and the main screen contradicts the user with "Placeholder" (`MainScreen.kt:83-86`) — Nielsen #1/#5 violation; small dialog/toast change. |
| 59 | Add `android:windowSoftInputMode="adjustResize"` to MainActivity; hide IME on tab switch / card tap | ux | APPROVE | Confirmed: manifest line 11 has no `windowSoftInputMode`; with edge-to-edge the keyboard covers the bottom nav and pager (`MainScreen.kt:97-106`); only the IME search key dismisses it today (`Components.kt:322-328`). |
| 60 | Give the live-mode `Switch` a 48dp touch target | ux | APPROVE | Confirmed: `SwitchRow` (`Components.kt:618-626`) is a wrap-content row with no `minimumHeight` — well under the M3/Android 48dp minimum; `minimumHeight/minimumWidth = dp(48)` is a two-line fix with real mis-tap impact. |
| 61 | Move "Clear search" `contentDescription` onto the clickable `FrameLayout`, not the inner TextView | ux | APPROVE | Confirmed `Components.kt:328-337`: the listener is on the outer FrameLayout but the label is on the child TextView, so TalkBack sees an unlabeled "double-tap to activate" control. |
| 62 | `ModeBanner`: add `contentDescription` + click action for TalkBack; align text with the actual tap target | ux | APPROVE | Confirmed: clickable chip (`MainScreen.kt:83-86`, `Components.kt:632-639`) has no label/action role, and its small chip height is also a sub-48dp target; text says "tap ⚙" while the banner itself is the shortcut. |
| 63 | "Test connection": disable button + "Testing…" label while pinging; wrap `pingAll()` in `runCatching` | ux | APPROVE | Confirmed `SettingsActivity.kt:119-141`: button stays enabled (each tap spawns another `Thread` and clears results) and an unexpected throw leaves all chips stuck at "…" forever. |
| 64 | Opt into predictive back (`android:enableOnBackInvokedCallback="true"`) for targetSdk 35 | ux | APPROVE | Manifest-only change (attribute on `<application>`); default dispatch still finishes the activity, so the custom ✕/slide-down path is untouched. |
| 65 | Add `pressable()` to request cards in `RequestsActivity` | ux | APPROVE | Confirmed `RequestsActivity.kt:143-147`: card has a click listener + contentDescription but no ripple/press-scale, unlike `PosterCard`/banner — dead moment before the transition (Nielsen #3). One line. |
| 66 | Requests screen: show "couldn't reach services — showing status from HH:mm" + Retry when live refresh fails | ux | APPROVE | Confirmed `RequestsActivity.kt:61-66`: `runCatching { refreshStatuses(...) }` discards failure and the list keeps showing stale pills with no freshness indication (MainScreen and magnets both have error+Retry). |
| 67 | Only reset scroll-to-top when the page change comes from the bottom nav, not on swipe-back | ux | APPROVE | Confirmed `MainScreen.kt:104`: `pager.onPageSelected` fires for both nav taps and swipes and always `smoothScrollTo(0,0)`, contradicting `SwipePager`'s preserved-scroll promise — user loses their place on every tab glance (Nielsen #6). Pass a flag from the nav callback. |
| 68 | Add `contentDescription` to bottom-nav cells; mark children `IMPORTANT_FOR_ACCESSIBILITY_NO` | ux | APPROVE | Confirmed `Components.kt:255-263`: clickable cell has only an emoji TextView + label TextView — TalkBack merging on clickable containers is inconsistent and the emoji may be announced raw. Two lines. |
| 69 | Hoist the `MainScreen` results cache to a process-level holder so rotation doesn't flash skeletons | ux | APPROVE | Confirmed: manifest has no `configChanges`, so rotation recreates `MainActivity` and the instance-field `cache` (`MainScreen.kt:32-33`) is lost → skeleton flash + refetch of seconds-old data. Companion-object hoist is 3 lines. |

## 2. My APPROVE/MODIFY list

| # | change (short) | my vote |
|---|----------------|---------|
| 1 | Fatal `rootFolder`/`qualityProfileId` in *arr write path | APPROVE |
| 3 | `ArrClient` base class dedupe (after #34) | MODIFY |
| 4 | Named `SeriesState`/`MovieState` instead of `Triple` | APPROVE |
| 5 | Typed `ExternalRef` (wire-compatible, backward-compatible parse) | MODIFY |
| 6 | URL validation only on URL fields in Settings | APPROVE |
| 7 | Single toggleable error view in MainScreen (no stacking/stale overlay) | APPROVE |
| 10 | Poller: in-flight guard + pause in `onStop` + cancel on destroy (no coroutines) | MODIFY |
| 11 | Delete six dead symbols | APPROVE |
| 13 | `@Volatile audiobookIndexerIds` | APPROVE |
| 14 | Reset `wasWiped` after successful save | APPROVE |
| 20 | Persist media snapshot with each request (no vanishing requests after cold start) | APPROVE |
| 22 | Refresh header/banner/hint on `onResume` | APPROVE |
| 27 | TSV→JSON persistence (with legacy-TSV migration read) | MODIFY |
| 28 | `StatusPill` single-condition label/color | MODIFY |
| 29 | Shared `reduceMotion()` helper | APPROVE |
| 34 | Fix `Resp.header()` null-key NPE (un-breaks Copy magnet) | APPROVE |
| 35 | Move config disk/keystore/crypto off main thread (minimal async) | MODIFY |
| 36 | Cap image download size + `inSampleSize` decode (OOM) | APPROVE |
| 37 | Fix network security config so LAN HTTP live mode works | APPROVE |
| 38 | Manual redirect following without leaking API keys | APPROVE |
| 39 | Minimal `downloadUrl` validation (scheme + private/metadata IPs) | MODIFY |
| 40 | Cap Jellyfin pagination loop | APPROVE |
| 42 | Dismiss dialogs in `onDestroy` | APPROVE |
| 44 | Scrim behind GradientPoster titles (WCAG 4.5:1) | APPROVE |
| 45 | Darken accent gradient endpoints (button label contrast, both themes) | APPROVE |
| 46 | `accentText` token for accent-as-text on glass | APPROVE |
| 47 | Status colors that pass on tinted chips + solid badge | APPROVE |
| 48 | Fix dead ternary in `glassLayered`, token-derived border | MODIFY |
| 53 | Unify the three button atoms (padding + TITLE) | MODIFY |
| 54 | `RadiusTokens.thumb` + skeleton radius match | APPROVE |
| 56 | Fix PosterCard one-shot click guard | APPROVE |
| 57 | No full-grid fade/stagger on data ticks and search keystrokes | APPROVE |
| 58 | Truthful "Saved — live/placeholder" feedback in Settings | APPROVE |
| 59 | `adjustResize` + IME dismissal on tab switch/card tap | APPROVE |
| 60 | 48dp touch target on the live-mode Switch | APPROVE |
| 61 | Clear-button label on the clickable node | APPROVE |
| 62 | ModeBanner contentDescription/action + target size | APPROVE |
| 63 | Test-connection busy/disabled state + safe ping | APPROVE |
| 64 | Predictive back opt-in (manifest attr) | APPROVE |
| 65 | `pressable()` on request cards | APPROVE |
| 66 | Stale-data warning + Retry on Requests screen | APPROVE |
| 67 | Scroll-to-top only on nav-initiated tab switches | APPROVE |
| 68 | Bottom-nav cell contentDescriptions | APPROVE |
| 69 | Process-level results cache (no skeleton flash on rotation) | APPROVE |

Tally: 35 APPROVE, 9 MODIFY, 25 REJECT.

## 3. Notes

- **Highest user-impact items for round 2:** #56 (dead poster after first tap), #34 (Copy magnet always fails — one-line NPE), #7 (grid permanently hidden after retry cycle), #57 (grid flicker every 5 s while typing/polling), #44/#45/#46/#47 (WCAG failures on primary content and CTA — all verified numerically with the repo's own `contrastRatio`), #37 (LAN live mode broken by a config that can never match), #6 (bogus red URL error under every API-key field).
- **Verified claims:** the theming report's contrast numbers reproduce exactly (white on brightest gradient endpoint 2.04–3.48:1 for 9/12 catalog pairs; accent gradient 2.64–4.47:1; `accentSolid`-as-text 3.28:1 dark). The UX report's click-guard, error-stacking, fade-on-tick, scroll-reset, and Switch-target claims all check out against the code.
- **Constraints respected:** `build.gradle` has no coroutines dependency, so #10 is MODIFY'd to a `Handler`+`AtomicBoolean` implementation; #27 requires legacy TSV migration; #5 requires wire-compatible `ExternalRef`; big refactors (#2, #8, #18, #19, #21, #24, #43, #15) rejected as churn with no user-visible win.
- **Accessibility sweep approved as a bundle** (#60–#62, #68, #61): all are 1–4 line changes fixing real TalkBack/touch-target barriers; #29 keeps reduce-motion honored consistently.
