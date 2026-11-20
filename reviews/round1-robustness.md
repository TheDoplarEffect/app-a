# Round 1 — Robustness / Bugs review (app-a)

Lens: threading, ANRs, leaks, recreate loops, uncaught exceptions, null-safety, prefs/Keystore, cleartext, timeouts/retries (Prowlarr), image loading.

**References consulted (cited by name):**
- Android Developers, *"ANRs"* (developer.android.com/topic/performance/vitals/anr) — sections **"Slow code on the main thread"** and **"I/O on the main thread"**: any blocking network/disk I/O on the UI thread risks the 5-second input-dispatch ANR.
- Android Developers, *`android.os.NetworkOnMainThreadException`* (API 11+): "thrown when an application attempts to perform a networking operation on its main thread" — thrown by default for apps targeting Honeycomb+ (this app targets 35).
- Android Developers, *`android.os.StrictMode`*: "most commonly used to catch accidental disk or network access on the application's main thread … to prevent ANR dialogs" (`ThreadPolicy.Builder().detectAll().penaltyLog()`).
- OWASP **MASVS** control groups (mas.owasp.org/MASVS): **MASVS-NETWORK-1** ("data is encrypted in transit … secure connections in any situation" / platform secure defaults not disabled), **MASVS-STORAGE** (secure storage of sensitive data at rest), **MASVS-CRYPTO**, **MASVS-PLATFORM** (secrets must not leak to other apps/surfaces e.g. clipboard/logs).
- Defensive Kotlin: `runCatching` boundaries at thread/IO edges, no `!!` across module boundaries, null-safe propagation.

---

## HIGH

1. **Blocking HTTP on the main thread in `LiveRepository`'s `init` block — guaranteed `NetworkOnMainThreadException` crash in live mode.**
   - `LiveRepository.kt:51-54` — the constructor `init { if (cfgN.hasProwlarr) { audiobookIndexerIds = prowlarr.indexerIdByName("audiobook") … } }` performs a synchronous `HttpURLConnection` call (`Clients.kt:177` → `Http.kt:18-33`).
   - It is constructed on the **main thread** from `AppState.init` (`AppState.kt:30`), called in `MainScreen.attach()` (`MainScreen.kt:63`, from `MainActivity.onCreate`), and from `SettingsActivity.kt:27`, `RequestsActivity.kt:28`, `DetailActivity.kt:32` — and again on the main thread in `AppState.saveConfig` (`AppState.kt:38`) from `SettingsActivity.save()` (`SettingsActivity.kt:96-98`). With live mode + Prowlarr configured, the app crashes on first launch (and when pressing Save) with `NetworkOnMainThreadException` per the Android docs; even without the exception it is a multi-second main-thread freeze (25 s connect timeout, `Http.kt:22`) → ANR per the ANR doc's "I/O on the main thread".
   - **Fix:** remove all network I/O from the constructor. Make `audiobookIndexerIds` `@Volatile` + lazily resolved on first use inside `magnetsFor` (which already runs on background threads), cached with `runCatching`, and re-resolved when blank. Optionally wrap construction in `runCatching` so a failed Prowlarr lookup can never take down `AppState.init`.

2. **`MainScreen` ticker is never stopped and spawns unbounded raw `Thread`s — Handler leak + thread pile-up (main source of "flaky" behavior).**
   - `MainScreen.kt:44-58`: `ticker` reschedules itself via `handler.postDelayed(this, 5000/1000)` and every live tick spawns a new `Thread { … refreshStatuses … }` (`MainScreen.kt:48`). There is **no** `onDestroy`/detach path — `MainActivity.java` only overrides `onCreate`, and the manifest has no `configChanges`, so every rotation leaks another permanent ticker, and after leaving the screen the ticker keeps doing network I/O and posting into dead views (Handler holds the destroyed activity's view tree).
   - Each tick spawns a new thread without checking whether the previous refresh is still running; with 25 s/30 s timeouts (`Http.kt:22-23`) a slow Prowlarr/Jellyfin makes ticks overlap → concurrent `refreshStatuses` calls racing on shared repo state (see #6).
   - **Fix:** add `MainScreen.detach()` (remove callbacks) called from `MainActivity.onDestroy`; replace `Thread` + `postDelayed` with a single-threaded `ScheduledExecutorService` (schedule at fixed rate, so ticks can't overlap); guard the posted UI update with an `isAttached`/`active` flag.

3. **`DetailActivity` recreate loop is only partially guarded — can still loop forever.**
   - `DetailActivity.kt:91-99`: when `needsDetails` (genres empty **and** overview blank) and `tmdb.details()` returns a copy that differs in *any* field (rating/year) but still leaves genres empty + overview blank (common for obscure TMDB entries), `d != media` stays true → `recreate()` → `onCreate` → `needsDetails` still true → fetch again → **infinite recreate loop** (each iteration = one network call + full view rebuild; ANR/flicker — matches the "still quite buggy" report). The comment at `DetailActivity.kt:88-90` claims the guard fixed the loop; it doesn't for empty-metadata items.
   - **Fix:** track fetched ids in the repo (e.g. `private val detailsFetched = ConcurrentHashMap.newKeySet<String>()` checked in `details()`/`markAvailability`) or, better, update the existing views in place instead of `recreate()`.

## MEDIUM

4. **No retry/backoff for Prowlarr 5xx/timeouts — one failed call empties the whole magnet list.**
   - `Http.kt:55-57` (`httpGetJson` throws on any non-2xx, single attempt), `Clients.kt:186-199` (`ProwlarrClient.search`), `LiveRepository.kt:93-114` (`magnetsFor`), `Http.kt:45-51` (`magnetFromDownloadUrl`, single attempt, 25 s/30 s timeouts). Any 5xx / `SocketTimeoutException` → `runCatching` at `DetailActivity.kt:209-210` → header shows "search failed" (`DetailActivity.kt:214-216`). Additionally `audiobookIndexerIds` is resolved exactly once in the constructor (`LiveRepository.kt:53`); if Prowlarr was down at that moment it stays `""` forever and audiobook searches silently hit **all** indexers (`LiveRepository.kt:99`) — a concrete source of "flaky Prowlarr magnet search".
   - **Fix:** add a small retry helper (2–3 attempts, exponential backoff ~500 ms/1.5 s) applied to Prowlarr search + magnet resolve for 5xx and `IOException`/timeouts only; resolve `audiobookIndexerIds` lazily on first audiobook search and re-try when blank.

5. **`magnetFromDownloadUrl` accepts any 3xx `Location`, and the failure fallback copies a URL containing the API key to the clipboard.**
   - `Http.kt:47-51`: any 3xx `Location` (including an HTML login/error page) is returned as the "magnet"; on a 200/5xx it silently returns the Prowlarr `downloadUrl` itself. `DetailActivity.kt:242-245` then does `getOrElse { mag.downloadUrl }` and copies it — Prowlarr download URLs embed `?apikey=…` (`Clients.kt:177`), so a failed resolve **puts the Prowlarr API key on the clipboard** (MASVS-PLATFORM/STORAGE secret-leak surface).
   - **Fix:** return `loc` only if it starts with `magnet:` or ends with `.torrent`; otherwise return `""`. In `copyMagnet`, on failure show an error toast instead of copying `downloadUrl`, and strip any `apikey` query param from any URL ever copied/displayed.

6. **Unsynchronized shared mutable state in `LiveRepository` accessed from many threads.**
   - `LiveRepository.kt:47-49` (`audiobookIndexerIds`, `cachedMovieTmdbIds`, `cachedSeriesTmdbIds`) and `:180-195` (`mediaCache = mutableMapOf()`) are read/written concurrently by the main ticker thread (`MainScreen.kt:48`, `RequestsActivity.kt:64`), the `loadItems` threads (`MainScreen.kt:178,202`), and Detail threads (`DetailActivity.kt:94,209,242`) with no synchronization → `ConcurrentModificationException` / torn reads. `RequestStoreCore` itself is properly `synchronized` (clean), but the repo is not.
   - **Fix:** use `ConcurrentHashMap` for `mediaCache`, `@Volatile` for the scalar fields, or confine all repo calls to one single-thread executor (pairs well with fix #2).

7. **`AppState.repo!!` null-safety holes + non-atomic `liveMode`/`repo` pair.**
   - `MainScreen.kt:179,203`, `DetailActivity.kt:95,190,210,244` use `AppState.repo!!`. `AppState.liveMode` (`AppState.kt:22`) reads `config` while `repo` is assigned separately in `saveConfig` (`AppState.kt:37-41`), so a background thread can observe `liveMode == true` with `repo == null` (e.g. config changed while MainScreen is alive) → `!!` throws inside `runCatching` → UI silently renders an empty grid with no error state.
   - **Fix:** store `(config, repo)` in one `@Volatile` holder updated atomically in `saveConfig`; replace every `repo!!` with `?: return@Thread` / `.getOrElse` plus a visible error/empty state.

8. **ImageLoader: no timeouts, 3-thread pool can all hang, no retry.**
   - `ImageLoader.kt:19` (`Executors.newFixedThreadPool(3)`) + `:32` (`URL(url).openStream()` — `URLConnection` with **no** connect/read timeout) means one stuck TMDB/OpenLibrary connection permanently occupies pool threads → all later poster loads silently never complete; failure path only sets a background color, never retries.
   - **Fix:** route image fetches through `realHttp` (which sets 25 s/30 s timeouts, `Http.kt:22-23`) or set timeouts explicitly; use a bounded queue; skip work when `!view.isAttachedToWindow` in the main-thread callback.

9. **Global cleartext traffic + API keys in URL query strings.**
   - `AndroidManifest.xml:5` `android:usesCleartextTraffic="true"` disables the platform TLS default for **every** host (MASVS-NETWORK-1: "platform secure defaults" must not be disabled wholesale). Keys are sent as `?apikey=$apiKey` in URLs (`Clients.kt:177,215,218,272,275`), so Sonarr/Radarr/Prowlarr **access logs** record the secrets.
   - **Fix:** add `res/xml/network_security_config.xml` with `cleartextTrafficPermitted` limited to private/LAN ranges (or per-host `192.168.x.x`), referenced from the manifest instead of the global flag; send *arr keys via the `X-Api-Key` header (supported by Sonarr/Radarr/Prowlarr).

10. **SecureStore: key-invalidation edge silently wipes config.**
    - `SecureStore.kt:23-35`: if the AndroidKeyStore key is permanently invalidated (restore to new device, keystore reset), `ks.getKey` returns null and a **new** key is generated; `decrypt` (`SecureStore.kt:46-50`) then throws `AEADBadTagException`, and `loadConfig` (`SecureStore.kt:57-60`) swallows it via `runCatching` → user silently gets an empty `AppConfig()`. (Positive: AES/GCM with a Keystore-backed key otherwise matches MASVS-STORAGE/MASVS-CRYPTO.)
    - **Fix:** catch `KeyPermanentlyInvalidatedException` explicitly, delete the stale ciphertext, and surface "config was reset" to the user instead of a silent default.

## LOW

11. **Skeleton shimmer animator never cancelled.** `Components.kt:521-528` starts an `INFINITE` `ValueAnimator` per skeleton; `showLoading` (`MainScreen.kt:134-138`) just sets `GONE` and `renderCurrent` does `removeAllViews()` (`MainScreen.kt:223`) — animators keep firing on detached views (contrast `AuroraBackground.kt:57-61`, which correctly cancels in `onDetachedFromWindow`). Fix: make `Skeleton` a `View` subclass that cancels its animator in `onDetachedFromWindow`.
12. **RequestsActivity: in-flight refresh thread renders after destroy.** `RequestsActivity.kt:64-66` posts `render()` with no `isDestroyed`/`isFinishing` guard (the ticker itself *is* correctly removed in `onDestroy`, `RequestsActivity.kt:78-81` — clean). Fix: `if (isDestroyed) return@post`.
13. **SearchField debounce fires after destroy.** `Components.kt:291-300`: pending runnable → `onQuery` → `loadItems()` on a dead `MainScreen`. Fix: remove callbacks on detach (fold into fix #2).
14. **Duplicate-request toast lies.** `DetailActivity.kt:188-196`: `store.create()` returns `null` when the media is already requested (`Catalog.kt:108-110`), but the toast still says "Added to …". Fix: branch on the null result → "Already requested".
15. **Search cache keyed by query only, not tab; stale-fresh timestamp.** `MainScreen.kt:190,207` (`searchCache[key]`) reuses Movies-tab results on the Series tab; `MainScreen.kt:184` sets `loadedAt[tab] = now` using the pre-fetch timestamp. Minor UX bugs; fix by keying cache on `tab to query` and stamping after the fetch completes.
16. **`realHttp` lets transport exceptions escape raw.** `Http.kt:18-33`: `conn.responseCode` throws `IOException` on timeout/DNS (no `connect()` guard, no status-0 `Resp` fallback); every call site must remember `runCatching` (`magnetFromDownloadUrl` `Http.kt:45` and `ProwlarrClient.magnetOf` `Clients.kt:202` rely on callers). Fix: wrap the transport body in `runCatching` and return `Resp(status = 0, …)` on failure so the type is total, and add the retry helper from fix #4 at Prowlarr call sites.

## Areas that are clean (one line each)
- `RequestStoreCore` (`Catalog.kt:100-165`): fully `synchronized` with `runCatching` around each persisted line parse — thread-safe and corruption-tolerant. Clean.
- `MainScreen.loadItems` job-generation guard (`MainScreen.kt:131,148-150,177-181,201-205`) correctly drops stale async renders. Clean.
- `AuroraBackground` cancels its animator in `onDetachedFromWindow` (`AuroraBackground.kt:57-61`). Clean.
- `RequestsActivity` removes its ticker in `onDestroy` (`RequestsActivity.kt:78-81`). Clean.
- `runCatching` boundaries exist at every `Thread {}` network call site in screens — no currently uncaught exception in a `Thread{}`, but see #7 for the `!!` inside them.
- SharedPreferences writes use `apply()` (async) (`AppState.kt:27-29`), and SecureStore uses AndroidKeyStore AES/GCM — no plaintext secrets in prefs. Clean per MASVS-STORAGE.

## Suggested guardrail
- No `StrictMode` anywhere in the app. Enable `StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog()` in debug builds (per the StrictMode docs) — it would have flagged finding #1 immediately.
