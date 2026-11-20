# Round 2 — Quality lens (Kotlin conventions / Effective Kotlin / Ousterhout)

Scope: `/home/dev/app-a/app/src/main` only. References: Kotlin Coding Conventions
(kotlinlang.org/docs/coding-conventions.html), Effective Kotlin (M. Moskala),
Ousterhout, *A Philosophy of Software Design* (deep modules, information hiding,
comments explain why).

## Findings (ranked)

### 1. HIGH — Silent fallback defaults feed the Sonarr/Radarr *write* path
`Clients.kt:236-237` and `Clients.kt:293-294` — `rootFolder()` / `qualityProfileId()` wrap the API call in `runCatching { … }.getOrNull() ?: "/tv"` (resp. `"/movies"`, `1`). Those defaults are then put into the `addSeries`/`addMovie` POST bodies (`Clients.kt:245-246`, `302-303`).
**Why it matters:** if the rootfolder/qualityprofile call fails (401, 5xx, timeout), `addSeries` still POSTs with a *guessed* root folder and quality profile 1 — Sonarr/Radarr can create the series in the wrong location or with the wrong profile, and the failure is completely invisible to the user. This is exception-masking at the worst possible place (a mutating request). *Ousterhout ch. 8: exceptions should be pulled up and handled at one deliberate level, not hidden behind default values; Effective Kotlin: never swallow exceptions without a documented reason.*
**Fix:** make `rootFolder()`/`qualityProfileId()` throw `ApiException` on failure (drop `runCatching`) — `lookupTmdb` on the same path already throws, so `addSeries`/`addMovie` already have a failure channel. Alternatively return `Result` and force the caller to handle it.

### 2. MEDIUM — Four inconsistent error-handling styles across layers
- `Http.kt:69-70` `httpGetJson` throws on any non-2xx; `Http.kt:74-92` `httpGetJsonRetrying` retries 5xx/network but throws 4xx immediately.
- `Clients.kt:91` `TmdbClient.details` catches *all* exceptions and silently returns the input.
- `Clients.kt:236-237, 259-272, 293-321` Sonarr/Radarr use `runCatching { … }.getOrElse { emptyList()/emptySet()/default }`.
- `LiveRepository.kt:61-68, 147-151` `runCatching { … }.getOrDefault(false/emptyList())`; only `MainScreen.kt:200-207` turns failure into a UI error state.
**Why it matters:** callers cannot predict what a failure looks like — an empty `queueSeriesIds()` is indistinguishable from "nothing queued", so request statuses silently stick at PENDING. *Ousterhout: a module should define one error contract, not scatter handling; mixing throw/retry/silent-default makes every module shallow.*
**Fix:** adopt one policy: clients throw `ApiException`; `LiveRepository` catches per-call *only where degradation is intended* (`pingAll`) and documents it; the UI catches at the boundary (already does).

### 3. MEDIUM — Triplicated `*arr` client plumbing (~40 duplicated lines)
`Clients.kt:176-180` (Prowlarr), `215-226` (Sonarr), `275-286` (Radarr) — identical `headers` map, `url()`, `api()`, and `post()` implementations. Drift is already visible: only the *arr clients use the retrying GET, TMDB/Jellyfin (`Clients.kt:42-43`, `Clients.kt:330-332`) use the non-retrying one.
**Why it matters:** every fix (header change, retry policy, error mapping) must be applied three times; the copies will diverge. *Effective Kotlin: avoid duplication; Ousterhout: one deep module should hide the HTTP/URL mechanics.*
**Fix:** extract `private open class ArrClient(baseUrl, apiKey, http)` exposing `get(path, params)` and `post(path, body)`; each service client keeps only endpoints and response parsing.

### 4. MEDIUM — `Triple` return values with positional, comment-only semantics
`Clients.kt:259,264` `seriesStates(): List<Triple<Int, Int, Boolean>> // (sonarrId, tvdbId, hasFiles)` and `Clients.kt:311,315` `movieStates(): List<Triple<Int, String, Boolean>>`, consumed positionally in `LiveRepository.kt:163-166` (`it.first == id`, `st?.third`).
**Why it matters:** the only documentation of what `first`/`third` mean is a trailing comment; `LiveRepository` silently treats a missing entry as `false`. *Effective Kotlin: prefer named data classes over Pair/Triple; Ousterhout: information hiding — don't expose positional tuples across module boundaries.*
**Fix:**
```kotlin
data class SeriesState(val sonarrId: Int, val tvdbId: Int, val hasFiles: Boolean)
data class MovieState(val radarrId: Int, val tmdbId: String, val hasFile: Boolean)
```

### 5. MEDIUM — Stringly-typed external refs parsed in three files
`"radarr:<id>" / "sonarr:<id>"` is produced in `LiveRepository.kt:131-139`, parsed with `removePrefix(...).toIntOrNull()` in `LiveRepository.kt:162-168`, prefix-matched again in `DetailActivity.kt:200`, and rendered with `substringBefore(":")` in `RequestsActivity.kt:121`; the raw string is persisted in `Catalog.kt` (`Request.external`).
**Why it matters:** the wire format leaks across persistence, repository and two UIs; any rename or new backend silently breaks parsing (an unparseable id becomes `-1`, which then matches nothing — same silent-failure trap as finding 2). *Ousterhout: don't let a string format cross module boundaries.*
**Fix:** define next to `Request`:
```kotlin
sealed interface ExternalRef {
    data class Radarr(val id: Int) : ExternalRef
    data class Sonarr(val id: Int) : ExternalRef
    object Local : ExternalRef
    fun toWire(): String; companion object { fun parse(s: String): ExternalRef }
}
```

### 6. MEDIUM — Settings validation runs the URL validator on every field, including API keys
`SettingsActivity.kt:57-66` attaches a `TextWatcher` to **all** nine fields that calls `AppConfig().urlError(et.text…)`. `Config.kt:38-40` defines `validUrl(u) = u.isBlank() || u.startsWith("http://")…`, so typing any token/key (e.g. `eyJ…`, `abc123`) makes a red "URL needs http:// or https://" appear under the *secret* fields. It also calls an instance method on a throwaway `AppConfig()` instance.
**Why it matters:** misleading validation UI on exactly the fields where a URL makes no sense; the static-style call signals the function belongs in a companion. *Kotlin Coding Conventions: don't invoke instance members through dummy instances — use `companion object`; correctness of validation scope.*
**Fix:** add `isUrl: Boolean = false` to `field(…)` and validate only `sonarrUrl/radarrUrl/prowlarrUrl/jellyfinUrl`; move `urlError`/`validUrl` into `AppConfig.Companion`.

### 7. MEDIUM — `showError` can stack duplicate error views; a later success leaves a stale overlay on the grid
`MainScreen.kt:240-242` removes `p.empty` and adds a new view tagged `"error"` without removing an existing one; `MainScreen.kt:255` (`renderCurrent`) removes only the *first* tag match. Sequence fail → Retry → fail stacks two error views; a subsequent success removes one, and the remaining `MATCH_PARENT` error view stays on top of the grid forever.
**Why it matters:** real user-visible bug (grid permanently hidden after a retry cycle), caused by an implicit invariant ("at most one error view") that the code never enforces. *Ousterhout: make invariants explicit in code, not in comments.*
**Fix:** in `showError`, first `p.root.findViewWithTag<View>("error")?.let { p.root.removeView(it) }`; better, never detach `p.empty` — keep it attached and toggle `visibility`.

### 8. MEDIUM — Long functions mixing abstraction levels
`DetailActivity.onCreate` (`DetailActivity.kt:36-124`, ~90 lines: top bar + hero + scrim + details thread + meta card + actions + magnets), `MainScreen.attach` (`MainScreen.kt:63-137`, ~75 lines), `RequestsActivity.render` (`RequestsActivity.kt:85-151`, ~67 lines), `PosterCard` (`Components.kt:469-~530`, ~60 lines of nested DSL).
**Why it matters:** each function spans "build view tree", "wire listeners", and "start network" — three abstraction levels; changes in one area require reading all of it. *Effective Kotlin: functions should be short and do one thing (extract parts of long functions); Ousterhout: split top-down into smaller methods.*
**Fix:** extract `buildHero()`, `buildMetaCard()`, `buildMagnetsSection()` in `DetailActivity`; `buildTopBar()/buildSearch()/buildPager()/buildNav()` in `MainScreen.attach`; split `PosterCard` into poster-frame + caption builders.

### 9. MEDIUM — Magic numbers/strings scattered outside the token system
`Components.kt:345` (350 ms debounce), `Components.kt:446-457` (poster text layout: `0.84f/0.14f/0.33f`, `<= 14` chars, `> 3` lines, `w*0.115f…`), `Http.kt:29-30` (15_000/20_000), `Http.kt:78-79` (3 attempts / 600 ms), `AuroraBackground.kt:36-37,55,97` (÷8, 24_000, alpha 52), `Glass.kt:38` (26/16 alpha boost), `SecureStore.kt:52` (GCM `128`/IV `12`), `Catalog.kt:263,287-288`, `Clients.kt:270,321,351` (`pageSize=200`, `page = 200`), `LiveRepository.kt:104` (`take(25)`), `MainScreen.kt:59` (5000/1000 ms ticks).
**Why it matters:** the project already set the rule in `Tokens.kt` — "the ONLY place raw design values live" — but logic constants ignore it; tuning or understanding behavior requires hunting numbers through five files. *Kotlin Coding Conventions (naming/readability) + the project's own Tokens.kt principle.*
**Fix:** named constants at the top of each file or a small `Constants` object: `SEARCH_DEBOUNCE_MS = 350`, `CONNECT_TIMEOUT_MS = 15_000`, `RETRY_ATTEMPTS = 3`, `GCM_TAG_BITS = 128`, `GCM_IV_LENGTH = 12`, `ARR_PAGE_SIZE = 200`, `MAX_MAGNET_RESULTS = 25`, `TICK_LIVE_MS = 5_000` / `TICK_LOCAL_MS = 1_000`.

### 10. MEDIUM — Duplicated status-ticker logic in two screens
`MainScreen.kt:43-60` and `RequestsActivity.kt:60-75` contain the same tick → background `refreshStatuses` → `handler.post { render() }` → `postDelayed(if (liveMode) 5000 else 1000)` block.
**Why it matters:** the polling interval and the live/local branch will drift apart (they already differ in what they re-render). *Effective Kotlin: eliminate duplication.*
**Fix:** extract `class StatusTicker(private val handler: Handler, private val onChanged: () -> Unit)` with `start()`/`stop()`; both screens use it.

### 11. LOW — Dead code in main sources (one entry also has a broken contract)
- `Components.kt:351` `SearchField.clearSilently()` — never called; and it is *not* silent: `input.setText("")` fires `onTextChanged`, which schedules `onQuery("")` 350 ms later (`Components.kt:339-346`).
- `Motion.kt:25` `EmphasizedPath` — unused.
- `Http.kt:14` `Resp.networkError` — unused.
- `Catalog.kt:20` `MediaType.fromSlug` — unused.
- `DetailActivity.kt:34` `detailsRequested` — write-only (set at `:95`, never read).
- `AuroraBackground.kt:107` `renderOnce()` — "for tests/previews", but no test calls it.
**Why it matters:** dead code misleads readers about what is live behavior. *Effective Kotlin: remove unused code; Ousterhout: comments and code should not advertise things that don't exist.*
**Fix:** delete all six; if `clearSilently` is wanted, implement it with a `suppressEvents` flag around `setText` and call it from the tab-switch path.

### 12. LOW — Unbounded in-memory caches
`MainScreen.kt:32-33` — `cache` and `signatures` maps keep one entry per distinct `tab|query` forever; the 45 s TTL (`CACHE_TTL_MS`, `:273`) only gates refetching, never eviction.
**Why it matters:** a long session with many searches grows both maps (and their `List<Media>` payloads) without bound. *Effective Kotlin: avoid unbounded mutable state.*
**Fix:** in `loadItems`, evict entries with `now - fetchedAt > CACHE_TTL_MS` (and drop their `signatures` entry), or cap to the last N keys.

### 13. LOW — Mutable shared state handled inconsistently
`LiveRepository.kt:49` `audiobookIndexerIds` is a plain `var` read/written from arbitrary background threads (`magnetsFor` runs on `Thread`s from `DetailActivity`), while its neighbors at `:50-51` are `@Volatile`; app-wide mutables `AppState.state` (`AppState.kt:15`), `AppTheme.current` (`Tokens.kt:181`), `SecureStore.wasWiped` (`SecureStore.kt:20`) are mutable singletons with implicit single-writer assumptions.
**Why it matters:** the data-race contract is invisible; the non-`@Volatile` field can be re-resolved concurrently (benign today, but the inconsistency invites a real race later). *Effective Kotlin: minimize shared mutable state; where it exists, make the synchronization explicit.*
**Fix:** make `audiobookIndexerIds` `@Volatile` (or `AtomicReference`) and add a one-line comment on `AppState`/`AppTheme` stating the single-writer rule.

### 14. LOW — `wasWiped` is never reset after a successful save
`SecureStore.kt:20,67` sets `wasWiped = true` when the ciphertext is unreadable; `saveConfig` (`:58-61`) never clears it, so `SettingsActivity.kt:82-84` (via `AppState.kt:30`) shows "⚠ Stored config was reset…" for the rest of the process even after the user re-enters and saves new keys.
**Fix:** set `wasWiped = false` at the start of `saveConfig` (and `AppState.configWasWiped` is re-read on next `init` anyway).

### 15. LOW — Naming deviations from the Kotlin Coding Conventions
Top-level factory functions use PascalCase — `AppText` (`Components.kt:57`), `AppTopBar` (`:111`), `Chip`, `GlassCard`, `PosterCard` (`:469`), `PrimaryButton` (`:179`)… The conventions allow PascalCase only for factory functions *named like the class they return*; here `AppText` returns a `TextView`, so lowerCamelCase (`appText`, `chip`, …) is the conformant form. Additionally `qenc` (`Http.kt:99`) is a cryptic abbreviation, and `Catalog.kt:302-303` re-implements it verbatim as private `urlEncode` — two copies of the same URL-encoder.
**Why it matters:** convention violations slow down readers who expect lowerCamelCase functions, and the duplicated encoder is exactly the kind of copy that drifts. *Kotlin Coding Conventions, "Names".*
**Fix:** rename factories to lowerCamelCase (or group them in a `Components` object where PascalCase reads as a type), rename `qenc` → `encodeQueryParam`, delete `Catalog.urlEncode` and use the shared one.

### 16. LOW — `View.tag` overloaded for two unrelated purposes
`ImageLoader.kt:29,36,42` stores the image URL in `view.tag`, while components use `tag` as a semantic lookup key: `"top_action_${a.icon}"` (`Components.kt:128`) looked up as the literal `"top_action_📥"` in `MainScreen.kt:268`, `"badge"` (`Components.kt:150`), `"error"` (`MainScreen.kt:242`).
**Why it matters:** string/emoji tag coupling breaks silently if two actions share an icon or an emoji is changed; tag collisions between the two uses are one refactor away. *Ousterhout: information hiding — don't expose lookup internals through a general-purpose field.*
**Fix:** have `AppTopBar` expose its action buttons (`val actionButtons: Map<String, FrameLayout>` keyed by `contentDescription`) and use that in `MainScreen.renderRequestsBadge`; reserve `tag` for ImageLoader (or a dedicated `R.id`-style key).

### 17. LOW — Assorted leaky details
- `BottomNavBar.select` reads `AppTheme.current` (`Components.kt:271,278`) instead of the constructor `t` — build-time and runtime theme can diverge on theme swap.
- `realHttp` hard-codes `Content-Type: application/json` for any body (`Http.kt:35`) even though callers pass it in headers (`Clients.kt:222,282`) — the transport silently overrides caller headers.
- Settings hints hard-code an example LAN IP `http://192.168.178.42:…` (`SettingsActivity.kt:70-74`) — a magic string that looks like real user config.
- Parallel `Magnet` / `LiveMagnet` types with different field orders, bridged by a positional constructor call (`DetailActivity.kt:231-233`) — easy to swap `seeders`/`leechers` by mistake.
- `AuroraBackground.kt:100` comment refers to "the SDK stub" in production code.
**Fix:** use the `t` parameter in `select()`; in `realHttp` only set Content-Type if absent from headers; use a neutral hint (`http://192.168.1.x:8989`); add `LiveMagnet.from(m: Magnet)` with named arguments; drop the stub remark.

## Top 5 changes I would make

1. **Make `rootFolder()`/`qualityProfileId()` failures fatal in `addSeries`/`addMovie`** (`Clients.kt:236-237,293-294`) — no silent `"/tv"`/`1` defaults in write requests.
2. **Collapse Prowlarr/Sonarr/Radarr into one `ArrClient` base class** (`Clients.kt:175-286`) with a single `get`/`post`/retry implementation.
3. **Fix Settings validation** (`SettingsActivity.kt:57-66`): validate only the four URL fields, and move `urlError`/`validUrl` to `AppConfig.Companion`.
4. **Replace `Triple` returns and stringly `"radarr:/sonarr:"` refs with named types** (`Clients.kt:259,311`; `LiveRepository.kt:162-168`) — `SeriesState`/`MovieState` data classes and a sealed `ExternalRef` with one `toWire`/`parse` site.
5. **Fix the `showError` duplicate-overlay bug and extract the duplicated status ticker** (`MainScreen.kt:240-255,43-60`; `RequestsActivity.kt:60-75`), and split the long `onCreate`/`attach`/`render` functions into per-section builders.
