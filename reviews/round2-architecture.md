# Round 2 — Architecture Review (`architecture` lens)

Scope: `app/src/main/java/com/example/appa/` only. References used: Robert C. Martin, *Clean Architecture* (dependency rule, use cases/interactors, UI as a peripheral); GoF *Design Patterns* (Strategy, Information Expert, Dependency Injection vs. Service Locator); Android developer guide, *App architecture* (UI layer / data layer separation, repository pattern, single source of truth, exposing state to the UI); SOLID (SRP, OCP, DIP).

The codebase is honestly documented and has good *local* seams (`HttpTransport` injection in `Clients.kt`, load/save lambdas in `RequestStoreCore`, pure `PagerMath`/`Tokens`/`LiveStatus`), but at the architectural level there is **no domain layer, no repository abstraction, and one global mutable singleton (`AppState`) that every screen reaches into statically**. The live/placeholder "strategy" is branched on inside the UI layer in ~10 places instead of being polymorphism. Findings below are ranked.

---

## Findings

### 1. HIGH — No repository abstraction: the live-vs-placeholder strategy is branched inside the UI layer
**Files:** `MainScreen.kt:46,59,75,83,88,167,189,205,228`; `DetailActivity.kt:94,195,215,263`; `RequestsActivity.kt:46,62,70`

Every screen does `if (AppState.liveMode) { repo… } else { Catalog… }`. The UI layer therefore knows about two concrete data sources (`LiveRepository`, `Catalog`) and owns the selection policy.

**Why it matters:** This violates OCP (every new backend means touching every screen) and the Android guide's UI/data-layer separation ("the UI layer should consume data from the data layer via a repository; the UI should not know where data comes from"). It also forces the UI to bridge two incompatible magnet types by hand (`DetailActivity.kt:224-227` maps `Catalog.magnetsByMediaId` → `LiveMagnet` inside the view). Clean Architecture dependency rule: the UI (outer circle) may depend inward on a domain *abstraction*, not on two concrete data implementations and a mode flag.

**Proposed fix:** Introduce a domain interface and let polymorphism replace the branches:
```kotlin
interface MediaRepository {
    fun browse(type: MediaType): List<Media>
    fun search(query: String): List<Media>
    fun details(media: Media): Media
    fun magnetsFor(media: Media): List<LiveMagnet>
    fun request(media: Media): ExternalRef          // see finding 4
    fun refreshStatuses(store: RequestStoreCore): List<Request>
    fun pingAll(): Map<String, Boolean>
}
class CatalogRepository : MediaRepository { /* wraps Catalog, magnets already LiveMagnet-shaped */ }
class LiveRepository(...) : MediaRepository { /* current live impl */ }
```
`AppState` (or its replacement, finding 2) holds exactly one `MediaRepository` chosen once at bootstrap; delete every `AppState.liveMode` branch from screens — `loadItems`, `doRequest`, `loadMagnets`, `copyMagnet`, both tickers, and the top-bar subtitle all collapse into single calls through the interface. Cite: GoF *Strategy*; Android guide "Repository pattern"; Clean Architecture dependency rule.

### 2. HIGH — `object AppState` is a global mutable singleton (service locator + god object) that everything depends on
**File:** `AppState.kt:6-45`

A Kotlin `object` with `lateinit var store`, a `@Volatile var state` pair, a mutable `configWasWiped` flag, and `init(context)`/`saveConfig(context, …)` that take a `Context` deep inside global state. All four screens call `AppState.init(this)` in `onCreate` and then statically read `AppState.repo/store/config/liveMode` from inside lambdas, tickers, and render paths.

**Why it matters:** GoF explicitly contrasts Dependency Injection with the Service Locator anti-pattern: a global locator hides the real dependencies of every class, makes the dependency graph unenforceable, and makes the UI layer untestable (you cannot substitute fakes). SOLID DIP: screens should depend on an *abstraction* they receive, not on a concrete global. It also mixes concerns in one object: persistence wiring (SharedPreferences), secure-config loading, repository construction, and derived state (`liveMode`). The mutable `configWasWiped` side-channel (read by `SettingsActivity.kt:81`) is a hidden cross-screen dependency. Clean Architecture: the composition root (Application) should wire dependencies inward and hand them to the UI.

**Proposed fix:** Define `interface AppContainer { val config: AppConfig; val repo: MediaRepository; val store: RequestStoreCore; fun saveConfig(cfg: AppConfig) }` (or a small `Repositories` holder), expose one instance from a custom `Application` class, and have each Activity obtain it via constructor/field injection (`(application as App).container`). Make the held state an immutable snapshot (`data class AppState(val config, val repo, val store)`) replaced atomically on save, instead of three separately-mutable globals. Cite: GoF *Dependency Injection* vs. Service Locator; SOLID DIP; Clean Architecture composition root.

### 3. HIGH — Single source of truth is violated: persisted requests reference media that lives only in a volatile in-memory cache, so requests silently disappear after a cold start
**Files:** `LiveRepository.kt:184-187` (`mediaCache` is a `ConcurrentHashMap`, never persisted), `AppState.kt:44` (`mediaById` = repo cache `?:` Catalog), `RequestsActivity.kt:92` (`mapNotNull { AppState.mediaById(...) }`)

`RequestStoreCore` persists requests to SharedPreferences, but the `Media` objects they point to are only remembered via `LiveRepository.remember()` during browsing. On process restart the cache is empty, `Catalog.byId["movies-680"]` is `null`, and `RequestsActivity.render()` filters the request out with `mapNotNull` — the user's live-mode requests vanish from the Requests screen until the item is browsed again.

**Why it matters:** The Android guide's "single source of truth" principle says each piece of data has exactly one owner. Here media identity has four owners: `MainScreen.cache`, `LiveRepository.mediaCache`, `Catalog.byId`, and the persisted store — and none is authoritative. This is a concrete, user-visible data-loss symptom of the architectural flaw, not a nit.

**Proposed fix:** Make the request record self-sufficient: persist a minimal media snapshot (`title`, `type`, `year`, `posterUrl`, `externalId`) alongside each request in `RequestStoreCore` (one more tab-separated field, or a JSON line), and have `RequestsActivity` render from `request + snapshot` with `mediaById` used only to enrich. Alternatively, make the repository the SSOT with a small disk cache of browsed media. Cite: Android guide "Single source of truth"; Clean Architecture (entities owned by the inner circle, not by UI caches).

### 4. HIGH — Stringly-typed backend references (`"radarr:<id>"`) are parsed in both the repository and the UI
**Files:** `LiveRepository.kt:162-171` (`startsWith("radarr:")` / `removePrefix` / `toIntOrNull()`), `DetailActivity.kt:200` (same prefix sniffing to decide the toast label "Added to Radarr/Sonarr/local list"), `Catalog.kt:107` (field comment defines the wire format)

The external-reference "protocol" is a raw string whose format is defined by a comment, and parsing it is duplicated in the data layer and the UI layer.

**Why it matters:** Clean Architecture's dependency rule says outer layers must not know inner-layer data formats — here the UI parses a data-layer encoding. Any format change (or a third backend) requires coordinated edits in two layers, and a typo silently degrades to the `else` branch (`false to false` in `refreshStatuses`, "local list" in the toast). SOLID SRP: the knowledge "how an external ref is encoded/decoded" belongs to exactly one owner.

**Proposed fix:** Put a typed value in the domain layer:
```kotlin
sealed interface ExternalRef {
    data class Radarr(val id: Int) : ExternalRef
    data class Sonarr(val id: Int) : ExternalRef
    data object Local : ExternalRef
    fun encode(): String = when (this) { is Radarr -> "radarr:$id"; is Sonarr -> "sonarr:$id"; Local -> "" }
    companion object { fun decode(s: String): ExternalRef = ... }
}
```
`Request.external: ExternalRef`, `LiveRepository.request()` returns `ExternalRef`, `refreshStatuses` pattern-matches on the sealed type, and the UI maps `ExternalRef` → display label via a domain function (`backendName()`). Cite: Clean Architecture dependency rule; SOLID SRP; GoF *Information Expert*.

### 5. MEDIUM-HIGH — `LiveRepository` is a god object that also writes into the domain store from the data layer
**File:** `LiveRepository.kt:37-202`

One class constructs and owns six HTTP clients (lines 39-44), three caches (`audiobookIndexerIds`, `cachedMovieTmdbIds`, `cachedSeriesTmdbIds`, `mediaCache`), availability marking as a hidden side effect of reads (`markAvailability`, lines 189-202, mutates `mediaCache` inside `browse`/`search`/`details`), magnet query building, request routing to the right *arr, and status orchestration. Worse, `refreshStatuses(store)` (line 142) takes the `RequestStoreCore` and **mutates it** (`store.updateStatus`) — the data layer writing into the domain-owned store.

**Why it matters:** SOLID SRP — this class has at least five reasons to change (TMDB, OpenLibrary, Prowlarr, *arr, Jellyfin). Clean Architecture: use-case interactors in the inner circle should orchestrate entities and *return* results; having the repository mutate the store inverts ownership and makes `refreshStatuses` impossible to run against anything but a real store. The side-effecting cache writes inside read methods also make "browse" non-idempotent in observable behavior.

**Proposed fix:** Split along responsibilities: `TmdbCatalog`/`OpenLibraryCatalog` behind a `CatalogSource`, `RequestBackend` (Sonarr/Radarr add + state), `MagnetIndex` (Prowlarr), `LibraryIndex` (Jellyfin). Change `refreshStatuses` to *return* updates — `fun statusUpdates(store: RequestStoreCore): List<Pair<String, RequestStatus>>` — and let a domain interactor apply them via `store.updateStatus`. Make `markAvailability` a pure function returning the marked list; the caller decides whether to cache. Cite: SOLID SRP; Clean Architecture use-case interactor; Android guide (repositories coordinate data sources, domain owns state transitions).

### 6. MEDIUM — Business/orchestration logic lives in the views: caching policy, load races, and the "request a title" use case
**Files:** `MainScreen.kt:25-70,165-213` (`cache` map + TTL + `loadJob` counter + ticker + error/Retry UI built inline in the screen), `DetailActivity.kt:192-212` (`doRequest` decides live vs. local, calls repo, creates the store entry, formats the user-facing result string)

`MainScreen` is simultaneously view-builder, cache owner, scheduler, and renderer. `DetailActivity.doRequest` is a use case (add to backend → persist request → report outcome) embedded in a click handler.

**Why it matters:** The Android guide assigns the UI layer one job — *display* state and forward user events; business logic belongs in a data/domain layer or a ViewModel-style state holder ("UI layer: display state; data layer: hold and expose state"). Clean Architecture likewise wants controllers/presenters thin, with use cases inward. Today you cannot unit-test the request flow or the stale-while-revalidate cache policy without an Activity.

**Proposed fix:** Extract a screen-state holder per screen (a plain class, no androidx needed): e.g. `class MainScreenState(repo: MediaRepository, store: RequestStoreCore, dispatcher: …)` exposing `fun load(tab, query)`, `fun observe(): …`, and a `RequestMediaUseCase(repo, store)` returning a `Result<RequestOutcome>` (sealed: Added(backend), AlreadyRequested, Failed) that `DetailActivity` renders. The view keeps only: build views, call state holder, render what it returns. Cite: Android guide "UI layer" / "ViewModel: expose state to UI"; Clean Architecture controllers & presenters.

### 7. MEDIUM — Ad-hoc `Thread { }` + lifecycle flags everywhere; no dispatcher seam, so the concurrency is untestable and un-cancellable as a unit
**Files:** `MainScreen.kt:48,189`; `DetailActivity.kt:96,196,217,261`; `RequestsActivity.kt:64`; `SettingsActivity.kt:133`

Five call sites spawn raw threads and guard with `attached` / `isFinishing` / `myJob != loadJob` after the fact. There is no injectable dispatcher/executor, so tests cannot run these paths deterministically, and nothing prevents overlapping 5-second tickers (each `refreshStatuses` can outlive the next one; only luck plus `@Volatile` caches keeps it safe).

**Why it matters:** The Android guide prescribes coroutines/structured concurrency precisely so work is scoped to a lifecycle and injectable for tests; raw threads scattered through views are the pre-architecture pattern. It is also a missing seam (GoF: depend on abstractions): the `HttpTransport` seam exists for the network, but not for "which thread does work" or "post back to main".

**Proposed fix:** Introduce a tiny seam — `interface AppDispatchers { val io: Executor; fun main(block: () -> Unit) }` (default: `Executors.newSingleThreadExecutor()`-backed io + `Handler(Looper.getMainLooper())`) — injected into screens and the repository. Give each screen a `Scope`-like handle (`start()/cancel()`) called from `onDestroy`/`detach()` (which already exists in `MainScreen.detach()` / `RequestsActivity.onDestroy()`), and guard the ticker so a refresh cannot overlap itself (`if (refreshing.getAndSet(true)) return`). Cite: Android guide "Kotlin coroutines / structured concurrency"; GoF — program to an interface for testability.

### 8. MEDIUM — UI snapshots global state at attach time and never observes it: stale "Placeholder" UI after enabling live mode
**Files:** `MainScreen.kt:75,83,88` (subtitle, banner, search hint read once in `attach()`), `MainScreen.kt:46,59,167` (ticker/load re-read `AppState.liveMode` each run)

After saving config in `SettingsActivity`, returning to `MainActivity` (which is still alive) leaves the top-bar subtitle "Placeholder", the banner, and the search hint stale, while the ticker and loader already run in live mode — the screen shows contradictory state.

**Why it matters:** The Android guide says the UI layer should *expose/consume state as an observable stream* from a single source of truth; here the config is read once at view-construction and separately re-read in behavior code, so the view and the behavior disagree. Unclear ownership of state: who is responsible for re-rendering the header when config changes? Nobody.

**Proposed fix:** Make the container's config observable (a simple `var config` + listener list, or a `MutableStateFlow`-equivalent hand-rolled holder since no androidx), have `MainScreen` subscribe in `attach()` and re-render the header/banner/hint on change, and unsubscribe in `detach()`. At minimum, re-read in `onResume`/refresh the header views. Cite: Android guide "Expose state to the UI / single source of truth"; Clean Architecture — UI observes, never owns, domain state.

### 9. MEDIUM — The domain model carries presentation values, and the data layer hard-codes UI colors
**Files:** `Catalog.kt:44-45` (`Media.color1/color2`), `Clients.kt:118` (`color1 = "#1f6feb", color2 = "#8250df"` in `TmdbClient.toMedia`), `Clients.kt:149` (OpenLibrary hard-codes `#953800/#e16f24`)

`Media` mixes domain facts (id, title, externalId) with view concerns (poster gradient colors), and the HTTP/parsing layer decides those colors.

**Why it matters:** Clean Architecture dependency rule — inner circles must not know outer-circle details; a data-layer client assigning hex colors to a domain entity is exactly an inward→outward leak. It also blocks theming: `PorcelainLight` can't influence poster colors because they travel inside the model produced by the data layer. (Posters from `posterUrl` are fine; it's the gradient fallback that leaks.)

**Proposed fix:** Drop `color1/color2` from `Media`; derive the gradient palette in the presentation layer (e.g. `GradientPoster` picks from a token palette by a stable hash of `media.id`, exactly as `Catalog.magnetsFor` already hashes ids). The data layer returns only content fields. Cite: Clean Architecture dependency rule; SOLID DIP (domain independent of presentation).

### 10. MEDIUM — Everything lives in one package; the dependency rule is enforced only by a comment, not by structure
**Files:** all 21 files in `app/src/main/java/com/example/appa/`

`Catalog.kt` (pure domain: `Media`, `Request`, `StatusRules`, `RequestStoreCore`) sits in the same package as `MainScreen.kt`, `Components.kt`, `SecureStore.kt` (Android). The "no Android imports in domain" rule exists only as a doc comment ("No Android imports, so it is unit-testable", `Catalog.kt:6-8`).

**Why it matters:** Clean Architecture's dependency rule is enforced by *module/package boundaries* precisely so the compiler rejects inward violations; in a single package nothing stops a domain file from importing `android.*` tomorrow, and nothing stops the UI from depending on data internals (which it already does via `AppState`). Tests currently work only by convention.

**Proposed fix:** Split into packages/modules with an explicit inward direction: `com.example.appa.core` (pure Kotlin: `Catalog.kt` domain parts, `Config.kt`, `PagerMath.kt`, `Tokens.kt`, `RequestStoreCore`) with no Android dependency, `com.example.appa.data` (`Clients.kt`, `Http.kt`, `LiveRepository.kt`, `SecureStore.kt`, repositories), `com.example.appa.ui` (`MainScreen`, `DetailActivity`, `RequestsActivity`, `SettingsActivity`, `Components.kt`, `Glass.kt`, `Motion.kt`, `AuroraBackground.kt`, `AppState`→container). A Gradle `core` module makes "domain has no Android imports" a build failure, not a comment. Cite: Clean Architecture dependency rule ("source code dependencies must point inward… use modules to enforce"); Android guide recommended app architecture (ui / domain / data layers).

### 11. MEDIUM — `SettingsActivity` constructs a second `LiveRepository` directly, bypassing the app's single instance; construction logic is duplicated
**Files:** `SettingsActivity.kt:134` (`LiveRepository(cfg).pingAll()`), `AppState.kt:31-32,37-38` (`if (cfg.normalized().liveReady) LiveRepository(cfg) else null` in both `init` and `saveConfig`)

"Test connection" builds a throwaway repository from unsaved form values — which is reasonable — but it does so by duplicating the exact construction rule that `AppState` uses, and the throwaway shares no code path with the real one beyond the class itself.

**Why it matters:** Ownership of object construction is unclear: two places decide when a `LiveRepository` exists. If the construction rule changes (e.g. "also require Prowlarr for magnets"), both must be updated in sync. SOLID DIP/DRY: creation should flow through one factory.

**Proposed fix:** Add `object RepositoryFactory { fun create(cfg: AppConfig, http: HttpTransport = realHttp): MediaRepository? }` used by both the container (finding 2) and the settings test (`factory.create(cfg)?.pingAll()`), so "when does a live repo exist" has exactly one definition. Cite: GoF *Abstract Factory* / DI; SOLID DIP.

### 12. LOW-MEDIUM — `Triple` with inconsistent field semantics across clients: `seriesStates(): Triple<Int, Int, Boolean>` (sonarrId, tvdbId, hasFiles) vs `movieStates(): Triple<Int, String, Boolean>` (radarrId, tmdbId, hasFile)
**Files:** `Clients.kt:259`, `Clients.kt:311`; consumed at `LiveRepository.kt:160-171` (`st?.third`, `it.first`)

**Why it matters:** GoF *Information Expert* / basic readability: a `Triple` tells the reader nothing at the call site (`st?.third` — third of what?). The two clients happen to agree that `.third` is `hasFile` today; nothing enforces it, and the middle field differs in type and meaning (tvdbId vs tmdbId). A component consuming these "knows too much" about two backends' shapes.

**Proposed fix:** Named data classes: `data class SeriesState(val sonarrId: Int, val tvdbId: Int, val hasFiles: Boolean)` and `data class MovieState(val radarrId: Int, val tmdbId: String, val hasFile: Boolean)`, plus a common `interface ArrState { val id: Int; val hasFile: Boolean }` so `refreshStatuses` can treat them uniformly. Cite: GoF Information Expert; SOLID SRP/readability of interfaces.

### 13. LOW — Unsynchronized shared mutable state in `LiveRepository`: `audiobookIndexerIds` is a plain `var` written from arbitrary worker threads
**File:** `LiveRepository.kt:49-58`

The sibling caches are `@Volatile` (lines 43-44) but `audiobookIndexerIds` is not; `magnetsFor` can run on concurrent `DetailActivity` threads. Worst case is a redundant Prowlarr call (benign), but it's an inconsistent thread-safety policy inside one class.

**Why it matters:** Ownership of concurrency policy is scattered (some fields volatile, one not, `mediaCache` a CHM, the store internally synchronized). A single rule — "all mutable repo state is `@Volatile` or synchronized" — would make the class auditable.

**Proposed fix:** Make it `@Volatile` like its siblings, or compute it in an `init`-time lazy with `@Synchronized`; document the one rule. Cite: Clean Architecture (entities/use cases define and own their invariants); general thread-safety ownership.

### 14. LOW — Inconsistent seams: `ImageLoader` is a global object hard-wired to `realHttp`, unlike the clients which accept an injectable `HttpTransport`
**File:** `ImageLoader.kt:32` (`realHttp("GET", url, …)` inside the singleton)

**Why it matters:** The codebase has a good testing seam pattern (`HttpTransport`) but doesn't apply it uniformly; the image path can't be faked or pointed at a cache server in tests, and the global object has process-wide mutable cache state (another unowned singleton, cf. finding 2).

**Proposed fix:** Give `ImageLoader` a constructor taking `http: HttpTransport = realHttp` and an injectable cache, and obtain it from the app container rather than as a Kotlin `object`. Cite: GoF — depend on abstractions; Android guide DI guidance.

### 15. LOW — Domain pieces live in misleadingly-named files; `AppConfig.urlError` is an instance method used as a static
**Files:** `Catalog.kt:73-166` (`StatusRules`, `RequestStoreCore` inside "Catalog.kt"), `SettingsActivity.kt:60` (`AppConfig().urlError(...)` constructs a throwaway config to call a stateless function)

**Why it matters:** File/package names are the cheapest navigation aid the dependency rule has; `StatusRules`/`RequestStoreCore` are domain logic, not catalog data, and the throwaway-instance call signals the function belongs on the companion. Small, but it compounds finding 10.

**Proposed fix:** Move `StatusRules` + `RequestStoreCore` + `Request` into a `RequestStore.kt` (domain), move `AppConfig.urlError`/`validUrl` to the companion object, and do the package split from finding 10. Cite: Clean Architecture (entities/use cases in the inner circle, named as such); SOLID SRP.

---

## Top 5 changes I would make

1. **Introduce `interface MediaRepository` with `CatalogRepository` and `LiveRepository` implementations, chosen once at bootstrap** — deletes ~10 `if (AppState.liveMode)` branches from the screens and the hand-rolled `Catalog.Magnet → LiveMagnet` bridging in `DetailActivity` (findings 1, 4).
2. **Replace `object AppState` with an injectable `AppContainer` interface wired in a custom `Application`**, holding an immutable state snapshot; screens receive it instead of statically reaching into a global (findings 2, 8, 11).
3. **Make the request store self-sufficient** — persist a media snapshot (or disk-cache browsed media) so live-mode requests don't vanish from the Requests screen after a cold start; single source of truth for both requests and media (finding 3).
4. **Introduce typed `ExternalRef` (sealed class) for `radarr:<id>`/`sonarr:<id>`** — removes string-prefix parsing from both `LiveRepository.refreshStatuses` and `DetailActivity.doRequest`, and gives the UI a domain-level `backendName()` (finding 4).
5. **Split `LiveRepository` into focused services (catalog / request backend / magnet index / library index), move orchestration out of views into use cases or screen-state holders, and replace raw `Thread {}` with an injected dispatcher + lifecycle-scoped cancellation** (findings 5, 6, 7), with a `core`/`data`/`ui` package split so the compiler enforces the dependency rule (finding 10).
