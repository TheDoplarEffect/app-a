# Round 3 — Architecture Review (LENS: architecture)

Scope: `/home/dev/app-a/app/src/main/java/com/example/appa/` (20 Kotlin/Java sources + 5 JVM tests), reviewed against **Clean Architecture (Robert C. Martin — dependency rule)**, **GoF Design Patterns**, the **Android "App architecture" guide (UI/domain/data separation, single source of truth)**, and **SOLID**.

What is already good (and should be preserved): the pure core (`Catalog.kt`, `Clients.kt`, `LiveRepository.kt`, `Http.kt`, `Config.kt`, `JsonX.kt`, `PagerMath.kt`) has **zero Android imports** and is genuinely unit-tested via injected `HttpTransport` and load/save lambdas — a real test seam. All architectural problems are concentrated in the **app/UI layer**, which reaches past the (undeclared) layer boundaries straight into concrete data sources and global state.

---

## Findings (ranked)

### 1. HIGH — No repository abstraction: views call concrete data sources and branch on `liveMode` themselves (dependency-rule violation)
- **Where:** `MainScreen.kt:243-244` (`if (!live) { Catalog.ofType(tab) / Catalog.search(q) }` inside the view), `DetailActivity.kt:248,264` (liveMode branch + `Catalog.magnetsByMediaId`), `AppState.kt:70-79` (`mediaById` 3-way fallback), `SettingsActivity.kt:150` (direct `LiveRepository(cfg)` construction).
- **What:** There is no `interface MediaRepository`. The UI layer (outer ring) source-depends directly on the concrete data implementations (`Catalog` placeholder data, `LiveRepository` live clients), and every screen re-implements the "live vs placeholder" decision. There is also no package/module boundary — everything is one flat package `com.example.appa`, so the dependency direction is enforced only by comment convention.
- **Why it matters:** Clean Architecture's dependency rule (Martin: *source-code dependencies must point only inward, toward higher-level policies*) is violated: the most volatile layer (Android Views/Activities) depends on the most concrete, network-coupled classes. The Android "App architecture" guide requires the UI layer to talk to a repository that is the single source of truth, not to data sources directly. Today a change in any client (e.g. TMDB response shape) can force edits in Activities.
- **Fix:** Define a domain `interface MediaRepository { fun browse(type); fun search(q); fun details(m); fun magnetsFor(m); fun request(m): String; fun refreshStatuses(store); fun getById(id): Media? }` in a pure-Kotlin `domain` package. Provide `PlaceholderRepository` (wraps `Catalog` + `RequestStoreCore.tick`) and make `LiveRepository` implement the interface in a `data` package. `AppState`/container holds exactly one `MediaRepository`; all `if (AppState.liveMode)` branches in views delete themselves. Split packages (`domain` / `data` / `ui`) so the compiler enforces the direction.
- **Reference:** Clean Architecture (Martin), dependency rule; Android developers "App architecture" guide — "UI layer / data layer separation", repository pattern.

### 2. HIGH — The "request media" use case lives in the Activity, with a wrong operation order (duplicate *arr add)
- **Where:** `DetailActivity.kt:218-243` (`doRequest`).
- **What:** In live mode the view spawns a thread that first calls `AppState.repo?.request(media)` — a **side-effecting write to Sonarr/Radarr** (`Clients.kt:258-271`) — and only afterwards calls `store.create(...)`, which returns `null` when a request already exists. The toast then says "Already requested" while the *arr add has already executed.
- **Why it matters:** Business rules (uniqueness of a request, ordering of "check → external add → persist") belong to an interactor/use case, not to a View (Clean Architecture: interactors own the application business rules; Android guide: domain layer). As written, the app can create an orphaned/duplicate entry in the user's *arr server that no request row tracks — a real correctness bug, not just style.
- **Fix:** Extract `class RequestMediaUseCase(private val store: RequestStoreCore, private val repo: MediaRepository)` returning a sealed `Result<Created|AlreadyRequested|Failed>`: check `store.byMediaId(media.id)` **first**, then `repo.request(media)`, then `store.create(media.id, external, snapshot)`. Unit-test it with the existing fake-transport pattern. The Activity only calls the use case and renders the result.
- **Reference:** Clean Architecture (Martin) — use cases/interactors; Android "App architecture" — domain layer owns business logic.

### 3. HIGH — `AppState` is a global service-locator singleton with a single callback slot; state ownership is unclear
- **Where:** `AppState.kt:10-79` (`object AppState`: owns `store`, atomic `(config, repo)` pair, `configWasWiped`, and the single `var onConfigChanged`); consumed globally by `MainScreen.kt:67,161`, `DetailActivity.kt`, `RequestsActivity.kt`, `SettingsActivity.kt`.
- **What:** One global object is simultaneously the DI container, the state holder, the persistence wiring, and the change-broadcast mechanism. `onConfigChanged` is a **single** `var` — `MainScreen.attach()` overwrites it and `detach()` nulls it, so only one screen can ever observe config changes; any second subscriber silently wins/loses. `StatusPoller` (`AppState.kt:84-135`) is hardwired to `AppState` globals too.
- **Why it matters:** GoF flags Singleton/Service Locator as an anti-pattern precisely because of hidden global coupling and untestable state; the Android guide recommends constructor-injected dependencies and a per-screen state holder. Concurrency is "handled" with ad-hoc `@Volatile` + `Handler.post` rather than a defined ownership model.
- **Fix:** Introduce an `AppContainer` (manual DI) created once in `Application.onCreate` and **passed by constructor** to `MainScreen`/Activities. Replace the single callback with a listener list (`addConfigListener/removeConfigListener`) or a small observable state holder scoped to a lifecycle.
- **Reference:** GoF Design Patterns — Singleton/Service Locator; Android "App architecture" guide — dependency injection, "state holders".

### 4. HIGH — Multiple competing sources of truth for media and for "available" status
- **Where:** `MainScreen.kt:344-350` (process-global `cache`/`signatures` in companion), `LiveRepository.kt:178-194` (`mediaCache` + `markAvailability` writing `available=true`), `Catalog.kt:266+` (`Catalog.byId`), request snapshots (`Catalog.kt:107-135`), request status in `RequestStoreCore` (`Catalog.kt:140+`); merged ad hoc in the view signature `MainScreen.kt:322` and `Components.kt:359-372` (`statusLabelOf`/`statusColorOf`).
- **What:** "Which media list is on screen" lives in 4 places; "is this title available" is derivable from Jellyfin cache (`LiveRepository.markAvailability`), from placeholder `i % 6 == 0`, and from request status — the UI recomputes the combination inline. `markAvailability` also only runs at browse time, so a title that *becomes* available in Jellyfin later is not re-marked until re-browse.
- **Why it matters:** The Android architecture guide's **single source of truth** principle exists exactly to prevent divergent copies of the same data (stale posters, wrong badges, double-fetch). Here the copies can and do diverge (see findings 6 and 7).
- **Fix:** One `MediaRepository` (finding 1) becomes the SSOT: `getById(id)` with one documented priority (live cache → catalog → request snapshot) and a single `availabilityOf(mediaId)` computed from the Jellyfin id-sets + request store in one place; `refreshStatuses` re-marks cached media when the Jellyfin sets change. Views render only what the SSOT returns.
- **Reference:** Android developers "App architecture" guide — "Single source of truth"; Clean Architecture — entities/SSOT at the center.

### 5. MEDIUM — `StatusPoller`'s in-flight guard does not cover the async refresh it claims to guard
- **Where:** `AppState.kt:90-113`: `busy.compareAndSet(false, true)` at line 94, worker thread spawned at 98-103, but `busy.set(false)` in `finally` at line 110 — i.e. released **immediately after starting the thread**, while `refreshStatuses` is still running.
- **What:** The class comment says the guard prevents "slow *arr refreshes piling up", but any refresh slower than `TICK_LIVE_MS` (5 s) overlaps the next tick, so two `refreshStatuses` run concurrently — duplicate Jellyfin/*arr traffic and potentially out-of-order `store.updateStatus` writes.
- **Why it matters:** State mutation (`updateStatus` → `persist`) then has multiple concurrent writers from the poller alone, violating the single-writer/SSOT discipline the guide prescribes for state holders.
- **Fix:** Move `busy.set(false)` into the `handler.post { ... onTick(...) }` callback (after the result is delivered), so the guard spans the whole network round-trip.
- **Reference:** Android "App architecture" guide — SSOT/single writer for mutable state.

### 6. MEDIUM — Main-screen cache key omits the mode: stale placeholder results served in live mode for up to 45 s after a config change
- **Where:** `MainScreen.kt:194` (`cacheKey(tab, q) = "$tab|$q"`), `MainScreen.kt:239-262` (`loadItems` serves `cache[key]` before fetching), `MainScreen.kt:67` (`onConfigChanged = { refreshChrome(); loadItems() }`), TTL `CACHE_TTL_MS = 45_000` at line 348.
- **What:** After the user enables live mode in Settings and returns, `onConfigChanged → loadItems()` finds the same `"tab|query"` key in the process-level cache and returns the **placeholder** list as if it were live, skipping the fetch for up to 45 s (the `now - cached.first < CACHE_TTL_MS` early return).
- **Why it matters:** The cache is a second source of truth for screen content (finding 4) and its key does not include the identity of the data source — a classic SSOT violation producing visibly wrong data (placeholder titles, no posters) in "Live" mode.
- **Fix:** Include the mode in the key (`"${AppState.liveMode}|$tab|$q"`) or call `cache.clear()` in the config-changed handler / in `AppState.saveConfig` completion.
- **Reference:** Android "App architecture" guide — SSOT; cache invalidation must be keyed to the source of the data.

### 7. MEDIUM — `LiveRepository.mediaById` lacks the snapshot fallback, so live statuses freeze after a cold start
- **Where:** `LiveRepository.kt:181` (`mediaCache[id] ?: Catalog.byId[id]`) vs `AppState.kt:70-79` (adds the request-snapshot fallback); `LiveRepository.kt:150` (`val media = mediaById(r.mediaId) ?: continue` inside `refreshStatuses`).
- **What:** After process death the repo's `mediaCache` is empty, so `refreshStatuses` hits `?: continue` for **every** live request — Jellyfin/*arr statuses are never refreshed until the user happens to browse that exact title (which populates the cache via `remember`). The snapshot persisted with each request (`MediaSnapshot`, has title/type) is exactly the data needed to fix this, but the repo doesn't know it exists.
- **Why it matters:** Two different `mediaById` implementations disagree about what "media identity" means — unclear state ownership with a user-visible consequence (Requests screen shows stale pending/processing forever after restart).
- **Fix:** Inject the 3-way resolver into the repository (constructor `mediaResolver: (String) -> Media?`), or move status refresh into a use case that resolves media through the SSOT `getById` (finding 4). Add `externalId`/`type` to `MediaSnapshot` if the live path needs it.
- **Reference:** Android guide — SSOT; Clean Architecture — the domain must not depend on which cache happens to be warm.

### 8. MEDIUM — Missing test seams in the UI layer: all orchestration lives in android.*-coupled classes with zero tests
- **Where:** `MainScreen.kt` (cache policy, load-job race handling, error mapping, poller wiring), `DetailActivity.kt` (`doRequest`, magnet loading), `RequestsActivity.kt` (signature diffing), `SettingsActivity.kt` (`collect`, `test`). All 5 test files (`app/src/test/...`) cover only the pure core.
- **What:** Every risky decision — cache TTL/stale-while-revalidate, `loadJob` guarding, request flow ordering, poller ticking, form→config mapping — is embedded in classes that import `android.app.Activity` etc. and can never run on the JVM. The well-tested pure core is the *safe* part; the untested views are the *risky* part.
- **Why it matters:** Clean Architecture achieves testability by keeping framework code at the outside and logic inside; the Android guide's layer separation exists so UI logic can be tested without a device. The seam currently stops exactly at the boundary where the bugs live (findings 2, 5, 6).
- **Fix:** Extract pure-Kotlin screen controllers (e.g. `MainScreenController(repo, cache, clock, onState: (UiState) -> Unit)`) that own decisions and expose state; the Activity/View becomes a dumb renderer. Unit-test the controllers exactly like `LiveParsingTest` does.
- **Reference:** Clean Architecture (Martin) — "the UI is a detail", testability via inward dependencies; Android guide — UI layer testability.

### 9. MEDIUM — God objects / SRP violations in three places
- **Where:** `AppState.kt` (141 lines: singleton state + persistence wiring + fallback lookup + owns the poller contract); `Catalog.kt` (390 lines: domain models + `RequestStoreCore` + placeholder data generation + search + magnet generation + `formatSize` formatting util); `Components.kt` (657 lines: layout atoms + status-label/color mapping `statusLabelOf`/`statusColorOf` + form atoms + `formatTime`).
- **What:** Each mixes several axes of change (model vs. data generation vs. presentation mapping vs. app lifecycle).
- **Why it matters:** SOLID-SRP: a class should have one reason to change. `Catalog.kt` changes when the placeholder content changes, when persistence format changes, and when formatting changes — three unrelated owners. `Components.kt` couples the design system to domain status semantics.
- **Fix:** Split into `Media.kt` (models), `RequestStore.kt` (store+rules), `Catalog.kt` (placeholder data), `Format.kt`; move `statusLabelOf/statusColorOf/StatusPill` into a `StatusPresentation.kt`; narrow `AppState` to a state holder and move `StatusPoller` construction to a small factory.
- **Reference:** SOLID — SRP; GoF — "program to an interface, favor object composition", small single-purpose objects.

### 10. MEDIUM — Two construction rules for `LiveRepository`: `SettingsActivity.test()` bypasses `RepositoryFactory`
- **Where:** `SettingsActivity.kt:150` (`LiveRepository(cfg).pingAll()`) vs `LiveRepository.kt:199-204` (`object RepositoryFactory.create(cfg, http)`) used by `AppState.kt:43,59`.
- **What:** The same heavy object is built in two places with different rules (factory returns null when not `liveReady`; the settings path constructs directly). The "single construction rule" comment in `RepositoryFactory` is not actually honored.
- **Why it matters:** DIP/Abstract Factory (GoF): one place should decide how a complex object graph is assembled; drift between the two sites (e.g. normalization, transport injection) silently changes behavior.
- **Fix:** Route the test through `RepositoryFactory.create(cfg, http)?.pingAll()` or a `ConnectionTester` use case that takes the factory.
- **Reference:** GoF — Abstract Factory; SOLID — DIP.

### 11. MEDIUM — Request-store mutations (incl. disk persist) run on the main thread in placeholder paths
- **Where:** `DetailActivity.kt:239` (`AppState.store.create(...)` on the UI thread), `AppState.kt:96` (`StatusPoller` runnable calls `AppState.store.tick()` on the main `Handler` → `persist()` at `Catalog.kt:249-264` does JSON serialization + `SharedPreferences` write).
- **What:** The store is thread-safe but not thread-*confined*; which thread performs I/O depends on the call site (live path uses a worker, placeholder path uses main).
- **Why it matters:** Android guide: the data layer must keep I/O off the main thread; with a single-writer discipline (finding 5) all mutations should also come from one context to keep ordering deterministic.
- **Fix:** Wrap `RequestStoreCore` in a facade that dispatches all mutations (`create/delete/updateStatus/tick`) onto one single-thread executor; views/poller just enqueue and observe results.
- **Reference:** Android "App architecture" guide — data layer off the main thread; SSOT single writer.

### 12. LOW — No Null Object for the repository: `RepositoryFactory.create` returns `null` and every caller null-checks
- **Where:** `LiveRepository.kt:201-203` (`return if (n.liveReady) LiveRepository(n, http) else null`); consumers `AppState.kt:71`, `MainScreen.kt:253`, `DetailActivity.kt:225,251,296` all do `AppState.repo?...`.
- **What:** Null-checks scattered through the UI re-introduce the live/placeholder branching that the repository abstraction (finding 1) is supposed to remove.
- **Fix:** With the `MediaRepository` interface in place, return a `PlaceholderRepository` instead of null (GoF Null Object); `liveMode` becomes a property of the selected implementation, not of every call site.
- **Reference:** GoF Design Patterns — Null Object pattern.

### 13. LOW — Process-global mutable `cache`/`signatures` in `MainScreen.Companion`, shared across Activity instances, unsynchronized, never cleared on config wipe
- **Where:** `MainScreen.kt:344-350`; read/written from `loadItems`/`renderCurrent`/`evictCache` (main thread today, but nothing enforces it).
- **What:** Rotation-safety is achieved by leaking mutable maps into a companion object with no ownership, no synchronization, and no invalidation on config change (only age-based eviction when `loadItems` happens to run).
- **Fix:** Move to an injectable `ResultCache` class with an explicit `clear()` called from the config-changed path and `synchronized` accessors.
- **Reference:** Android guide — state ownership/SSOT; Clean Architecture — framework-independent state holder.

### 14. LOW — `AppState.init`/`saveConfig` state transitions can race: a slow initial load can overwrite a newer save
- **Where:** `AppState.kt:40-49` and `56-66`: two fire-and-forget `Thread`s each `post { state = st }` with no ordering/versioning.
- **What:** Last-poster-wins on a `@Volatile var`; if `init`'s decrypt (100 ms+) finishes after a `saveConfig`, the freshly saved config is silently replaced by the stale loaded one.
- **Fix:** Route all state transitions through one single-thread executor, or stamp each transition with a monotonically increasing version and ignore stale posts.
- **Reference:** Android guide — single writer for state; Clean Architecture — state at the center has one owner.

### 15. LOW — `DetailActivity` never observes request-status changes: the action button goes stale while the screen is open
- **Where:** `DetailActivity.kt:185-216` (`renderActions` called only from `onCreate`, `doRequest`, and the cancel dialog) — the screen owns no poller/subscription, unlike `MainScreen`/`RequestsActivity`.
- **What:** A request created on the detail screen stays "⏳ pending" on that screen even after the poller (owned by another screen) advances it to AVAILABLE.
- **Fix:** Subscribe to store changes in `onStart`/`onStop` (shared listener from finding 3) or re-render in `onResume`.
- **Reference:** Android guide — UI observes state from its holder; unclear state ownership otherwise.

### 16. LOW — `ImageLoader` hardwires the global `realHttp` and is not injectable/testable
- **Where:** `ImageLoader.kt:36` (`realHttp("GET", url, ...)` directly), unlike every client which takes `HttpTransport`.
- **What:** The one component that fetches arbitrary URLs has no transport seam and no tests; it will happily fetch any string handed to it.
- **Fix:** Give `load(url, view, http: HttpTransport = realHttp)` the same seam as the clients.
- **Reference:** SOLID — DIP; consistency with the existing test-seam pattern.

---

## Top 5 changes

1. **Introduce a domain `MediaRepository` interface with `PlaceholderRepository` and `LiveRepository` implementations, selected once in the app container** — deletes every `if (liveMode)` branch and every direct `Catalog`/`LiveRepository` call from views/Activities, and gives the app a real SSOT (`getById` with documented priority + single `availabilityOf`). *(Findings 1, 4, 7, 12)* — Clean Architecture dependency rule + Android guide repository/SSOT.
2. **Extract use cases out of the views: `RequestMediaUseCase` (check-then-add ordering, fixing the duplicate *arr add) and `StatusRefreshUseCase`** — business rules move out of `DetailActivity`/`StatusPoller` into unit-testable pure Kotlin. *(Findings 2, 7)* — Clean Architecture interactors; Android guide domain layer.
3. **Replace the `AppState` service locator with a constructor-injected `AppContainer` and a multi-subscriber config-change notification** — ends the single-callback overwrite (`MainScreen.attach/detach`) and the init/save race. *(Findings 3, 14, 15)* — GoF Singleton/Service Locator; Android guide DI + state holders.
4. **Fix the `StatusPoller` busy-guard to span the async refresh, and confine all `RequestStoreCore` mutations (incl. `persist`) to one background executor** — removes overlapping refreshes and main-thread disk writes. *(Findings 5, 11)* — Android guide single-writer/off-main-thread data layer.
5. **Make the UI layer testable: extract pure-Kotlin screen controllers (cache policy, load jobs, error mapping) behind the views, key the result cache by mode/config and clear it on config change** — the risky orchestration gets the same JVM-test coverage the pure core already has. *(Findings 6, 8, 13)* — Android guide UI-layer testability; Clean Architecture "UI is a detail".
