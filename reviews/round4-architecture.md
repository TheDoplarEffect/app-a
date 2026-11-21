# Round 4 — Architecture Review (Clean Architecture / GoF / Android App Architecture / SOLID)

**Scope:** app code only (`app/src/main/java/com/example/appa/`, `app/src/main/AndroidManifest.xml`, `app/build.gradle`). Nothing under `reviews/` was read.
**Code reviewed:** `TheDoplarEffect/app-a` @ master HEAD `33ef6e266dbc9481c4cda103db4106feac2b7636` ("v4.4: fix smart-cast on mutable redirect body (copy to local)"). The local checkout `/home/dev/app-a` did not exist, so master HEAD was fetched and reviewed read-only (working copy at `/home/dev/app-a-fresh`; nothing under `app/` was modified).
**Lens:** Clean Architecture dependency rule (Martin), GoF patterns, Android "App architecture" guide (UI/data separation, SSOT, repository, lifecycle-aware state), SOLID.

---

## Ranked findings

### 1. HIGH — "Test connection" crashes with `IllegalThreadStateException` (double `Thread.start()`)
- **File:** `app/src/main/java/com/example/appa/SettingsActivity.kt:158-171`
- **What:** `WorkerScope.spawn { … }.start()` — but `WorkerScope.spawn` (`AppState.kt:146-149`) already calls `th.start()` before returning the thread. Calling `Thread.start()` on an already-started thread throws `IllegalThreadStateException` on the main thread.
- **Why:** every tap on "Test connection" in live mode crashes the app. This is a thread-lifecycle ownership error: the view both creates and "starts" a worker through a helper that already starts it.
- **Fix:** delete the trailing `.start()` (line 171): `WorkerScope.spawn { … }`. Add a unit test or `require(!th.isAlive)`-style guard inside `spawn` if callers keep chaining.
- **Reference:** GoF — encapsulate object creation/lifecycle in one place (Factory method owns `start()`); SOLID-S (the button handler should not manage thread lifecycle).

### 2. HIGH — SSOT violation: in live mode the simulated ticker still advances *live* requests → statuses flap
- **File:** `app/src/main/java/com/example/appa/AppState.kt:102` (live branch of `StatusPoller`), with `RequestStoreCore.tick()` at `Catalog.kt:192-207` and `LiveRepository.refreshStatuses` at `LiveRepository.kt:142-177`
- **What:** in live mode the poller calls `AppState.store.tick()` unconditionally, which ages **every** non-final request (`StatusRules.next`), including requests that carry an external `"radarr:<id>"`/`"sonarr:<id>"` ref. `refreshStatuses` only *skips* `external.isBlank()` requests when computing live state — it does not stop the ticker from touching them.
- **Why:** a Radarr request that is PENDING flips to PROCESSING after 4 s via `tick()`, then the next 5 s `refreshStatuses` computes PENDING again and overwrites it (`store.updateStatus`, `LiveRepository.kt:175`). The persisted SSOT oscillates and the UI re-renders back and forth. Two writers own `RequestStatus`: the simulated ticker and the live sync. Per the Android architecture guide the request store is the single source of truth for status, and each piece of state must have exactly one writer/owner.
- **Fix:** give `tick()` a predicate and call it in live mode only for local-only requests: `store.tick { it.external.isBlank() }` (placeholder mode keeps ticking everything). Then in live mode the *arr/Jellyfin sync is the sole status owner for external requests.
- **Reference:** Android "App architecture" guide — SSOT / single-writer principle; Martin, *Clean Architecture* — state transitions belong to one use-case/entity layer, not to a UI-adjacent poller.

### 3. HIGH — No repository abstraction: the UI layer branches between `Catalog` and `LiveRepository` (dependency-rule violation, logic in views)
- **File:** `MainScreen.kt:261-266` (`if (!live) { Catalog.ofType/search } else { repo… }`), `DetailActivity.kt:271` (`Catalog.magnetsByMediaId` in placeholder mode), `AppState.kt:72-77` (`mediaById` = repo → Catalog → request-snapshot fallback chain)
- **What:** screens decide *which data source* to use by reading `AppState.liveMode` and calling `Catalog` (placeholder) or `AppState.repo` (live) directly. Media lookup is a three-way fallback chain with no owner.
- **Why:** the dependency rule says inner layers must not know outer layers and outer layers should depend on abstractions, not concretes. Here the UI layer depends on two concrete data sources and contains the switching logic; adding a source (or a cache) means editing every screen. "Logic in views" — the Android guide puts source selection in the data layer's repository.
- **Fix:** define `interface MediaRepository { browse/search/details/magnetsFor/request/refreshStatuses/mediaById }`, implement it with `LiveRepository` and a `PlaceholderRepository` wrapping `Catalog`, and expose exactly one instance from a factory chosen at composition root. `AppState.mediaById`'s fallback chain moves into the repository. Screens then depend only on the interface.
- **Reference:** Martin, *Clean Architecture*, ch. 22 (dependency rule — dependencies point inward; depend on abstractions); Android guide — "Data layer" + repository pattern as SSOT; SOLID-D (DIP).

### 4. HIGH — Request creation is a TOCTOU that can double-add in *arr; the safe primitive (`setExternal`) is dead code; transaction logic lives in the view
- **File:** `DetailActivity.kt:220-244` (`doRequest`), `Catalog.kt:166` (`setExternal`, never called anywhere), `LiveRepository.kt:129-140` (`request()` = the *arr POST)
- **What:** in live mode the view calls `repo.request(media)` (network POST to Radarr/Sonarr) **before** `store.create(...)`. The "check FIRST" guard at line 224 is not atomic with the create: if `create` returns null (request already exists) the *arr entry has already been added — exactly the double-add the comment claims to prevent. The store already exposes the correct idempotent primitive (`create` returning null, plus `setExternal`) but the flow doesn't use it.
- **Why:** business-transaction logic (idempotent local create → external add → attach ref) sits in an Activity, and the ordering makes the local SSOT unable to protect the external system. UI/data separation violation.
- **Fix:** in live mode: `val created = store.create(media.id, snapshot = …)`; if null → "Already requested", abort. Otherwise `val external = repo.request(media)`; then `store.setExternal(created.id, external)`. Move this sequence into a `RequestRepository.request(media): Result<Request>` use case so the view only fires it and renders the result.
- **Reference:** Android guide — UI layer must not contain business logic; Martin — use-case/interactor owns the transaction; Ousterhout — expose deep, idempotent primitives (`create`/`setExternal`) and compose them one layer up.

### 5. MEDIUM — `AppState` is a global service-locator singleton with a single-slot callback: hidden dependencies, unclear state ownership
- **File:** `app/src/main/java/com/example/appa/AppState.kt:10-84`; single-slot callback set at `MainScreen.kt:63`, cleared at `MainScreen.kt:164`
- **What:** `object AppState` holds `lateinit var store`, the atomic `(config, repo)` pair, `configWasWiped`, and `var onConfigChanged: (() -> Unit)?` — one subscriber slot that `MainScreen.attach()` overwrites and `detach()` nulls. Every screen statically reaches in (`AppState.store`, `AppState.repo`, `AppState.liveMode`, `AppState.requestFor`).
- **Why:** static access = hidden dependencies (SOLID-D violated), impossible to substitute in tests, and the single `var` callback means any second observer silently replaces the first (Observer pattern done wrong — GoF Observer supports N subscribers). State ownership (who may write config/repo) is implicit, not enforced.
- **Fix:** make the app state a class injected into each screen (constructor param); expose config as `StateFlow<AppConfig>` and requests as observable state from the data layer; replace the callback with `MutableSharedFlow`/`StateFlow` (or a listener list). `lateinit var store` becomes an initialized-in-`init` or injected dependency.
- **Reference:** GoF — Service Locator anti-pattern vs. Dependency Injection; GoF Observer (multi-subscriber); Android guide — unidirectional data flow + ViewModel/UI-state holder; SOLID-D.

### 6. MEDIUM — God file / god class: `Components.kt` (660 lines, ~20 unrelated atoms) and `MainScreen` (view + cache + loader + poller lifecycle + navigation)
- **File:** `Components.kt:1-660`; `MainScreen.kt:24-379` (cache at `:56-57`, `:370-379`; polling at `:144-166`; loading at `:251-330`; rendering at `:336-368`)
- **What:** one file mixes top bar, buttons, nav bar, search field, chips, pills, glass cards, posters, skeletons, magnet rows, form fields and date formatting. `MainScreen` mixes UI construction, a process-wide result cache with TTL/LRU eviction (`evictCache`, `:226-233`), network-load orchestration with a job counter, render-signature diffing, keyboard handling and the poller lifecycle.
- **Why:** SOLID-S violated at file and class level; changes to any one concern touch the same 660/379-line unit, and the class is untestable as a whole. The file's own doc comment promises atomic design, but atomic design means one atom per unit, not one file.
- **Fix:** split `Components.kt` into per-component files/packages (`topbar/`, `buttons/`, `poster/`, `form/`…); extract `BrowseCache` (TTL/LRU), `GridLoader` (job counter + stale-while-revalidate) and keep `MainScreen` as pure view wiring + a UI-state holder.
- **Reference:** SOLID-S (SRP); Martin — package-by-feature/layer, small focused classes; Android guide — UI layer = thin, logic in ViewModel/data layer.

### 7. MEDIUM — Three competing owners for "current media / browse results" (SSOT violation for cached data)
- **File:** `MainScreen.kt:376-377` (companion `cache`/`signatures` — static mutable maps), `LiveRepository.kt:183-186` (`mediaCache` ConcurrentHashMap + `remember()`), `Catalog.kt:287-289` (`byId`/`magnetsByMediaId` lazy maps)
- **What:** browse results live in a process-level map inside `MainScreen`'s companion (cleared only on config change, `MainScreen.kt:63-67`); media-by-id lives in `LiveRepository.mediaCache` (plus a `Catalog` fallback inside `mediaById`); placeholder data lives in `Catalog`. No single owner, no single invalidation rule.
- **Why:** caching is a data-layer concern; a UI-layer static cache is invisible to the data layer and survives configuration changes unless someone remembers to clear it. SSOT requires one owner per piece of state.
- **Fix:** move the TTL/LRU result cache into the (single) `MediaRepository` from finding #3; the UI keeps only the render signature (a pure function of the state it displays).
- **Reference:** Android guide — SSOT, repository owns caching; Martin — dependency rule (caching policy belongs to the data layer, not the view).

### 8. MEDIUM — `refreshStatuses` mutates the store passed as a parameter, and a stale repo can be captured across a config swap
- **File:** `LiveRepository.kt:142-177` (`refreshStatuses(store)` calls `store.updateStatus` at `:175`); `MainScreen.kt:281` (`val repo = AppState.repo` captured in the worker); mutable `@Volatile` caches at `LiveRepository.kt:47-49`, `183`
- **What:** a method named "refresh" has a hidden write side effect on the caller's SSOT — callers must know it mutates. Separately, `MainScreen.loadItems` captures `AppState.repo` when spawning the worker; if `saveConfig` swaps `state` mid-flight (`AppState.kt:54-68`), results from the old repo (old clients/URLs) are rendered and `remember()`ed into the old cache.
- **Why:** single-writer/ownership of the store is not explicit — the repository writes the very store it receives, so the "who writes status" question has no crisp answer (compounds finding #2). The captured-repo race means data from a previous configuration can be presented as current.
- **Fix:** have `refreshStatuses` return the changed requests and let a `RequestRepository.sync()` apply them (single writer), or rename to make the write explicit (`syncStatusesInto(store)`). Tag async results with the config generation (e.g. compare `AppState.config` identity inside the posted callback) and drop stale ones.
- **Reference:** Android guide — SSOT with a single writer; Martin — use cases own state changes, not "refresh" reads.

### 9. MEDIUM — `WorkerScope` is a global thread registry; any screen's `onDestroy` interrupts *all* workers process-wide
- **File:** `AppState.kt:141-160` (`cancelAll` at `:152`); called from `DetailActivity.kt:103` and `MainScreen.kt:163`; never called from `RequestsActivity`/`SettingsActivity`
- **What:** `DetailActivity.onDestroy` calls `WorkerScope.cancelAll()`, interrupting in-flight loads/polls belonging to `MainActivity` still alive underneath; `MainScreen.detach` does the same. Ownership of worker threads is global but teardown is per-screen and inconsistent (Requests/Settings never cancel).
- **Why:** cross-component cancellation is a lifecycle-ownership bug: a screen must only cancel its own work. The Android guide's answer is lifecycle-scoped concurrency (`lifecycleScope`/`viewModelScope` or per-owner scopes), not a process-wide interrupt-all.
- **Fix:** give each owner (poller, screen, loader) its own worker set/scope and cancel only that; e.g. `class WorkerScope { … }` instantiated per `MainScreen`/`DetailActivity`/poller instead of `object`.
- **Reference:** Android guide — lifecycle-aware components; SOLID-DIP (depend on an owned abstraction, not a global); GoF — avoid global state in Mediator-style coordination.

### 10. MEDIUM — `ImageLoader`'s bounded queue has no rejection policy → `RejectedExecutionException` on the UI thread during fast scrolls
- **File:** `ImageLoader.kt:21-24` (`ThreadPoolExecutor(2, 2, 30s, LinkedBlockingQueue(24))`, default `AbortPolicy`), `:35` (`executor.execute` from the UI thread via `PosterCard`, `Components.kt:540-543`)
- **What:** once 24 poster loads are queued and both threads are busy, `executor.execute` throws `RejectedExecutionException` on the main thread → crash. A 3-column grid of live results (`GRID_COLUMNS = 3`, `MainScreen.kt:373`) scrolling fast easily exceeds 24 queued tasks. The pool's non-daemon threads are also never shut down.
- **Why:** the queue bound is a backpressure guard, but without a rejection policy the bound itself becomes a crash vector; resource ownership (who may enqueue, what happens when full) is undefined.
- **Fix:** install `ThreadPoolExecutor.CallerRunsPolicy()` (or `DiscardOldestPolicy`) and a daemon thread factory, or replace with Coil/Glide which handle this. At minimum wrap `execute` in `runCatching` and skip the load.
- **Reference:** Android guide — off-main-thread loading with backpressure; SOLID-S (the loader should own its queue policy in one place).

### 11. MEDIUM — Duplicate construction of `LiveRepository`, bypassing the one factory
- **File:** `SettingsActivity.kt:159` (`LiveRepository(cfg).pingAll()`) vs. `RepositoryFactory.create` at `LiveRepository.kt:204-208` used by `AppState.kt:46,60`
- **What:** the settings "Test connection" path constructs a second `LiveRepository` directly with its own lazy clients and caches, instead of going through `RepositoryFactory` (GoF Abstract Factory) — two construction rules for the same type.
- **Why:** factories exist to centralize construction decisions (e.g. "null when not live-ready"); bypassing it lets the two paths drift (here it also runs on the global `WorkerScope`, see finding #9). DRY violation.
- **Fix:** `RepositoryFactory.create(cfg)?.pingAll()` or introduce a small `ConnectionTester(http)` that takes the transport; keep exactly one way to build a `LiveRepository`.
- **Reference:** GoF — Abstract Factory / Factory Method (single construction rule); DRY.

### 12. LOW — Status→presentation mapping duplicated in two layers
- **File:** `DetailActivity.kt:195-199` (inline `when (request.status)` → button text) vs. `Components.kt:389-403` (`statusColorOf`/`statusLabelOf`, used by `StatusPill`/`PosterCard` and `RequestsActivity.kt:130`)
- **What:** the same domain concept (`RequestStatus` → label/color) is mapped twice with different strings ("⏳ Requested — pending" vs. "⏳ Pending") and can drift.
- **Why:** presentation of state should have one definition (SSOT applied to UI state mapping); duplication invites divergence.
- **Fix:** one `StatusPresentation.label(status, available)` / `color(...)` helper in the UI layer, used by Detail, PosterCard and StatusPill.
- **Reference:** DRY; Android guide — single source of truth for UI state rendering.

### 13. LOW — `AppState.init` performs the first SharedPreferences disk read on the main thread, contradicting its own "pre-warm off the main thread" comment
- **File:** `AppState.kt:30-52` (`getSharedPreferences` at `:33`; `RequestStoreCore` init runs `reload()` → `load()` → `prefs.getString` on main, `Catalog.kt:150`, `:209-215`); the worker "pre-warm" at `AppState.kt:41-43` runs too late
- **What:** the first access to the `appa` prefs file (disk read) happens synchronously inside `init()` on the main thread; the `WorkerScope.spawn` pre-warm executes afterwards. `MainActivity.java`'s StrictMode `detectAll()` would flag it.
- **Why:** initialization ownership is muddled — the comment promises off-main work but the store constructor does the read on main; ANR risk on slow storage.
- **Fix:** construct `RequestStoreCore` with a deferred/background load (e.g. load inside the worker and post `store` initialization), or at minimum move `getSharedPreferences` + first read into the spawned worker.
- **Reference:** Android guide — no disk/network I/O on the main thread; SOLID-S (init should have one owner for "when" things load).

### 14. LOW — `RequestStoreCore` owns a never-shutdown non-daemon executor
- **File:** `Catalog.kt:249-265` (`Executors.newSingleThreadExecutor()` at `:249`, used at `:265`)
- **What:** each store instance creates a single-thread executor whose thread is never shut down; there is no `close()`/shutdown hook, and persistence failures are silently swallowed by the executor.
- **Why:** resource lifecycle should be explicit (who starts/stops the writer thread); a leaked executor per store instance is a (small) leak and unobservable failures make the SSOT silently stale on disk.
- **Fix:** daemon thread factory + a `shutdown()` called from the composition root, and surface `save` failures (at least log) so the SSOT's persistence is observable.
- **Reference:** SOLID-S / resource ownership (RAII-style); Martin — infrastructure details at the edges with explicit lifecycle.

---

## Top 5 changes

1. **Fix the `WorkerScope.spawn { … }.start()` crash** in `SettingsActivity.kt:171` (delete the second `start()`) — "Test connection" currently throws `IllegalThreadStateException` on every tap.
2. **Make status single-writer in live mode:** `StatusPoller` must tick only local-only requests (`external.isBlank()`) when live, so `refreshStatuses` is the sole owner of external request statuses — no more flapping persisted state (`AppState.kt:102`).
3. **Introduce one `MediaRepository`/`RequestRepository` abstraction** with live + placeholder implementations behind `RepositoryFactory`, and remove the `Catalog`-vs-`repo` branching from `MainScreen`/`DetailActivity`/`AppState.mediaById` — restores the dependency rule and SSOT.
4. **Fix the request-creation ordering** in `DetailActivity.doRequest`: idempotent `store.create` first, then `repo.request`, then the (currently dead) `store.setExternal` — moved into a use case — so a duplicate can never double-add in *arr.
5. **Replace the `AppState` service locator with injected, observable state:** constructor-injected app state, `StateFlow` for config/requests, multi-subscriber events instead of the single `onConfigChanged` slot, per-owner worker scopes instead of global `WorkerScope.cancelAll()`, and the browse cache moved from `MainScreen`'s companion into the repository.
