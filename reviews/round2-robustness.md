# Round 2 — Robustness Review (lens: ANR/StrictMode, threading & lifecycle, coroutines best practices, error handling, OWASP mobile)

Scope: only the current code under `/home/dev/app-a/app/src/main` (plus manifest/network-security-config/build.gradle). References cited per finding: Android ANR/vitals docs (developer.android.com/topic/performance/vitals/anr), Android "Threading" & "Process lifecycle" docs, Kotlin coroutines best practices (structured concurrency, no fire-and-forget/GlobalScope), Kotlin null-safety docs, general error-handling guides, OWASP MASVS/MSTG, Android network-security-config docs, Android "Loading Large Bitmaps Efficiently".

---

## Findings (ranked)

### 1. HIGH — `Resp.header()` throws NPE on every real response; magnet resolution is silently broken
- **File:** `Http.kt:10-11` (used at `Http.kt:56` in `magnetFromDownloadUrl`)
- **What:** `headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }` — `HttpURLConnection.getHeaderFields()` always contains a **null key** for the status line (platform type `Map<String!, List<String>!>`), so `it.key.equals(...)` throws NPE as soon as the predicate reaches that entry. The map is built at `Http.kt:41` with `mapValues`, which preserves the null key.
- **Why it matters:** `magnetFromDownloadUrl` calls `resp.header("Location")` on every 3xx, so resolving a Prowlarr download URL to a magnet **always throws**; the exception is swallowed by `runCatching` in `DetailActivity.copyMagnet` (`DetailActivity.kt:261-278`) and surfaces only as a generic "Magnet resolve failed: null" toast. The whole "Copy magnet" feature is dead in live mode. Classic Kotlin platform-type null-hostility bug.
- **Fix:** `headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()`. Add a unit test with a `Resp` built from a real `headerFields`-style map containing a null key.
- **Reference:** Kotlin null-safety / platform-types docs; Android `HttpURLConnection.getHeaderFields()` docs (status line has null key); error-handling guides (don't let unexpected exceptions masquerade as domain errors).

### 2. HIGH — Unbounded thread spawning from the 5s tickers; a slow backend turns each tick into minutes of queued threads
- **File:** `MainScreen.kt:43-59` (ticker spawns a new `Thread {}` every 5000 ms in live mode), `RequestsActivity.kt:60-75` (same pattern), work in `LiveRepository.refreshStatuses` (`LiveRepository.kt:142-176`), timeouts/retries in `Http.kt:29-30, 82-90`
- **What:** The ticker starts a fresh `Thread` every 5 s without checking whether the previous `refreshStatuses` finished. One refresh chains many sequential HTTP calls (Jellyfin paged ids + 4 *arr calls), each with 15 s connect + 20 s read timeouts and up to 3 attempts with backoff — worst case ≈ 100+ s per call, minutes per refresh. Threads pile up unbounded, hammering the services and eventually exhausting memory/threads.
- **Why it matters:** Kotlin coroutines best practices require structured concurrency — never fire-and-forget unbounded work; Android vitals/ANR guidance cares about keeping the app responsive and not doing unbounded work. Two screens polling simultaneously (see #9) makes it worse.
- **Fix:** Guard with an `AtomicBoolean refreshInFlight` (skip tick if still running) and/or use a single-thread `ExecutorService` for all repo work; better, a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that is cancelled in `onDestroy`, with `refreshStatuses` suspending (`delay()` instead of `Thread.sleep`).
- **Reference:** Kotlin coroutines best practices (structured concurrency, no GlobalScope/fire-and-forget); Android ANR docs (responsiveness, avoid unbounded work on the app).

### 3. HIGH — Disk + Keystore/crypto I/O on the main thread during `onCreate` and on save
- **File:** `AppState.kt:23-33` (`init`, called from `MainScreen.attach()` at `MainScreen.kt:63-64` inside `MainActivity.onCreate`), `AppState.kt:35-39` (`saveConfig` ← `SettingsActivity.save()`), `SecureStore.kt:28` (`KeyStore…load(null)`), `SecureStore.kt:61-70` (`getSharedPreferences` first read + AES-GCM decrypt), `SecureStore.kt:56-59` (encrypt on save)
- **What:** First `getSharedPreferences` reads the XML from disk; `KeyStore.getInstance("AndroidKeyStore").load(null)` hits the keystore files; Cipher init/decrypt runs on the main thread. `save()` encrypts on the main thread. StrictMode `detectAll()` is only installed in debug builds (`MainActivity.java:11-15`), so release never even logs these.
- **Why it matters:** Android's ANR guidance explicitly calls out disk I/O on the main thread (StrictMode `DiskReadViolation`) as a cause of jank and input-timeout ANRs, especially on cold start when flash is slow. Crypto/keystore operations are not free either.
- **Fix:** Make config loading lazy/async: load `AppConfig` on a background executor before first live use (e.g., a `lazy` backed by `Dispatchers.IO`, or an init latch), and do `SecureStore.saveConfig` inside the same background path (e.g., `Executors.newSingleThreadExecutor().execute { … }` from `save()`). Keep `StrictMode` penalty in debug and add a `DiskReadViolation`/`NetworkOnMainThread` penaltyDeath in debug to catch regressions.
- **Reference:** Android ANR docs (developer.android.com/topic/performance/vitals/anr — "disk I/O on the main thread", StrictMode); Android Threading docs ("don't block the main thread").

### 4. HIGH — Unbounded download + full-resolution bitmap decode in `ImageLoader` → OOM crash
- **File:** `ImageLoader.kt:30-38` (`realHttp` + `BitmapFactory.decodeByteArray(resp.bytes, 0, resp.bytes.size)`), `Http.kt:40` (`stream?.use { it.readBytes() }` — no size cap)
- **What:** Poster bytes are read fully into memory with no `Content-Length` check and no byte cap, then decoded at original resolution (no `inJustDecodeBounds`/`inSampleSize`). A large or hostile image (Open Library covers, or a redirect target) of e.g. 50–100 MP decodes to hundreds of MB.
- **Why it matters:** OWASP mobile (uncontrolled resource consumption → OOM) and Android's bitmap docs both require bounding both the download and the decode. A single oversized poster can crash the app; the `LruCache` only helps after a successful decode.
- **Fix:** In `realHttp` (or a dedicated image transport) cap the read (check `Content-Length`, read at most ~8–10 MB); in `ImageLoader` decode bounds first and compute `inSampleSize` so the bitmap is ≈ the view size (posters are ~150 dp wide); `recycle()` evicted bitmaps if needed.
- **Reference:** OWASP MASVS/MSTG (resource management); Android developer docs "Loading Large Bitmaps Efficiently" (inJustDecodeBounds + inSampleSize).

### 5. MEDIUM-HIGH — `network_security_config.xml` blocks cleartext to real LAN IPs, so HTTP live mode cannot work
- **File:** `app/src/main/res/xml/network_security_config.xml:5-12`
- **What:** `<domain>` entries are network addresses (`10.0.0.0`, `172.16.0.0`, `192.168.0.0`). Android's network security config matches `<domain>` by **hostname** (an IP literal must be listed exactly); `192.168.0.0` never matches host `192.168.178.42`. With `targetSdk 35` and `base-config cleartextTrafficPermitted="false"`, a connection to the very URL the settings UI suggests (`http://192.168.178.42:8989`, `SettingsActivity.kt:71`) fails with `CleartextNotPermittedException` → surfaces as a generic "network error" and live mode over LAN HTTP is broken.
- **Why it matters:** Functional break of the app's core live feature on LAN, plus confusing error reporting (see also #11 on the masked cast).
- **Fix:** All public endpoints in code are hard-coded `https://`, so invert the config: `<base-config cleartextTrafficPermitted="true"/>` plus `<domain-config cleartextTrafficPermitted="false">` listing `api.themoviedb.org`, `image.tmdb.org`, `openlibrary.org`, `covers.openlibrary.org` (defense-in-depth: public APIs can never go cleartext), which permits cleartext to any user-configured LAN host. Alternatively enumerate the exact LAN IPs.
- **Reference:** Android network security config docs (domain matching rules; IP literals must be exact; cleartext defaults false for targetSdk ≥ 28).

### 6. MEDIUM — API keys forwarded to arbitrary redirect targets (automatic redirect following with auth headers)
- **File:** `Http.kt:31` (`conn.instanceFollowRedirects = followRedirects`), `Http.kt:67,84` (`httpGetJson`/`httpGetJsonRetrying` pass `followRedirects = true`), credential headers in `Clients.kt:43` (TMDB Bearer), `Clients.kt:134,176` (X-Api-Key), `Clients.kt:217-220` (Jellyfin `Authorization: …Token=…`), `Clients.kt:259`
- **What:** `HttpURLConnection` re-sends all `setRequestProperty` headers (including `Authorization`/`X-Api-Key`) to the redirect target. If any configured service (or a compromised/DNS-spoofed LAN host) answers 3xx to an attacker host, the API keys leak.
- **Why it matters:** OWASP — unvalidated redirects / credential exposure. The app deliberately avoids copying the apikey-bearing download URL (`DetailActivity.kt:265`), but the same keys are exposed one redirect away on every GET.
- **Fix:** Follow redirects manually (max 3): issue the request with `instanceFollowRedirects = false`, inspect `Location`, allow only same-host or explicitly allowlisted hosts, and strip `Authorization`/`X-Api-Key` on any cross-origin hop. Only `magnetFromDownloadUrl` (which sends no credentials) should follow external redirects, and only to `magnet:`/`.torrent` targets as it already does.
- **Reference:** OWASP MASVS/MSTG (insecure redirect handling, sensitive data in transit); Android `HttpURLConnection` docs on redirect behavior.

### 7. MEDIUM — SSRF-style fetch of unvalidated URLs returned by external APIs
- **File:** `Http.kt:53-60` (`magnetFromDownloadUrl` GETs `downloadUrl` verbatim), source of `downloadUrl` at `Clients.kt:150-168` (Prowlarr JSON); config URLs validated only by scheme prefix at `Config.kt:38-40`
- **What:** `downloadUrl` comes from Prowlarr's response JSON and is fetched with no scheme/host/IP validation — an indexer entry could point at `http://169.254.169.254/…`, `file://` (the `as HttpURLConnection` cast then throws, masked as network error), or any internal host, and the app will issue the request. Similarly, a user-entered service URL is only checked to start with `http(s)://`, yet the API key is then sent to whatever host it names.
- **Why it matters:** OWASP — unvalidated external input used as a request target (SSRF); sensitive keys sent to unvalidated endpoints. On a phone on hostile Wi-Fi, a DNS-spoofed "Prowlarr" controls these URLs.
- **Fix:** Validate scheme is `http(s)`; resolve the host and reject loopback/link-local/metadata IPs (169.254.169.254) unless it is one of the user-configured service hosts; cap response size (ties into #4) and redirect count; in Settings, warn when a *arr/Jellyfin URL points to a public/non-private host before saving.
- **Reference:** OWASP MASVS/MSTG (input validation, SSRF); Android networking docs.

### 8. MEDIUM — Activities retained by uncancellable raw `Thread {}` work; no cancellation in `onDestroy`
- **File:** `DetailActivity.kt:96, 196, 217, 261` (details, doRequest, loadMagnets, copyMagnet threads; class has **no `onDestroy`**), `MainScreen.kt:189-207` (`loadItems` thread outlives `detach()` at `MainScreen.kt:132-135` — only the `handler.post` callback is guarded), `RequestsActivity.kt:64-67`, `SettingsActivity.kt:133`
- **What:** Every screen spawns raw `Thread { … }` capturing the Activity. The UI callbacks check `isFinishing/isDestroyed/attached`, but the threads themselves keep running until the HTTP calls time out (up to ~35 s per call, ~minutes with retries per #2), retaining the whole Activity (views, window refs) after the user leaves or the device rotates.
- **Why it matters:** Android process-lifecycle guidance says to release resources in `onDestroy` and never leak Activity contexts; Kotlin structured-concurrency guidance says cancel child work when the owner dies. Today the "leak" is bounded only by network timeouts.
- **Fix:** Give each screen a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (or a `MutableList<Thread>` registry) cancelled/interrupted in `onDestroy`; check `isActive`/`Thread.interrupted()` around each network call; pass `applicationContext` where a context is needed off the UI thread.
- **Reference:** Android "Process lifecycle" docs (release resources in onDestroy; Activity leaks); Kotlin coroutines best practices (structured concurrency, cancellation on lifecycle end).

### 9. MEDIUM — Polling keeps running while other screens are in front (duplicate 5s pollers)
- **File:** `MainScreen.kt:59,128` + `MainActivity.java:21-24` (only `onDestroy` stops the ticker; no `onStop`/`onPause` handling), `RequestsActivity.kt:68`
- **What:** Opening Requests/Settings/Detail leaves `MainActivity` alive (just paused), so its ticker keeps firing `refreshStatuses` every 5 s; `RequestsActivity` adds a second concurrent poller. Two screens then race on `LiveRepository` state (#14) and double the request load on Jellyfin/*arr.
- **Why it matters:** Android lifecycle guidance: stop expensive/periodic work in `onStop` when the UI is no longer visible. Also wastes battery and can trigger rate limiting on the *arr APIs.
- **Fix:** In `MainActivity`, override `onStop()`/`onStart()` (or `onPause`/`onResume`) to call `screen.pause()`/`screen.resume()`, where pause does `handler.removeCallbacks(ticker)` and resume re-posts it; same for `RequestsActivity`.
- **Reference:** Android "Process lifecycle" / Activity docs (onStop: release resources, stop work when not visible).

### 10. MEDIUM — Unbounded pagination loop in `JellyfinClient.itemTmdbIds`
- **File:** `Clients.kt:348-358` (`while (true)` with `StartIndex += page`; breaks only on empty or short page)
- **What:** A server (or a misbehaving proxy) that always returns a full 200-item page makes this loop infinite; the refresh thread never returns and `refreshStatuses` never completes, feeding the pile-up in #2.
- **Why it matters:** Robustness principle: never trust external input to terminate a loop; error-handling guides require bounding loops over remote data.
- **Fix:** Cap iterations (e.g., 100 pages ≈ 20k items) and/or honor the response's `TotalRecordCount` (`if (start >= total) break`).
- **Reference:** General error-handling/robustness guidance (bounded loops on external input); Android vitals (hung background work starves the app).

### 11. MEDIUM — Crash/misclassification on malformed external input: `toInt()`, unchecked cast, untyped parse errors
- **File:** `Clients.kt:299` (`JsonPrimitive(tmdbId.toInt())` in `RadarrClient.addMovie` — throws `NumberFormatException` when `externalId` is blank/non-numeric, reachable because `AppState.mediaById`/`LiveRepository.mediaById` fall back to placeholder `Catalog` entries whose `externalId` is `""` — `AppState.kt:44`, `LiveRepository.kt:187`); `Http.kt:26` (`URL(url).openConnection() as HttpURLConnection` — bad scheme becomes a silent "network error"); `Http.kt:67,85` and `Clients.kt:224,284` (`Json.parseToJsonElement(resp.body)` on a 2xx body that is HTML/empty throws `SerializationException`, not `ApiException`, so retry logic can't classify it)
- **Why it matters:** Error-handling guides: validate before parsing, fail with typed domain errors. A `NumberFormatException` from a request action crashes the fire-and-forget thread and leaves the button stuck at "Requesting…"; masked casts make config typos look like flaky networks.
- **Fix:** `tmdbId.toIntOrNull() ?: throw ApiException("Radarr: invalid tmdbId '$tmdbId'")`; check `url.startsWith("http")` before the cast and return `Resp(0)`-with-reason or a typed `ApiException("unsupported scheme")`; wrap JSON parsing in `runCatching { … }.getOrElse { throw ApiException("Malformed JSON from $url", resp.status) }`.
- **Reference:** Kotlin error-handling guides (validate, typed exceptions, `toIntOrNull`); OWASP input validation.

### 12. MEDIUM — AlertDialogs not dismissed in `onDestroy` → leaked window / `BadTokenException`
- **File:** `RequestsActivity.kt:131` (delete confirm) and `DetailActivity.kt:173` (cancel-request confirm); `RequestsActivity.onDestroy` (`RequestsActivity.kt:78-80`) only removes the ticker; `DetailActivity` has no `onDestroy` at all
- **What:** If the activity is finished/destroyed (back, rotation, process pressure) while the dialog is showing, the dialog's window is leaked and can throw `BadTokenException`/`WindowManager$BadTokenException` on show or leak.
- **Why it matters:** Android docs explicitly require dismissing dialogs in `onDestroy` to avoid window leaks — a crash/leak class the lens calls out.
- **Fix:** Keep `private var dialog: AlertDialog? = null`, assign on `.show()`, and in `onDestroy`: `dialog?.dismiss(); dialog = null`; also guard `if (isFinishing) return` before showing.
- **Reference:** Android Activity/dialog docs ("dismiss dialogs in onDestroy to avoid window leaks"); process lifecycle docs.

### 13. LOW-MEDIUM — `showError` stacks error views and detaches the empty state on repeated failures
- **File:** `MainScreen.kt:212-241` (`showError` does `p.root.removeView(p.empty)` then adds an `"error"`-tagged view without removing a previous one; only `renderCurrent` at `MainScreen.kt:255` removes a single error view)
- **What:** Each failed load (e.g., pressing Retry repeatedly while offline) adds another error view on top; after N failures, N−1 stale error views remain even after a later success removes only one, and `p.empty` stays detached on some paths (stale-while-revalidate cache hit at `MainScreen.kt:183-184` returns early after `renderCurrent` without re-adding `empty`).
- **Why it matters:** View accumulation + stale overlay = wrong UI state after recovery; resource creep. Error-handling robustness: UI state transitions must be idempotent.
- **Fix:** At the top of `showError`: `p.root.findViewWithTag<View>("error")?.let { p.root.removeView(it) }`; re-add `p.empty` whenever the error view is removed.
- **Reference:** Error-handling/state-management guidance (idempotent state transitions); Android view-removal semantics.

### 14. LOW — Data race on `LiveRepository.audiobookIndexerIds`; unbounded per-query cache
- **File:** `LiveRepository.kt:49-58` (plain `var audiobookIndexerIds` written from ticker/magnet threads — sibling fields at lines 50-51 are `@Volatile`, this one is not), `MainScreen.kt:32` (`cache` map grows one entry per tab|query forever)
- **What:** Two concurrent `magnetsFor` calls (Main + Requests tickers, #9) can both resolve the indexer id (benign duplicate work) and the unsynchronized read/write is a JMM data race (unsafe publication). The results cache grows without bound as the user searches.
- **Why it matters:** Kotlin concurrency guidance: minimize shared mutable state, use `@Volatile`/synchronization for cross-thread visibility.
- **Fix:** Make it `@Volatile` and resolve under `synchronized(this)` (or compute once in the constructor off the main thread); bound `cache` to the most recent ~20 keys (simple LRU eviction).
- **Reference:** Kotlin concurrency/visibility docs; structured-concurrency guidance on shared mutable state.

### 15. LOW — Retry backoff uses `Thread.sleep` on the caller and there is no per-operation deadline
- **File:** `Http.kt:82-90` (`sleepQuiet(backoffMs * i)` → `Thread.sleep`), timeouts at `Http.kt:29-30` (15 s/20 s × up to 3 attempts)
- **What:** Backoff blocks whatever thread calls the transport; today all callers are background threads, but nothing enforces that — a future main-thread call would block the UI for seconds (ANR risk). Worst-case per-call latency is ~106 s, and `refreshStatuses` chains several calls sequentially with no overall deadline.
- **Why it matters:** Android ANR docs — the main thread must never block on network/sleep; coroutines best practice — use suspending `delay()` and `withTimeout` rather than `Thread.sleep`, and bound total operation time.
- **Fix:** Reduce timeouts (e.g., 8 s connect / 12 s read), add an overall `withTimeout(30s)`-style deadline per logical operation, replace `Thread.sleep` with a suspending delay when moving to coroutines, and add a debug assertion (`Looper.myLooper() == Looper.getMainLooper()` → throw) inside `realHttp`/`httpGetJsonRetrying` so main-thread network is caught even in release-style tests.
- **Reference:** Android ANR docs (main-thread blocking, StrictMode `NetworkOnMainThread`); Kotlin coroutines best practices (`delay`, `withTimeout`, never block the main thread).

---

## Top 5 changes I would make

1. **One-line fix in `Http.kt:11`**: `it.key?.equals(name, ignoreCase = true) == true` — this single NPE currently kills all magnet resolution (`magnetFromDownloadUrl`).
2. **Replace all fire-and-forget `Thread {}` + 5 s tickers with structured work**: one `CoroutineScope(SupervisorJob() + Dispatchers.IO)` per screen, cancelled in `onDestroy`, paused in `onStop`, with an in-flight guard so ticks never stack (fixes #2, #8, #9 together).
3. **Move all disk/Keystore/crypto work off the main thread** in `AppState.init`/`saveConfig` (SharedPreferences first read, `KeyStore.load`, AES-GCM) and keep StrictMode `detectAll` in debug to catch regressions (#3).
4. **Bound all resource consumption**: cap HTTP body reads and bitmap decode size with `inSampleSize` in `ImageLoader`, cap Jellyfin pagination pages, tighten the 15 s/20 s timeouts and add a per-operation deadline (#4, #10, #15).
5. **Fix cleartext + trust boundaries**: correct `network_security_config.xml` so LAN HTTP actually works while pinning public API domains to TLS-only, follow redirects manually without forwarding API keys, and validate Prowlarr-returned `downloadUrl`s before fetching (#5, #6, #7).
