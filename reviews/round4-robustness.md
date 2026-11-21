# Round 4 — Robustness review (lens: ANR/StrictMode, threading & lifecycle, coroutines best practices, OWASP mobile)

**Scope note (path mapping):** the brief names `/home/dev/app-a`, which did not exist on this machine, so `/home/dev/app-a/reviews/` was created for this report. The project itself lives at `/srv/projects/app-a` (read-only). Its local `master` checkout is 29 commits behind `origin/master` and contains only a 16-line hello-world stub; the current code of the app (what CI builds and ships, `origin/master` @ `33ef6e2`, byte-identical copies at `/tmp/appsrc/app` and `/tmp/scratch/app`, verified with `diff -rq` against `git archive origin/master`) is the full Jellyseerr-style app described in the brief. That is what was reviewed: `app/src/main/java/com/example/appa/` (25 files, ~4,300 LOC), `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/network_security_config.xml`, `app/build.gradle`. Per the rules, nothing under any `reviews/` directory was read and nothing under `app/` was modified; this file is the only artifact written.

**Lens applied:** main-thread I/O, unbounded retries/loops/queues, thread-safety, leaks, unchecked `!!`, unvalidated input, crash risks.

Positive notes (checked, no finding): all network calls currently originate from worker threads (no `NetworkOnMainThreadException` path today); StrictMode `detectAll().penaltyLog()` is enabled in debug (`MainActivity.java:11-14`); the redirect loop in `Http.kt:84` is bounded (`MAX_REDIRECTS=5`) and re-validates every hop, dropping `Authorization`/`X-Api-Key` cross-host (`Http.kt:36,109-118`); response bodies are capped at 8 MB (`Http.kt:22`); the Jellyfin paging loop is bounded (`Clients.kt:343`); secrets are AES-GCM encrypted with a non-exportable Android Keystore key (`SecureStore.kt:24-31`) and never logged (no `Log.*` anywhere); all `!!` uses are guarded by preceding null checks or maps populated in `onCreate`.

---

## Ranked findings

### 1. HIGH — Crash on every "Test connection" tap: `WorkerScope.spawn { … }.start()` starts the thread twice
- **File:** `app/src/main/java/com/example/appa/SettingsActivity.kt:158` and `:171`
- **What:** `test()` calls `WorkerScope.spawn { … }.start()`. But `WorkerScope.spawn` already calls `th.start()` before returning the `Thread` (`AppState.kt:144-150`). Calling `Thread.start()` a second time throws `IllegalThreadStateException`.
- **Why:** The exception is thrown on the main thread (button-click handler) and uncaught → the app crashes 100% reproducibly the first time a user with a valid live config taps "Test connection". A guaranteed crash path in a shipped screen.
- **Fix:** Delete `.start()` at `SettingsActivity.kt:171`. Add a guard inside `spawn` (e.g. only start if `!th.isAlive`) so this class of bug can't recur.
- **Reference:** `java.lang.Thread.start()` javadoc — "Throws: IllegalThreadStateException – if the thread was already started" (https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html#start--).

### 2. HIGH — Image burst crashes the main thread: `ThreadPoolExecutor` with bounded queue (24) + default `AbortPolicy`
- **File:** `app/src/main/java/com/example/appa/ImageLoader.kt:21-26` (executor) and `:35` (`executor.execute`)
- **What:** The pool is `core=max=2` with `LinkedBlockingQueue(24)`. With `maximumPoolSize == corePoolSize`, no extra threads are ever created, so once 2 tasks run + 24 are queued, the next `execute()` is rejected. The default rejection policy is `AbortPolicy`, which throws `RejectedExecutionException` — and `load()` is called **on the main thread** from the `PosterCard` / `DetailActivity` / `RequestsActivity` render paths.
- **Why:** One trending page renders ~20 posters; swiping to another tab during a cold start (or opening Requests with posters) easily pushes pending loads past 26 while the 2 workers are stuck in 15 s/20 s HTTP calls. The uncaught `RejectedExecutionException` on the main thread kills the app. This is a crash risk, not just jank.
- **Fix:** Use `ThreadPoolExecutor.CallerRunsPolicy()` (or wrap `execute` in `try/catch (RejectedExecutionException)`), and/or `DiscardOldestPolicy` for stale poster requests. Also skip `execute` when `!view.isAttachedToWindow`.
- **Reference:** `java.util.concurrent.ThreadPoolExecutor` javadoc — default handler aborts with `RejectedExecutionException` (https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ThreadPoolExecutor.html); Android ANR guidance — never let UI-thread work throw/block (https://developer.android.com/topic/performance/vitals/anr).

### 3. MEDIUM — Main-thread disk I/O on cold start: `AppState.init` builds the store (and loads SharedPreferences) on the main thread
- **File:** `app/src/main/java/com/example/appa/AppState.kt:30-38` (`getSharedPreferences` + `RequestStoreCore(...)` whose `init { reload() }` calls `prefs.getString` → `Catalog.kt:209-216`)
- **What:** `init()` runs in `onCreate` (main thread). The first `getSharedPreferences("appa", …)` synchronously parses the XML from disk, and `RequestStoreCore`'s constructor immediately calls `load()` → `prefs.getString("requests", null)`. The "pre-warm off the main thread" `WorkerScope.spawn` at lines 40-51 runs *after* this synchronous read, so it cannot prevent it.
- **Why:** Blocking disk reads on the main thread are exactly what StrictMode `detectAll()` flags (`DiskReadViolation`, visible in debug builds where the app enables it) and what contributes to ANR dialogs on slow devices/first launch; the comment at lines 38-39 shows the intent was to avoid this, but the construction order defeats it.
- **Fix:** Construct `RequestStoreCore` inside the worker (pass lazy `load`/`save` lambdas, call `reload()` there) and publish `store` via the same main-thread `post` that publishes `state`, guarding `::store.isInitialized` consumers.
- **Reference:** Android StrictMode docs (https://developer.android.com/reference/android/os/StrictMode); ANR docs — "do not perform blocking I/O on the main thread" (https://developer.android.com/topic/performance/vitals/anr); threading docs (https://developer.android.com/guide/components/processes-and-threads).

### 4. MEDIUM — `store.tick()` + full-store JSON serialization run on the main thread every poller tick, under the store lock
- **File:** `app/src/main/java/com/example/appa/AppState.kt:102` and `:109` (`AppState.store.tick()` inside the main-`Handler` runnable), `app/src/main/java/com/example/appa/Catalog.kt:251-265` (`persist()` builds the whole JSON array **inside** `synchronized(lock)` on the caller thread)
- **What:** In placeholder mode the poller fires every 1 s (`TICK_PLACEHOLDER_MS`, `AppState.kt:136`); in live mode every 5 s the main thread still runs `store.tick()` inside `handler.post` (`AppState.kt:102`). `tick()` mutates under `lock` and calls `persist()`, which serializes the entire request list to JSON while holding `lock` — on the main thread.
- **Why:** Recurring main-thread CPU (whole-store JSON encode each tick) plus lock-hold time that blocks worker threads calling `create/delete/updateStatus` (`Catalog.kt:157-188`). With a large request list this is measurable jank every second — a self-inflicted ANR vector.
- **Fix:** Run `tick()` on the worker in both poller branches (single code path: spawn → `tick()` off-main → post result); in `persist()`, copy `items` under the lock, then serialize **outside** the lock and hand the string to `persistIo`.
- **Reference:** Android ANR/StrictMode docs (above); coroutines/threading best practice — keep the main thread free of CPU-heavy, lock-held work (https://developer.android.com/kotlin/coroutines/coroutines-best-practices).

### 5. MEDIUM — Unbounded worker-thread creation × 8 MB response buffers = heap exhaustion / OOM on low-end devices
- **File:** `app/src/main/java/com/example/appa/AppState.kt:144-150` (`WorkerScope.spawn` creates a new `Thread` per task, no pool, no cap; the `workers` list is only pruned on the next spawn), combined with `app/src/main/java/com/example/appa/Http.kt:22` (`MAX_BODY_BYTES = 8 MB` per response, up to 6 responses per redirect chain → up to ~48 MB transient per thread)
- **What:** Every search (debounced 350 ms, `Components.kt:363`), poller tick, details fetch and magnet resolve spawns a fresh thread. A user typing/swiping during a slow network (15 s connect + 20 s read timeouts, `Http.kt:90-91`) accumulates dozens of live threads, each able to hold multi-MB buffers.
- **Why:** `Runtime.maxMemory()` on min-spec devices is small; N threads × 8 MB bodies is a realistic `OutOfMemoryError` path. Unbounded thread creation also violates structured-concurrency guidance.
- **Fix:** Replace `WorkerScope`'s raw threads with a fixed pool (`Executors.newFixedThreadPool(4)` or a `ThreadPoolExecutor` with a small bounded queue + `DiscardOldestPolicy`); reduce `MAX_BODY_BYTES` for JSON APIs (posters are already capped separately in `ImageLoader`); keep the interrupt-all registry only for shutdown.
- **Reference:** Kotlin coroutines best practices — avoid unstructured concurrency, prefer bounded executors (https://developer.android.com/kotlin/coroutines/coroutines-best-practices); Android memory overview (https://developer.android.com/topic/performance/memory-overview).

### 6. MEDIUM — Rotation/lifecycle bug: the old `MainScreen.detach()` clobbers the *new* instance's global callback and interrupts its workers
- **File:** `app/src/main/java/com/example/appa/MainScreen.kt:159-164` (`detach()` does `WorkerScope.cancelAll(); AppState.onConfigChanged = null`), called unconditionally from `MainActivity.java:37-39` (`onDestroy`)
- **What:** On a configuration change the old activity's `onDestroy` runs **after** the new instance's `onCreate`/`attach()`. The old `detach()` then sets `AppState.onConfigChanged = null` — wiping the registration the new `MainScreen.attach()` just made (`MainScreen.kt:62`) — and `WorkerScope.cancelAll()` interrupts the new instance's in-flight workers (global registry, `AppState.kt:139-156`).
- **Why:** After every rotation, config changes (e.g. saving Settings) no longer refresh the main screen (callback is null), and in-flight loads of the new screen are interrupted. `isFinishing` is `false` during config-change destroys, so the fix is to check it.
- **Fix:** In `detach()`, only do the global teardown when `activity.isFinishing`; never null a callback you don't own — e.g. `if (AppState.onConfigChanged === thisLambda) AppState.onConfigChanged = null`. Prefer per-screen worker scopes over one global `WorkerScope` (see finding 12).
- **Reference:** Android activity lifecycle — `isFinishing()` semantics on configuration changes (https://developer.android.com/guide/components/activities/activity-lifecycle); lifecycle-aware components (https://developer.android.com/topic/libraries/architecture/lifecycle).

### 7. MEDIUM — Live status refresh has no overall deadline; 5 s poller × up to ~56 blocking HTTP calls; `interrupt()` does not cancel socket reads
- **File:** `app/src/main/java/com/example/appa/Clients.kt:341-357` (Jellyfin paging: up to 25 GETs per library call, ×2 per refresh), `app/src/main/java/com/example/appa/Http.kt:88-91` (15 s connect / 20 s read per request), `app/src/main/java/com/example/appa/AppState.kt:98-107` + `:135` (`TICK_LIVE_MS = 5_000`; `refreshStatuses` calls Jellyfin ×2 + Radarr ×2 + Sonarr ×2)
- **What:** One `refreshStatuses` can issue up to ~56 sequential blocking requests; worst case with a hung server ≈ 56 × 35 s ≈ 30 min on a single worker. The 5 s tick keeps firing (the busy-guard prevents pile-up, which is good), but `WorkerScope.cancelAll()` relies on `Thread.interrupt()`, which **does not abort a thread blocked in `HttpURLConnection` socket I/O** — the worker keeps running until its own timeout.
- **Why:** The UI shows stale statuses for many minutes after a server hangs, and the "interrupt workers on teardown" design (comments in `AppState.kt:139`, `DetailActivity.kt:101`) is ineffective for the very case it targets. No per-refresh deadline exists.
- **Fix:** Wrap each refresh in an overall deadline (`withTimeout(30_000)` under coroutines, or shorter read timeouts ~5 s); reduce Jellyfin paging (smaller pages / cache and diff); back off the poll interval after a failed refresh.
- **Reference:** `Thread.interrupt()` javadoc — interrupting a thread blocked in classic (non-`InterruptibleChannel`) I/O has no effect (https://docs.oracle.com/javase/8/docs/api/java/lang/Thread.html#interrupt--); `HttpURLConnection` timeouts (https://developer.android.com/reference/java/net/HttpURLConnection); coroutines best practices — cooperative cancellation + `withTimeout` (https://developer.android.com/kotlin/coroutines/coroutines-best-practices).

### 8. MEDIUM — Cleartext HTTP to a hard-coded LAN IP: API keys and Bearer tokens leave the device unencrypted
- **File:** `app/src/main/res/xml/network_security_config.xml:8-13` (cleartext allowed for `127.0.0.1`, `localhost`, `::1`, `192.168.178.42`), referenced from `AndroidManifest.xml:5`
- **What:** All `*arr`/Prowlarr traffic goes over plain HTTP to `192.168.178.42`, and the clients attach secrets to every request (`X-Api-Key`, `Authorization: Bearer …` — `Clients.kt:23,174,239-242`). NSC `<domain>` matches exact IP literals only (no CIDR), so the LAN host is pinned to one hard-coded address.
- **Why:** Anyone on the LAN — or any device that later receives `192.168.178.42` via DHCP reassignment — can sniff the API keys, which grant full *arr/Jellyfin control. The hard-coded IP also breaks on any other subnet (availability). OWASP MASVS-NETWORK requires TLS for sensitive data in transit.
- **Fix:** Put the LAN services behind HTTPS (reverse proxy with a LAN CA, or service-native TLS) and drop the `cleartextTrafficPermitted` entries; if HTTP must stay, document the key-sniffing risk, never log headers, and validate the configured host at runtime instead of pinning one IP.
- **Reference:** OWASP MASVS-NETWORK (TLS for sensitive traffic) (https://mas.owasp.org/MASVS/); Android network security config docs — `<domain>` matches exact hosts only, no CIDR (https://developer.android.com/privacy-and-security/security-config).

### 9. LOW — User-configured service URLs are validated by scheme prefix only; secrets are sent to any host, including cloud-metadata/loopback
- **File:** `app/src/main/java/com/example/appa/Config.kt:39-46` (`validUrl` = `startsWith("http://"|"https://")`)
- **What:** `hasSonarr/hasRadarr/hasProwlarr/hasJellyfin` accept any host. The app will attach `X-Api-Key`/`Bearer` to e.g. `https://169.254.169.254` or any internet host. This is inconsistent with `isSafeExternalUrl` (`Http.kt:53-66`), which deliberately rejects loopback/link-local/metadata for *server-supplied* URLs.
- **Why:** Unvalidated input (Settings fields) flows into authenticated network requests — a typo or a pasted "helpful" URL exfiltrates the user's API keys. Self-SSRF, hence low severity, but the guard already exists for the other URL source and should be reused.
- **Fix:** Apply the same host policy to configured base URLs (block link-local `169.254.0.0/16` and `metadata.google.internal`; allow loopback/private LAN only with the explicit LAN hint the UI already shows).
- **Reference:** OWASP MASVS-INPUT/NETWORK — validate all input that influences requests (https://mas.owasp.org/MASVS/); OWASP SSRF Prevention Cheat Sheet (https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

### 10. LOW — SSRF guard has a DNS TOCTOU hole
- **File:** `app/src/main/java/com/example/appa/Http.kt:53-66` (`isSafeExternalUrl` resolves via `InetAddress.getAllByName(host)`), used in the redirect loop at `Http.kt:111-113`
- **What:** The guard resolves the hostname once for the check, but `URL(currentUrl).openConnection()` re-resolves DNS at connect time. A rebinding DNS name can pass the check (public IP) and resolve to loopback/link-local at connect time.
- **Why:** Classic SSRF TOCTOU; impact is bounded here because these URLs are user-configured or come from the user's own Prowlarr, but the guard's stated purpose ("must not resolve to loopback/link-local/metadata") is not enforced at connect time.
- **Fix:** Resolve once, validate all returned addresses, then connect to the validated IP (with `Host` header) or re-validate the resolved address after connect.
- **Reference:** OWASP SSRF Prevention Cheat Sheet — resolve DNS once and connect to that IP; don't re-resolve (DNS rebinding/TOCTOU) (https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html).

### 11. LOW — Unbounded in-memory media cache
- **File:** `app/src/main/java/com/example/appa/LiveRepository.kt:183-186` (`mediaCache = ConcurrentHashMap`; `remember()` inserts every search/browse result)
- **What:** Every search result is inserted into a `ConcurrentHashMap` that never evicts; long sessions with many searches grow it without bound (each `Media` holds overview text, genres, poster URL).
- **Why:** Slow memory growth / eventual OOM pressure on low-end devices. Thread-safe (CHM) but unbounded.
- **Fix:** Bound it — a synchronized `LruCache` or size-capped map (e.g. 500 entries, evict oldest).
- **Reference:** `android.util.LruCache` (https://developer.android.com/reference/android/util/LruCache); Android memory overview (https://developer.android.com/topic/performance/memory-overview).

### 12. LOW — Any child screen's `onDestroy` interrupts *all* screens' workers (global `WorkerScope`)
- **File:** `app/src/main/java/com/example/appa/DetailActivity.kt:100-103` (`WorkerScope.cancelAll()` in `onDestroy`); same pattern in `MainScreen.kt:163`; registry at `AppState.kt:139-156`
- **What:** `WorkerScope` is a single process-global registry. Closing a `DetailActivity` (or rotating — finding 6) interrupts the main screen's in-flight search/poller workers, not just its own.
- **Why:** Cross-owner cancellation is a lifecycle design leak: one screen's teardown degrades another screen's in-flight work. Today the effect is limited (socket reads ignore interrupt; the poller's busy-guard recovers), but it bites as soon as workers do interruptible work.
- **Fix:** Give each screen/owner its own scope (pass a scope into `spawnWorker`, cancel only that scope in `onDestroy` guarded by `isFinishing`), or migrate to structured concurrency where cancellation is scoped automatically.
- **Reference:** Coroutines best practices — structured concurrency and scoped cancellation (https://developer.android.com/kotlin/coroutines/coroutines-best-practices); activity lifecycle (https://developer.android.com/guide/components/activities/activity-lifecycle).

### 13. LOW — Retry backoff sleeps on the caller's thread with no off-main guard
- **File:** `app/src/main/java/com/example/appa/Http.kt:169-177` (`httpGetJsonRetrying` → `sleepQuiet` → `Thread.sleep`)
- **What:** The linear backoff blocks whatever thread calls `httpGetJsonRetrying`. Today all callers are workers, but nothing enforces that — the app hand-rolls threads instead of using a dispatcher, so one future call from the UI thread stalls the main thread (and network-on-main would then throw `NetworkOnMainThreadException`).
- **Why:** With raw `Thread`s there is no structural equivalent of `Dispatchers.IO` making the off-main guarantee; this is the coroutines-best-practice gap.
- **Fix:** If coroutines are adopted, use `withContext(Dispatchers.IO)` + `delay()`; with threads, at minimum assert `Looper.myLooper() != Looper.getMainLooper()` in `realHttp`/`httpGetJsonRetrying` (and enable StrictMode `penaltyDeath` in debug) so misuse fails loudly.
- **Reference:** Coroutines best practices — right dispatcher, `delay` instead of `Thread.sleep` (https://developer.android.com/kotlin/coroutines/coroutines-best-practices); `NetworkOnMainThreadException` (https://developer.android.com/reference/android/os/NetworkOnMainThreadException); StrictMode `penaltyDeath` (https://developer.android.com/reference/android/os/StrictMode).

### 14. LOW — `allowBackup="true"` with no `dataExtractionRules`/`fullBackupContent`
- **File:** `app/src/main/AndroidManifest.xml:4`
- **What:** App data (the `appa` request prefs and the encrypted `appa_secure` blob) are included in cloud backups and device migration.
- **Why:** The config blob is safe by construction (Keystore key is non-exportable; decrypt failure wipes it — `SecureStore.kt:61-71`), but the request list leaks viewing habits into backups, and on Android ≤ 11 `adb backup` can extract it. Best practice is to opt out or exclude.
- **Fix:** Set `android:allowBackup="false"`, or keep `true` and add `res/xml/backup_rules.xml` (`fullBackupContent`) + `dataExtractionRules` excluding `appa_secure`.
- **Reference:** OWASP MASVS-PLATFORM/STORAGE (backup exposure) (https://mas.owasp.org/MASVS/); Android auto-backup docs (https://developer.android.com/identity/data/autobackup).

### 15. LOW — Release builds are not shrunk/obfuscated
- **File:** `app/build.gradle:35` (`release { signingConfig …; minifyEnabled false }`)
- **What:** No R8 shrinking/obfuscation for release APKs.
- **Why:** Not exploitable here (no secrets in code — config is Keystore-encrypted), but it is the standard MASVS-RESILIENCE baseline and costs one line now that CI builds release.
- **Fix:** `minifyEnabled true` with `proguard-android-optimize.txt` (add kotlinx-serialization keep rules if needed).
- **Reference:** OWASP MASVS-RESILIENCE (https://mas.owasp.org/MASVS/); R8 docs (https://developer.android.com/build/shrink-code).

---

## Top 5 changes

1. **`SettingsActivity.kt:171` — delete the stray `.start()`** on the `WorkerScope.spawn { … }` result. 100%-reproducible main-thread crash (`IllegalThreadStateException`) on "Test connection"; the fix is one token.
2. **`ImageLoader.kt:21-35` — make the image executor rejection-safe**: `CallerRunsPolicy()` or try/catch around `execute`, so a burst of >26 poster loads (tab swipe during cold start) can never throw `RejectedExecutionException` on the main thread.
3. **`AppState.kt` — replace raw thread-per-task `WorkerScope` with a small fixed pool + bounded queue (discard-oldest), and cut `Http.kt:22` `MAX_BODY_BYTES`**; unbounded threads × 8 MB bodies is the OOM path.
4. **Move all store/persist work off the main thread**: construct `RequestStoreCore` inside the init worker (`AppState.kt:33-38`), run `store.tick()` on the worker in *both* poller branches (`AppState.kt:102,109`), and serialize JSON outside the lock (`Catalog.kt:251-265`).
5. **Make teardown lifecycle- and owner-aware**: only `cancelAll()`/null `onConfigChanged` when `isFinishing` (`MainScreen.kt:159-164`), scope workers per screen instead of one global registry (`DetailActivity.kt:100-103`), and give the 5 s live refresh an overall deadline so a hung *arr server can't pin a worker for tens of minutes (`Clients.kt:343`, `Http.kt:88-91`).
