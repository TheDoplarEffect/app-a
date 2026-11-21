# Round 5 — Architecture Review (master HEAD, v4.5 / cd6abab)

**Lens:** Clean Architecture layering, dependency rule (deps point inward), one-reason-to-change per module, RepositoryFactory indirection, coupling, data flow.
**Scope:** `app/src/main/java/com/example/appa/` (22 files, ~4,045 LOC), single Gradle module, single package `com.example.appa`, no androidx, programmatic Views. Tests in `app/src/test` (pure-JVM: Catalog, Tokens, PagerMath, LiveParsing, RequestStoreCore).

## What is good (keep)

- A genuinely pure domain/core exists: `Catalog.kt` (models `Media`, `Request`, `RequestStoreCore`, `StatusRules`, `ExternalRef`), `Config.kt` (`AppConfig`), `JsonX.kt`, `PagerMath.kt`, `Tokens.kt` have **no Android imports** and are unit-tested on the JVM (`app/src/test/...`). This is the right seed for an inner layer.
- HTTP clients (`Clients.kt`) all take an injectable `HttpTransport` (default `realHttp`), so parsing is testable (`LiveParsingTest` uses a fake transport). Good abstraction at the transport seam.
- `RepositoryFactory` (`LiveRepository.kt:211-215`) exists as the *single construction rule* for the live repo, and both `AppState.init` / `AppState.saveConfig` go through it (`AppState.kt:45,60`).
- Dependency direction at the *type* level is mostly inward: `Clients.kt` / `LiveRepository.kt` depend only on domain types (`Media`, `MediaType`, `Request`, `RequestStoreCore`), never on UI.
- `Http.kt` has a real SSRF guard (`isSafeExternalUrl`, `Http.kt:53-68`) and manual redirect handling that strips sensitive headers cross-host (`Http.kt:108-121`).

## Findings (ranked by severity)

### 1. HIGH — Process-global `WorkerScope.cancelAll()` is called from per-activity teardown and can kill other screens' work — including the one-shot config loader, which then never retries
- **Where:** `DetailActivity.onDestroy` → `WorkerScope.cancelAll()` (`DetailActivity.kt:103`); `MainScreen.detach` → `WorkerScope.cancelAll()` (`MainScreen.kt:163`); registry is a global `object WorkerScope` (`AppState.kt:141-162`).
- **Why it matters:** `DetailActivity` is opened *on top of* `MainActivity`. When the user backs out of Detail, `cancelAll()` interrupts **every** worker in the process: `MainScreen`'s in-flight `loadItems` (`MainScreen.kt:59,281`) and, critically, `AppState.init`'s one-shot loader (`AppState.kt:40-51`). Because `AppState.init` early-returns once `store` is initialized (`AppState.kt:31`), an interrupted init worker means `state` stays `State(AppConfig(), null)` forever — the app is silently stuck in placeholder mode with no retry. This is both a correctness bug and a coupling violation: one component's lifecycle destroys another component's in-flight work through a global registry.
- **Fix:** make `spawn` return a cancellable handle and let each owner cancel only its own work (per-screen `WorkerScope` instance, or a `Cancellable` token); never call a global `cancelAll()` from an Activity's `onDestroy`. Additionally, make `AppState.init` retryable (don't gate the async load on `::store.isInitialized`; gate only the prefs wiring).

### 2. HIGH — No enforced layer boundaries: one flat package + one Gradle module, with a global service locator (`AppState`) as the de-facto composition root; UI reaches through it to the concrete `LiveRepository`
- **Where:** everything lives in package `com.example.appa` (`app/src/main`); `AppState` is a global `object` with `lateinit var store` (`AppState.kt:11`), `@Volatile var onConfigChanged` global callback (`AppState.kt:22`), and `val repo: LiveRepository?` (`AppState.kt:25`). UI consumes it directly: `MainScreen.kt:281,299`, `DetailActivity.kt:66,68,232,258,303`, `RequestsActivity.kt:89,137`, `MainScreen.kt:343,351,367` (`AppState.store`, `AppState.requestFor`).
- **Why it matters:** the dependency rule (deps point inward) is only maintained by convention — nothing at build time stops `Catalog.kt` (domain) from importing `android.*`, or a domain file from referencing `AppState`. The global mutable `onConfigChanged` slot is a hidden coupling: `MainScreen.attach` overwrites it (`MainScreen.kt:63`) and `detach` nulls it (`MainScreen.kt:164`) — any second consumer would silently break. There is **no repository interface**: `RepositoryFactory.create` returns the concrete `LiveRepository?` (`LiveRepository.kt:214`), so the presentation layer is coupled to the data-layer class and to whatever `AppState` happens to hold, and none of the screen logic is unit-testable.
- **Fix:** split into Gradle modules with inward-only `implementation` deps — `core` (Catalog/Config/JsonX/PagerMath/Tokens + interfaces `MediaRepository`, `RequestStore`), `data` (Http/Clients/LiveRepository/SecureStore), `app` (screens/Components). Define `interface MediaRepository` in `core`; have `LiveRepository` implement it and `RepositoryFactory` return the interface. Replace `AppState` reach-through with constructor injection into screens (a small `AppContainer` created once in `MainActivity` and passed down), and replace the global `onConfigChanged` slot with an observable held by the container.

### 3. HIGH — `RepositoryFactory` indirection is bypassed by the UI layer
- **Where:** `SettingsActivity.test()` constructs `LiveRepository(cfg)` directly (`SettingsActivity.kt:159`), while the sanctioned path is `RepositoryFactory.create(cfg)` (`AppState.kt:45,60`; `LiveRepository.kt:211-215`).
- **Why it matters:** two construction sites for the same component means the "single construction rule" (live-ready gating, normalization, transport injection) can drift — e.g. a future rule change in the factory won't apply to the Settings ping. It also puts a data-layer concrete type directly in the presentation layer's hands.
- **Fix:** route the ping through the factory: `RepositoryFactory.create(cfg)?.pingAll()` (or expose `RepositoryFactory.ping(cfg)`); delete the direct `LiveRepository(...)` call.

### 4. MEDIUM — Parallel magnet models across layers; presentation depends on a data-layer type (dependency-rule violation)
- **Where:** domain `Magnet` (`Catalog.kt:49-58`) vs. data-layer `LiveMagnet` (`LiveRepository.kt:10-18`); the UI atom `MagnetRow(ctx, mag: LiveMagnet, ...)` (`Components.kt:600`) takes the data-layer type; `DetailActivity` hand-maps `Catalog.Magnet → LiveMagnet` in the screen (`DetailActivity.kt:272`) and branches live/placeholder in `loadMagnets` (`DetailActivity.kt:252-276`).
- **Why it matters:** the presentation layer now depends on `LiveRepository.kt`'s concrete type (outer→inner at the type level), and the live/placeholder mapping — a data-flow concern — lives in the Activity. Two magnet types with overlapping fields will drift (e.g. `addedAt`/`publishDate` exist only on one side).
- **Fix:** one domain `Magnet` type used by `Catalog`, `LiveRepository` (returning domain `Magnet`), and the UI; presentation maps domain→view-model only.

### 5. MEDIUM — Duplicated, diverging media-resolution fallback chains
- **Where:** `AppState.mediaById` (`AppState.kt:72-78`: repo → `Catalog.byId` → request snapshot) vs. `LiveRepository.mediaById` (`LiveRepository.kt:193`: cache → `Catalog.byId`), the latter used inside `refreshStatuses` (`LiveRepository.kt:159`).
- **Why it matters:** the live status refresh can silently resolve a media id against the *placeholder* catalog (wrong title/type fed into `LiveStatus.compute`), while the UI uses a different 3-step chain — same name, different semantics, in two layers. One reason to change, two places.
- **Fix:** a single `MediaResolver` in the domain layer with one explicit fallback order (live cache → catalog → request snapshot), injected into both `AppState` consumers and `LiveRepository.refreshStatuses`.

### 6. MEDIUM — `AppState.kt` bundles three responsibilities: global state, polling orchestration, thread registry
- **Where:** `object AppState` (`AppState.kt:10-78`), `class StatusPoller` (`AppState.kt:86-138`), `object WorkerScope` (`AppState.kt:141-162`) all in one file. `StatusPoller` directly couples to the global: `AppState.repo?.refreshStatuses(AppState.store)` and `AppState.store.tick()` (`AppState.kt:100,102,109`).
- **Why it matters:** three reasons to change in one file; the poller depends on the concrete repo + global store instead of an interface, so it can't be tested or reused independently (its own comment advertises "single implementation instead of two tickers" — but it's a shared *class*, each screen still instantiates its own, `MainScreen.kt:44`, `RequestsActivity.kt:23`).
- **Fix:** split into `AppState.kt` (state only), `StatusPoller.kt` taking a `StatusSource` interface (`fun refresh(store): List<Request>`, `fun tick(): List<Request>`), `WorkerScope.kt`. Consider moving the in-flight guard into the shared refresh path (see #10).

### 7. MEDIUM — `MainScreen` is a god-organism: view building + process-wide cache + load orchestration + rendering + lifecycle in one 379-line class
- **Where:** `MainScreen.kt:27-379` — `attach/buildPage` (view tree), companion `cache`/`signatures` process-wide mutable maps (`MainScreen.kt:370-377`), `loadItems` job-counter orchestration (`MainScreen.kt:251-313`), `renderCurrent`/`showError`/`showInlineError` (rendering), `onResume/onStart/onStop/detach` (lifecycle), badge wiring via string tag `"top_action_📥"` (`MainScreen.kt:80,367`).
- **Why it matters:** at least 5 reasons to change; the cache policy (TTL/LRU eviction, `evictCache` `MainScreen.kt:229-237`) is entangled with load orchestration; nothing here is testable.
- **Fix:** extract `ResultCache` (own class, key policy in one place), a `GridRenderer`, and a `MainController` (load/orchestration, injected with `MediaRepository` + cache); keep the class as a thin view binder.

### 8. MEDIUM — Business logic (request creation, dedup, external-ref wiring, snapshot) lives in the screen
- **Where:** `DetailActivity.doRequest` (`DetailActivity.kt:227-252`): checks `AppState.requestFor` (`:224`), calls `AppState.repo?.request(media)` and `AppState.store.create(media.id, external, snapshot)` from the UI thread's worker, builds `MediaSnapshot.of(media).toJson()`; the dedup check is duplicated between the screen and `RequestStoreCore.create` (`Catalog.kt:135-141`).
- **Why it matters:** the "never double-add in *arr" invariant — a domain rule — is enforced in the Activity, untestable; the request flow also mixes live/placeholder branches in the UI.
- **Fix:** a domain use case `RequestMedia(store, repo, media): Result<Request>` in `core`/`data` boundary (e.g. `RequestInteractor`) that does dedup → live add → store create → snapshot; unit-test it like `LiveParsingTest`.

### 9. LOW — SSRF guard is enforced at call sites, not centrally; `ImageLoader` fetches external poster URLs unguarded and hard-wires `realHttp`
- **Where:** `isSafeExternalUrl` is applied to redirect hops (`Http.kt:112`) and Prowlarr download URLs (`Http.kt:136`) but **not** to poster URLs: `ImageLoader.load` calls `realHttp("GET", url, ...)` directly (`ImageLoader.kt:35`) on `media.posterUrl`, which comes from external API responses (TMDB/OpenLibrary). `ImageLoader` also bypasses the injectable `HttpTransport` used everywhere else.
- **Why it matters:** a compromised/misbehaving metadata source could point the app at loopback/metadata URLs for image fetches; inconsistent with the guard's stated purpose ("URLs that come from external services"). Also makes ImageLoader untestable via fake transport.
- **Fix:** validate inside `realHttp` itself (reject `!isSafeExternalUrl(url)` on the initial URL), or wrap with a `safeGet`; give `ImageLoader` an injectable `HttpTransport`.

### 10. LOW — Poller in-flight guard is per-instance; status store is written from the data layer
- **Where:** `StatusPoller.busy` (`AppState.kt:88-97`) guards only that instance; `LiveRepository.refreshStatuses` mutates the store directly (`store.updateStatus(...)`, `LiveRepository.kt:177`) and owns the availability index (`cachedMovieTmdbIds`, `markAvailability`, `LiveRepository.kt:50-51,197-208`).
- **Why it matters:** two pollers (Main + Requests) can overlap *arr refreshes — safe today only because `MainScreen.onStop` stops its poller (`MainScreen.kt:152-156`); that's a lifecycle accident, not a design guarantee. Repo writing the store mixes read-model and write-model responsibilities.
- **Fix:** move the busy guard into the shared refresh path (repo or a `StatusRefresher` interface); have `refreshStatuses` *return* status updates and let the caller (poller/use case) apply them to the store.

### 11. LOW — Lifecycle/thread hygiene inconsistencies
- **Where:** `AppState.saveConfig` spawns a raw `Thread { }` (`AppState.kt:58`) instead of `WorkerScope.spawn` — untracked, uncancellable, inconsistent with the rest; `RequestStoreCore.persistIo = Executors.newSingleThreadExecutor()` (`Catalog.kt:250`) is never shut down, and `persist()` serializes the whole JSON while holding `lock` (`Catalog.kt:253-266`).
- **Why it matters:** the raw thread escapes the cancellation model from finding #1; the never-shutdown executor leaks a non-daemon thread (keeps test JVMs alive); serializing under the lock blocks all store reads.
- **Fix:** use `WorkerScope.spawn` in `saveConfig`; snapshot the list under the lock, serialize outside it, and shut down / daemonize `persistIo`.

### 12. LOW — Stringly-typed cross-layer contracts
- **Where:** navigation extra `"mediaId"` (`MainScreen.kt:352`, `RequestsActivity.kt:146`, `DetailActivity.kt:37`); Settings form keys `fields["tmdbToken"]`… (`SettingsActivity.kt:106-114`) duplicating the `AppConfig` field list, validated via a throwaway `AppConfig().urlError(...)` (`SettingsActivity.kt:63,122`); badge lookup by view tag `"top_action_📥"` (`MainScreen.kt:80,367`).
- **Why it matters:** a renamed key breaks at runtime with `!!` NPE, not compile time; the settings screen knows the config schema by string duplication.
- **Fix:** a `const val EXTRA_MEDIA_ID` / typed navigation helper; a single field-descriptor list (key, label, hint, secret, isUrl) driving both form creation and `collect()`; make `urlError` a companion/validator function; address badge via a returned handle from `AppTopBar`.

## Data-flow summary

`SettingsActivity` → `AppState.saveConfig` → `SecureStore` (encrypted prefs) → `RepositoryFactory.create` → `AppState.state` → global `onConfigChanged` → `MainScreen`. Reads: screens → `AppState.repo` (concrete `LiveRepository`) → `Clients` → `HttpTransport`; placeholder path: screens → `Catalog`. Statuses: `StatusPoller` (per screen) → `repo.refreshStatuses(store)` + `store.tick()` → re-render. The flow is coherent but crosses layers through the `AppState` global rather than through interfaces, and write paths (`doRequest`, `refreshStatuses`) originate in UI/data respectively without a use-case boundary.

## Suggested refactor order

1. Introduce `core` module + `MediaRepository`/`RequestStore` interfaces; make `LiveRepository` implement them; route `SettingsActivity` through `RepositoryFactory` (findings 2, 3).
2. Scope worker cancellation per owner; make `AppState.init` retryable (finding 1).
3. Unify the magnet model and the `mediaById` fallback chain in domain (findings 4, 5).
4. Split `AppState.kt` (state / poller / workers) and extract `ResultCache` + `RequestInteractor` from `MainScreen`/`DetailActivity` (findings 6, 7, 8).
5. Centralize the SSRF check in `realHttp`; inject transport into `ImageLoader`; hygiene fixes (findings 9-12).
