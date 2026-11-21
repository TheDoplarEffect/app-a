# Round 3 — Robustness review (lens: ANR / threading / lifecycle / leaks / crash & input safety)

Scope: `/home/dev/app-a/app/src/main/java/com/example/appa/` (Kotlin + `MainActivity.java`). Judgment based solely on the current code and the external references cited per finding: Android ANR docs (developer.android.com/topic/performance/vitals/anr), Android threading/lifecycle & StrictMode docs (developer.android.com/guide/components/processes-and-threads, developer.android.com/topic/performance/strictmode, android/os/Handler docs), Kotlin coroutines best practices (kotlinlang.org/docs/coroutines-best-practices.html — structured concurrency, no unbounded parallelism, don't block threads), OWASP Mobile guidance (MASVS / Mobile Top 10: input validation, sensitive-data exposure, SSRF-style server-side request validation).

## Ranked findings

### 1. HIGH — StatusPoller's "in-flight guard" is released before the worker finishes → unbounded thread pile-up
**File:** `AppState.kt:94-110` (guard), `AppState.kt:104` (list growth), `LiveRepository.kt:142-176` (long-running work)
**What:** In `StatusPoller.runnable`, `busy.compareAndSet(false, true)` is set at tick start, but `busy.set(false)` in the `finally` runs immediately after the worker `Thread` is *started*, not when `refreshStatuses()` completes. Every 5 s tick therefore spawns a new thread regardless of whether the previous refresh is still running. The class comment ("In-flight guard so slow *arr refreshes never pile up") is not true.
**Why it matters:** One `refreshStatuses()` is up to 6+ sequential HTTP calls (Jellyfin movie scan up to 25 paged GETs, series scan, Radarr states+queue, Sonarr states+queue), each with 15 s connect / 20 s read timeouts (`Http.kt:72-73`) and 3 attempts with sleeps in `httpGetJsonRetrying` (`Http.kt:134-152`). Against a hung *arr, a single refresh can run for many minutes, while a new `Thread` is spawned every 5 s — unbounded thread creation, unbounded growth of the `workers` list, hammering of the server, and eventual `OutOfMemoryError`/jank. This is the classic unbounded-concurrency anti-pattern (coroutines best practices: use structured concurrency with a bounded/single-thread dispatcher instead of raw fire-and-forget `Thread {}`).
**Fix:** Keep `busy` held until the worker's result has been delivered: release it inside the `handler.post { ... }` in the worker (i.e., `busy.set(false)` after `onTick`), or replace the hand-rolled threads with a `Executors.newSingleThreadExecutor()` per poller and `shutdownNow()` in `cancel()`. Also remove finished threads from `workers` (see finding 10).

### 2. HIGH — Delayed `poller.start()` runnable cannot be cancelled → poller runs (and leaks the Activity) after `onDestroy`
**File:** `MainScreen.kt:135` (`handler.postDelayed({ poller.start() }, 1000)`), `MainScreen.kt:157-161` (`detach()`), `AppState.kt:117,126-132` (`removeCallbacks(runnable)` only removes the poller's own runnable)
**What:** `attach()` posts an anonymous lambda that calls `poller.start()` 1 s later. `detach()` calls `poller.cancel()` → `stop()` → `handler.removeCallbacks(runnable)`, which only removes the poller's *tick* runnable — not this anonymous start lambda. If `detach()` (or `onStop()`) happens within that 1 s window (quick back-out, fast rotation), the lambda fires afterwards, sets `running = true` and reschedules the tick loop forever. Nothing ever stops it again.
**Why it matters:** After `MainActivity.onDestroy` the poller keeps ticking every 5 s: `AppState.store.tick()` runs on the main thread, live workers keep hitting the network, and the pending/looping `Handler` messages hold `StatusPoller → MainScreen → Activity` — a static-ish handler leak (Android Handler docs: pending messages keep the Handler, and here the whole Activity, alive) plus permanent background battery/network drain. ANR/lifecycle docs: remove *all* callbacks in `onDestroy`.
**Fix:** Store the delayed runnable as a named field (`private val startRunnable = Runnable { if (attached) poller.start() }`), `removeCallbacks(startRunnable)` in `detach()`, and guard the lambda with `if (!attached) return`.

### 3. MEDIUM-HIGH — First SharedPreferences read happens on the main thread in `AppState.init`
**File:** `AppState.kt:33-38` (`store = RequestStoreCore(load = { prefs.getString("requests", null) }, ...)` constructed synchronously on main; `Catalog.kt:189-191` `init { reload() }` → `load()`)
**What:** `AppState.init()` deliberately moves Keystore/AES work off the main thread, but constructs `RequestStoreCore` on the main thread, and its `init` block calls `reload()` → `prefs.getString(...)`. The first `getSharedPreferences` access in a process loads the XML file from disk synchronously on the calling thread — i.e., main-thread disk I/O on every cold start.
**Why it matters:** StrictMode `detectAll()` is enabled in debug builds (`MainActivity.java:12-15`) and would flag exactly this (`DiskReadViolation`); on slow storage this is a real cold-start ANR contribution (ANR docs: the main thread must finish input dispatch within 5 s — disk I/O on it directly eats that budget). The code already knows the rule ("keep it off the main thread", comment at `LiveRepository.kt:46-48`) but misses the store.
**Fix:** Build the `RequestStoreCore` inside the existing background `Thread { ... }` in `init()` and assign/post `store` on the main looper together with `state` (guarding `::store.isInitialized` access with the same post), or defer the first `reload()` to a background executor.

### 4. MEDIUM — Unbounded response-body read into memory on every HTTP call (OOM risk)
**File:** `Http.kt:81` (`val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)`), used by `httpGetJson`/`httpGetJsonRetrying` (`Http.kt:119-152`) and the redirect loop (`Http.kt:89-97`)
**What:** The JSON path reads the entire response body into a `ByteArray` + `String` with no size cap. Only `ImageLoader` caps its download (`ImageLoader.kt:33`, 8 MB); API responses are uncapped.
**Why it matters:** A misconfigured/redirected service URL, a hostile LAN host, or a Prowlarr `downloadUrl` that resolves to a large file makes the app allocate the whole body on the heap → `OutOfMemoryError` crash. Unvalidated/unbounded external input (OWASP MASVS resilience: cap sizes of data received from external sources).
**Fix:** Cap the read (e.g., `stream.readBytes(MAX_BODY_BYTES)`-style bounded copy, 2–5 MB for JSON, abort/treat as error beyond), and pre-check `conn.contentLengthLong` when > 0.

### 5. MEDIUM — SSRF guard is check-then-use: DNS is resolved twice (TOCTOU / rebinding bypass)
**File:** `Http.kt:36-49` (`isSafeExternalUrl` → `InetAddress.getAllByName(host)`), `Http.kt:109-116` (`magnetFromDownloadUrl` validates, then `realHttp` connects), `Http.kt:66-70`
**What:** `isSafeExternalUrl` validates the host by resolving it, but `realHttp` then resolves the *same hostname again* when opening the connection. A DNS name that returns a public address for the check and `169.254.169.254`/loopback on the second lookup (classic DNS rebinding) bypasses the loopback/link-local/metadata block. Redirect hops re-resolve as well.
**Why it matters:** The guard exists precisely to stop the app from fetching cloud-metadata/internal addresses from an externally supplied `downloadUrl` (OWASP SSRF guidance: validate *and* pin the resolved address, or re-validate the actual peer). The current check-then-use pattern does not actually guarantee the property it claims.
**Fix:** Resolve once, validate all returned addresses, and connect to the validated `InetAddress` (custom `SocketFactory`/proxy `Host`+`InetAddress`), or at minimum re-run the address check against the connection's actual peer after connect and on every redirect hop.

### 6. MEDIUM — API-key-bearing `.torrent` download URL can be copied to the clipboard
**File:** `DetailActivity.kt:295-300` (`if (uri.startsWith("magnet:") || uri.contains(".torrent")) uri`), `Http.kt:109-116` (`magnetFromDownloadUrl` returns the raw `downloadUrl` unchanged on non-3xx or non-magnet Location)
**What:** Prowlarr `downloadUrl` values embed the indexer API key as a query parameter (e.g. `.../download/x.torrent?...apikey=SECRET`). When the indexer answers 200 (or a Location that is not `magnet:`), `magnetFromDownloadUrl` returns that URL as-is, and `copyMagnet` accepts it because it `contains(".torrent")` — copying the key-bearing URL to the clipboard, contradicting the code's own comment ("never the apikey-bearing download URL").
**Why it matters:** Sensitive-data exposure via clipboard (OWASP Mobile Top 10 / MASVS-STORAGE: secrets must not leak to shared surfaces; clipboard is readable by other apps and persists in history).
**Fix:** Only ever copy `magnet:` URIs; if a `.torrent` file is needed, download it with the authenticated client (`X-Api-Key` header) and hand out a local content URI, never the key-bearing URL.

### 7. MEDIUM — `SettingsActivity.test()` spawns an untracked thread that survives `onDestroy`
**File:** `SettingsActivity.kt:149-160` (`Thread { LiveRepository(cfg).pingAll() ... }`), `LiveRepository.kt:61-67` (sequential pings), no reference kept, no `onDestroy` cleanup
**What:** The test-connection thread is not stored, not interrupted, and `pingAll()` is strictly sequential over 5 services, each with up to 3 attempts × (15 s connect + 20 s read) + backoff — worst case many minutes. The thread's posted lambda captures `chips` (Views → Activity).
**Why it matters:** Destroying Settings while a test runs leaves the thread doing network I/O for minutes and retains the whole Activity via the captured views/handler — a lifecycle leak (Android lifecycle docs: cancel background work in `onDestroy`). The UI is guarded by `isFinishing`, but the leak is not.
**Fix:** Keep the thread (or a single-thread executor) as a field, `interrupt()`/`shutdownNow()` it in `onDestroy()`, and run the five pings concurrently (e.g., each in `runCatching` on a small pool) so a dead service fails fast instead of serializing minutes.

### 8. MEDIUM — `Thread.interrupt()` does not cancel in-flight socket I/O; workers keep the destroyed Activity alive
**File:** `DetailActivity.kt:96-97` (`workers.forEach { it.interrupt() }`), `MainScreen.kt:159-160`, timeouts at `Http.kt:72-73`, worker lambdas at `DetailActivity.kt:63-69, 222-232, 249-258, 293-305`
**What:** On destroy, workers are only `interrupt()`ed. Java's `Thread.interrupt()` does **not** unblock a thread parked in `HttpURLConnection` reads; the worker continues until the 20 s read timeout plus retries/sleeps (`httpGetJsonRetrying` sleeps survive via `sleepQuiet` re-interrupt, but each attempt still blocks up to 35 s). The worker lambdas capture `handler`/`this` (the Activity).
**Why it matters:** For up to ~1–2 minutes after `onDestroy`, the destroyed Activity is retained by the still-running worker lambda (leak) and keeps issuing network requests (battery). ANR/lifecycle guidance: cancellation must be cooperative and must actually abort the I/O.
**Fix:** Use a per-screen `ExecutorService` and `shutdownNow()` on destroy; add an `AtomicBoolean cancelled` checked between network steps in `LiveRepository`; ideally hold a reference to the active `HttpURLConnection` and call `disconnect()` on cancel (e.g., pass a `CancellationToken` into `realHttp`).

### 9. MEDIUM — ImageLoader's fixed pool has an unbounded queue; obsolete fetches are never skipped
**File:** `ImageLoader.kt:19` (`Executors.newFixedThreadPool(3)`), `ImageLoader.kt:30` (`executor.execute { ... }`), tag check only at `ImageLoader.kt:44` (after the fetch)
**What:** Every `load()` enqueues a task on an unbounded `LinkedBlockingQueue`. Fast scrolling enqueues hundreds of fetches; each holds up to 8 MB of response bytes plus a decoded bitmap while running/queued, and the fetch proceeds even when the target `ImageView` was recycled (the tag check happens only *after* the network+decode).
**Why it matters:** Memory pressure/OOM under rapid scrolling (Android performance guidance: bound work queues and cancel obsolete work); wasted network. OWASP resilience angle: unbounded accumulation of externally downloaded data.
**Fix:** Use a bounded queue (e.g., `ThreadPoolExecutor` with `ArrayBlockingQueue(N)` + `DiscardOldestPolicy`), or check `view.tag == url && view.isAttachedToWindow` *before* enqueueing and inside the task before starting the HTTP call; track in-flight URLs in a `ConcurrentHashMap.newKeySet()` to dedupe.

### 10. MEDIUM — `workers` lists grow forever (threads added, never removed on completion)
**File:** `AppState.kt:87,104` (`workers.add(th)`), `MainScreen.kt:58-62`, `DetailActivity.kt:37,101-105`
**What:** Every spawned worker — one per poller tick in live mode (every 5 s), one per debounced search, one per magnet copy — is appended to a `synchronizedList` and only cleared in `onDestroy`/`cancel()`. Finished `Thread` objects are retained for the whole session.
**Why it matters:** Unbounded memory growth over a long session (combined with finding 1, this is also the pile-up vector); classic handler/thread-leak pattern (Android docs: don't retain dead workers; prefer executors which recycle threads).
**Fix:** `workers.remove(th)` in a `finally` inside the worker (or just use an `ExecutorService` and drop the list entirely); for the poller use a single-thread executor as in finding 1.

### 11. MEDIUM — Request-store mutations (full JSON re-serialize + prefs write) run on the main thread
**File:** `AppState.kt:96` (`AppState.store.tick()` in the poller's main-thread runnable), `DetailActivity.kt:204,239` (`store.delete`/`store.create` on main in dialogs/placeholder request), `RequestsActivity.kt:138` (`store.delete` on main), `Catalog.kt:243-260` (`persist()` builds the whole JSON array under `lock`)
**What:** Every status tick that changes something, and every create/delete from a button, serializes the entire request list to JSON and calls `prefs.edit()...apply()` **on the UI thread**, while holding the store lock (blocking worker threads that call `create`/`updateStatus` from `refreshStatuses`).
**Why it matters:** Main-thread work that scales with store size (ANR docs: keep the main thread free; serialization/disk-adjacent work belongs off-main — coroutines best practices: move blocking work to a dispatcher). With a few hundred requests this is measurable jank on every 1 s placeholder tick.
**Fix:** Route all store mutations through a single dedicated executor (or at least move `persist()`'s JSON building + `apply()` off the UI path, e.g., a serial `HandlerThread`), and have the poller call `tick()` on that same executor instead of the main thread.

### 12. LOW-MEDIUM — Manual redirect handling re-sends POST bodies on 301/302/303 → duplicate *arr writes
**File:** `Http.kt:60-107` (redirect loop, lines 89-97): the same `method` + `body` are re-sent to the redirect target
**What:** On 301/302/303 the HTTP spec requires switching to GET with no body; this code re-sends the original POST (the `addSeries`/`addMovie` JSON body) to the redirect target. Cross-host redirects drop sensitive headers but still forward the body.
**Why it matters:** If a *arr answers a POST with a redirect (trailing-slash, http→https upgrade), the add request is executed twice → duplicate library entries; forwarding bodies cross-host is also a small data-leak. (RFC 9110 §15.4; OWASP input/redirect handling.)
**Fix:** On 301/302/303 switch to `GET` with `body = null`; preserve method+body only for 307/308.

### 13. LOW — Process-wide result cache grows one entry per debounced keystroke
**File:** `MainScreen.kt:296-300` (companion `cache`), `MainScreen.kt:233-247` (`loadItems` → `cache[key] = ...` per query), `Components.kt:340-357` (350 ms debounce fires per keystroke)
**What:** Cache keys are `"tab|query"` per distinct query string; each debounced intermediate query ("a", "av", "ave"…) creates an entry holding a full result list, evicted only after 5 min (`CACHE_MAX_AGE_MS`).
**Why it matters:** Unbounded-in-window memory growth while typing (each entry holds dozens of `Media` plus later bitmaps references); Android memory best practices: bound caches (LRU with max entry count).
**Fix:** Cap the map (e.g., `LinkedHashMap` LRU with max 32 entries) and only cache the settled query (debounce the cache write, or key by trimmed final query).

### 14. LOW — First `Catalog` access happens on the main thread (lazy init of 54 items + ~300 magnets with SHA-256)
**File:** `MainScreen.kt:236-241` (`Catalog.ofType(tab)` / `Catalog.search(q)` on main in placeholder mode), `DetailActivity.kt:266` (`Catalog.magnetsByMediaId[media.id]` on main in `onCreate`)
**What:** `Catalog.all`/`magnetsByMediaId` are `by lazy` and first touched from UI code on the main thread.
**Why it matters:** Small today (~ms), but it's main-thread work that grows with catalog size and directly eats the 5 s input-dispatch budget on cold start (ANR docs). Cheap to fix.
**Fix:** Pre-warm `Catalog` on the background thread in `AppState.init` (e.g., `Catalog.all.size`), or move the first placeholder load into the existing `spawnWorker`.

### 15. LOW — Unchecked `!!` on externally-shaped data paths (fragile crash points)
**File:** `DetailActivity.kt:127` (`media.posterUrl!!`), `RequestsActivity.kt:100` (`media.posterUrl!!`), `Components.kt:500` (`m.posterUrl!!`), `AuroraBackground.kt:39` (`bmp!!`), `Components.kt:356` (`pending!!`)
**What:** All are guarded by a preceding null check today, but `!!` will crash the app the moment a guard and use drift apart (e.g., `media.posterUrl` is a `val` in a data class built from JSON snapshots — `MediaSnapshot.toMedia` (`Catalog.kt:99-108`) can construct `Media` with `posterUrl = null`).
**Why it matters:** Crash-risk hygiene (Kotlin null-safety best practice: prefer local smart-cast values). Low because current call sites are safe.
**Fix:** Replace with `val url = media.posterUrl ?: run { /*gradient fallback*/ }` style locals so the compiler proves non-null at the use site.

## Top 5 changes

1. **Fix the StatusPoller guard** (`AppState.kt:94-110`): hold `busy` until the worker's result is posted (or use a single-thread executor + `shutdownNow()` in `cancel()`) — this alone removes the unbounded 5 s thread pile-up against hung *arr servers.
2. **Make the delayed `poller.start()` cancellable** (`MainScreen.kt:135,157-161`): named runnable + `removeCallbacks` in `detach()` + `attached` guard — kills the post-`onDestroy` polling loop and the Activity/Handler leak.
3. **Move `RequestStoreCore` construction (first prefs disk read) off the main thread** (`AppState.kt:33-38`) — removes the cold-start `DiskReadViolation`/ANR risk that debug StrictMode already detects.
4. **Cap HTTP response body size** (`Http.kt:81`) and **pin/validate the resolved IP for external URLs** (`Http.kt:36-49,109`) — closes the OOM crash and the DNS-rebinding hole in the SSRF guard.
5. **Make cancellation real**: per-screen executors with `shutdownNow()` + a cooperative cancelled-flag/`disconnect()` in the HTTP layer (`DetailActivity.kt:96`, `MainScreen.kt:159`, `SettingsActivity.kt:149`), remove finished threads from the `workers` lists, and only ever copy `magnet:` URIs to the clipboard (`DetailActivity.kt:298`) so the Prowlarr API key never lands on the clipboard.
