# Round 5 — Code Quality Review (fresh, unpolluted by rounds 1–4)

- **Repo:** https://github.com/TheDoplarEffect/app-a @ master HEAD `cd6abab` ("v4.5: round-4 review reports + panel consensus")
- **Scope:** `app/src/main/java/com/example/appa/` (22 files, ~4,045 LOC) + tests + manifest/build. `reviews/` intentionally NOT read.
- **Lens:** naming, null-safety, error handling, thread-safety, resource leaks, lifecycle bugs. Principles: SOLID, DRY, KISS, fail-fast with `runCatching` at boundaries, no silent catch-all swallowing.

## Severity legend
**HIGH** = crash / data loss / broken core flow. **MEDIUM** = lifecycle/threading/error-handling defects that bite in real use. **LOW** = hygiene, latent risk.

---

## HIGH

### H1. Startup `StackOverflowError`: mutual recursion between `BottomNavBar.select` and `SwipePager.goToPage`
- **Files:** `Components.kt:286,290,309` · `SwipePager.kt:42` · `MainScreen.kt:106,125`
- **What:** `BottomNavBar.init` does `root.post { select(initial, animate = false); initialized = true }` (Components.kt:286). The re-entrancy guard is `if (i == selected && initialized) return` (Components.kt:290), but `initialized` is only set **after** `select` returns. `select` unconditionally calls `onSelect(i)` (Components.kt:309) → MainScreen's nav callback calls `pager.goToPage(i)` (MainScreen.kt:125) → `goToPage` unconditionally fires `onPageSelected?.invoke(target)` (SwipePager.kt:42) → MainScreen's page callback calls `nav.select(i)` (MainScreen.kt:106) → guard still fails (`initialized == false`) → infinite recursion. Verified by simulating the exact callback graph: stack overflow before the first frame renders.
- **Why it matters:** the app crashes on first layout of the main screen — a total functional break, not a cosmetic bug.
- **Fix:** set the flag before recursing: `root.post { initialized = true; select(initial, animate = false) }`; additionally make the cycle structurally safe — in `MainScreen.onPageSelected` only call `nav.select(i)` when `i != nav.selected`, and/or in `goToPage` skip `onPageSelected` when `target == current && scrollX == target * width`.

### H2. Global `WorkerScope.cancelAll()` from any screen's `onDestroy` kills unrelated in-flight work
- **Files:** `AppState.kt:152-157` · `DetailActivity.kt:103` · `MainScreen.kt:163`
- **What:** `WorkerScope` is a single process-global registry. `DetailActivity.onDestroy` calls `WorkerScope.cancelAll()` (DetailActivity.kt:103), which interrupts **every** worker thread in the app — including MainScreen's in-flight `loadItems` search and the shared `StatusPoller` refresh. Closing a detail page can therefore fail/silently drop the main grid's pending load (the interrupted HTTP call surfaces as a spurious "Couldn't load titles" error).
- **Why it matters:** cross-activity interference; one screen's teardown corrupts another screen's state. Violates encapsulation (SOLID) — teardown should be scoped to the owner.
- **Fix:** make `spawn` return a handle (e.g. `Cancellable`) and have each screen/activity cancel only its own workers; reserve `cancelAll()` for process teardown. At minimum, don't call `cancelAll()` from `DetailActivity.onDestroy`.

### H3. `AppState.saveConfig` uses a bare, unguarded `Thread { }` — silent hang + race on save
- **Files:** `AppState.kt:54-73` (thread at :58)
- **What:** the save runs in `Thread { SecureStore.saveConfig(...); ... }.start()` with **no `runCatching`**. If the Keystore op throws (key invalidated after backup restore — exactly the case `SecureStore.loadConfig` handles), the thread dies silently: `onDone` never fires, Settings is stuck on "Saving…", and the in-memory/disk config diverge. The thread is also not registered in `WorkerScope` (uncancellable on teardown), and two overlapping saves can interleave disk writes vs. the main-thread `state` post (last-writer-wins inconsistency).
- **Why it matters:** fail-fast-with-runCatching at boundaries is the stated principle; this is the one place a boundary exception is swallowed by thread death, and it's the write path for user secrets.
- **Fix:** route through `WorkerScope.spawn`, wrap the body in `runCatching`, post both success and failure to the main thread, and add an `onError` callback that re-enables the save button and toasts the failure. Serialize saves (a single-thread executor or an atomic "save in progress" flag).

---

## MEDIUM

### M1. `AppState.init` performs the SharedPreferences disk read on the **main thread**, making the "pre-warm" comment a lie
- **Files:** `AppState.kt:30-52` (store construction at :34, worker pre-warm at :40-41) · `Catalog.kt:158-165` (`init { reload() }` → `load()`)
- **What:** `RequestStoreCore(load = { prefs.getString("requests", null) }, ...)` runs `reload()` in its `init`, i.e. synchronously on the main thread during `AppState.init`. The worker-side `prefs.getString("requests", null)` "warm page cache" (AppState.kt:41) runs *after* the main-thread read already happened. With `StrictMode.detectAll()` enabled in debug (MainActivity.java:15-18) this is a flagged disk-read violation and a first-launch ANR risk.
- **Fix:** construct the store inside the worker and publish it via a `@Volatile` holder (with a main-thread-safe empty state until ready), or defer `reload()` to the first background access.

### M2. Rotation clobbers the new screen's `onConfigChanged` callback
- **Files:** `MainScreen.kt:63` (set) · `MainScreen.kt:164` (`AppState.onConfigChanged = null` in `detach`) · `AppState.kt:22`
- **What:** during rotation, the old `MainActivity.onDestroy` → `screen.detach()` runs **after** the new activity's `attach()` has registered its callback, so the old screen nulls the *new* screen's listener. After that, saving config in Settings no longer refreshes the main UI (until re-attach).
- **Fix:** compare-and-set: `if (AppState.onConfigChanged === mine) AppState.onConfigChanged = null`, or replace the single slot with a listener set (`MutableList<() -> Unit>`) that add/remove on attach/detach.

### M3. `StatusPoller.cancel()` doc/impl mismatch + unguarded poller callback touches views
- **Files:** `AppState.kt:84` (doc: "[cancel] also interrupts worker threads") vs `AppState.kt:132` (`cancel() = stop()` — does NOT interrupt) · `RequestsActivity.kt:41-45,78-81`
- **What:** `cancel()` only removes handler callbacks; the in-flight refresh worker keeps running. `RequestsActivity.onDestroy` calls only `poller.cancel()`, so its worker isn't interrupted, and the poller lambda writes `staleLabel.text` (RequestsActivity.kt:42-43) *before* `render()`'s `isFinishing/isDestroyed` guard (RequestsActivity.kt:90).
- **Fix:** track the current worker `Thread` in the poller and interrupt it in `cancel()`; guard the whole `onTick` lambda with a lifecycle check.

### M4. Silent catch-all swallowing at boundaries
- **Files:** `Clients.kt:75` (`catch (e: Exception) { media }` in `TmdbClient.details`) · `SettingsActivity.kt:159` (`runCatching { ... pingAll() }.getOrElse { emptyMap() }`) · `Config.kt:73` (`fromJson(...).getOrDefault(AppConfig())`)
- **What:** details-enrichment failures vanish without even a log line; a total `pingAll` failure is indistinguishable from "all services down"; a decryptable-but-malformed stored config silently becomes an empty config (no `wasWiped` flag, unlike the decrypt-failure path in `SecureStore.kt:65-70`).
- **Fix:** keep graceful degradation but make it observable — `Log.w(...)` + a returned flag, or propagate a `Result` to the UI layer. For `Config.fromJson`, distinguish "absent" from "malformed" and set a wiped/corrupt flag like `SecureStore` does.

### M5. `RequestStoreCore.persistIo`: non-daemon executor never shut down; `execute` can throw into callers
- **Files:** `Catalog.kt:250` (`Executors.newSingleThreadExecutor()`), `Catalog.kt:266` (`persistIo.execute { save(text) }`)
- **What:** the default thread factory creates a **non-daemon** core thread that never times out, and there is no `shutdown()` — in JVM unit tests the executor thread lingers; in the app it lives as long as the process (acceptable) but is unmanaged. `persist()` is called while holding `lock` from main-thread callers, so a `RejectedExecutionException` would crash the caller.
- **Fix:** daemon thread factory + `allowCoreThreadTimeOut(true)`, wrap `execute` in `runCatching` (log on failure), and add an explicit `shutdown()` for tests.

### M6. `AuroraBackground` leaks its offscreen bitmap on detach
- **Files:** `AuroraBackground.kt:67-70` (`onDetachedFromWindow` cancels the animator only) vs `:40` (recycle only in `onSizeChanged`)
- **What:** every activity destroy leaves an ARGB_8888 bitmap (w/8 × h/8) allocated until GC — native memory, non-deterministic reclamation.
- **Fix:** in `onDetachedFromWindow`, `bmp?.recycle(); bmp = null; blobCanvas = null` (guard `isRecycled`), and recreate in `onSizeChanged`/`onAttachedToWindow` as needed.

### M7. Live status polling hammers full library scans every 5 s
- **Files:** `LiveRepository.kt:142-145` (`refreshStatuses` calls `jellyfin.movieTmdbIds()` / `seriesTmdbIds()` — paginated full-library scans up to 5,000 items each, `Clients.kt:330-345`) plus both *arr full states + queues, on every `TICK_LIVE_MS = 5_000` tick (`AppState.kt:137`)
- **What:** the in-flight guard (`AppState.kt:100-108`) prevents pile-up, but on a large library each tick is dozens of HTTP calls against LAN services; a slow Jellyfin makes statuses perpetually stale.
- **Fix:** split cadence — lightweight queue/state check every 5 s, full Jellyfin library scan every 60 s (or on demand); consider *arr/Jellyfin delta endpoints.

---

## LOW

### L1. `withPressScale` applies a scale change under reduced motion
- **File:** `Motion.kt:104` — `if (v.context.reduceMotion()) { v.scaleX = 0.97f ... } else v.animate()...`. The reduced-motion branch still mutates the transform (instant 0.97); everywhere else (`slideUpIn`, `fadeIn`, `popIn`, `staggerIn`) reduced motion means *no* animation. Likely inverted intent.
- **Fix:** `if (reduceMotion()) return@setOnTouchListener false`-style no-op for DOWN/UP.

### L2. DNS TOCTOU in the SSRF guard; poster URLs not validated
- **Files:** `Http.kt:53-64` (`isSafeExternalUrl` resolves via `InetAddress.getAllByName`, then `realHttp` re-resolves at connect time) · `ImageLoader.kt:28` (`load` fetches `posterUrl` from remote metadata with no `isSafeExternalUrl` check)
- **Why:** a rebinding DNS answer can pass the check and resolve to loopback at connect time. On-device impact is low (user's own device), but the guard gives false assurance. **Fix:** pin the resolved `InetAddress` and connect to it (custom `SocketFactory`), or at least re-resolve and re-check immediately before connect; run `isSafeExternalUrl` on `posterUrl` in `ImageLoader.load`.

### L3. Throwaway `AppConfig()` just to call `urlError`; `!!` on the fields map
- **Files:** `SettingsActivity.kt:63,122` (`AppConfig().urlError(...)`), `SettingsActivity.kt:108-117` (`fields["tmdbToken"]!!`)
- **Fix:** move `urlError`/`validUrl` to the companion object (`AppConfig.urlError(u)`); replace `!!` with a safe fold that reports the missing key.

### L4. Dialog lists grow unbounded
- **Files:** `DetailActivity.kt:111` (`dialogs.add(d)`), `RequestsActivity.kt:144` — dismissed dialogs are never removed from the list.
- **Fix:** `d.setOnDismissListener { dialogs.remove(d) }`.

### L5. Process-global unsynchronized caches in `MainScreen.Companion`
- **File:** `MainScreen.kt:376-377` — `cache` / `signatures` are plain `mutableMapOf` shared process-wide; safe only because every access happens to be on the main thread (`loadItems`, `evictCache`, `renderCurrent`, `onConfigChanged`). Fragile if any future call comes off-main.
- **Fix:** document the main-thread confinement and/or use `Collections.synchronizedMap`, or scope the cache per screen instance with a static holder.

### L6. Placeholder magnet generation runs on the main thread in `DetailActivity.onCreate`
- **File:** `DetailActivity.kt:271` — first access of `Catalog.magnetsByMediaId` lazily builds 54 items × ~5 magnets each with SHA-256 on the calling (main) thread.
- **Fix:** pre-warm in `AppState.init`'s worker (it already touches `Catalog.all`), or move to a worker.

### L7. `ImageLoader` executor is a non-daemon pool never shut down
- **File:** `ImageLoader.kt:19-23` — acceptable for a process singleton, but inconsistent with the rest; `DiscardOldestPolicy` silently drops poster requests under burst (fine, but note it).
- **Fix:** daemon thread factory + comment; optionally `allowCoreThreadTimeOut`.

### L8. Misc hygiene
- `Config.kt:38` — `validUrl` is case-sensitive on the scheme ("HTTP://…" rejected); lowercase before checking.
- `AppState.kt:144-150` — `WorkerScope.spawn`'s prune+add is not atomic (benign today); synchronize the whole body.
- `RequestsActivity.kt:41-45` — poller lambda mutates `staleLabel` before the lifecycle guard in `render()` (covered by M3).
- `DetailActivity.kt:66` — `runCatching { ... }.getOrDefault(media)` silently falls back on details failure (same class as M4; acceptable but log it).

---

## Positive notes (keep as-is)
- Fail-fast on the *arr write path: `ArrClient.post`/`rootFolder`/`qualityProfileId` throw `ApiException` instead of silently defaulting (Clients.kt:200-225) — exactly the Ousterhout principle applied.
- Redirect handling drops `Authorization`/`X-Api-Key` cross-host and re-validates every hop with `isSafeExternalUrl` (Http.kt:34-36, 105-115); 301/302/303 correctly rewrite to GET.
- Response bodies capped at 8 MB, images capped/downsampled, bounded redirect hops, bounded Jellyfin pagination (Http.kt:27-43, Clients.kt:347, ImageLoader.kt:57-58).
- `RequestStoreCore` is properly lock-guarded; corrupt persistence keeps existing items instead of wiping (Catalog.kt:186-191); legacy TSV read is tolerant per line.
- Poller has a correct in-flight `AtomicBoolean` guard and stale-while-revalidate cache with TTL/LRU eviction in MainScreen.
- Only validated `magnet:` links are ever copied to the clipboard (DetailActivity.kt:303-310).
