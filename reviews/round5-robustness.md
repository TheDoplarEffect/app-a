# Round 5 — Robustness Review (master HEAD `cd6abab`, v4.5)

Scope: `app/src/main/java/com/example/appa/` (22 files), `AndroidManifest.xml`, `res/xml/network_security_config.xml`, `app/src/test`. Fresh review, robustness lens: crashes, races, network failure modes, persistence corruption, config edge cases. Principles applied: OWASP Mobile Top 10, defensive parsing of external JSON, atomic persistence, idempotent operations.

## Summary

The codebase is defensively written in most places (injectable `HttpTransport`, `runCatching` at network boundaries, body-size caps, manual redirect handling with sensitive-header stripping, bounded queues, corrupt-tolerant persistence reads). The real risks concentrate in **threading/lifecycle ownership** (a global worker registry cancelled by any activity; an uncaught-exception crash path in config save; an async config load that Settings can race against) and in the **SSRF guard** (DNS TOCTOU, unvalidated redirect target in magnet resolution). Persistence is corrupt-tolerant on read but async/non-durable on write.

## Findings, ranked by severity

### HIGH

**H1. Uncaught exception on the config-save thread → process crash (KeyStore failure path).**
`AppState.kt:58-59` — `Thread { SecureStore.saveConfig(app, normalized); ... }` has no try/catch. Inside, `SecureStore.saveConfig` (`SecureStore.kt:56-59`) calls `encrypt()` → `secretKey()` (`SecureStore.kt:27-38`), where `KeyStore.getInstance("AndroidKeyStore").load(null)` and `generateKey()` run **outside** any `runCatching` (unlike `loadConfig`, which wraps decrypt in `runCatching` at `SecureStore.kt:65-71`). If the keystore is unavailable or the key permanently invalidated (backup restore, key reset, `KeyStoreException`), the worker thread dies with an uncaught exception and Android's default handler kills the whole app; `onDone` never runs, so the Save button stays stuck on "Saving…".
*Fix:* wrap the whole save body in `runCatching`; on failure set `configWasWiped`/surface a Toast via `onDone(false)` and leave `state` unchanged. Add a test with a throwing transport/keystore.

**H2. Settings reads config before the async load lands → Save can silently wipe stored credentials.**
`AppState.init` (`AppState.kt:30-52`) loads the encrypted config on a worker and only posts `state` to the main thread later. `SettingsActivity.onCreate` reads `AppState.config` synchronously (`SettingsActivity.kt:52`) to prefill the form. If the user opens Settings and taps Save before the worker posts, `collect()` (`SettingsActivity.kt:108-120`) produces an all-empty `AppConfig` and `save()` (`SettingsActivity.kt:118`) overwrites the stored encrypted config with empty values — permanent loss of all API keys, with a success toast.
*Fix:* add a `@Volatile var configLoaded` latch in `AppState.init`'s posted block; in Settings, disable Save/Test (or block with a toast) until `configLoaded` is true, and re-prefill fields when the first load completes.

**H3. Global `WorkerScope.cancelAll()` from any activity kills unrelated in-flight work.**
`WorkerScope` is a process-global registry (`AppState.kt:140-161`); `DetailActivity.onDestroy` (`DetailActivity.kt:100-104`) and `MainScreen.detach` (`MainScreen.kt:163`) call `cancelAll()`, which interrupts **every** registered worker (`AppState.kt:152-157`) — including `AppState.init`'s config-load worker (`AppState.kt:41-51`), MainScreen's in-flight search (`MainScreen.kt:228-262`), and the StatusPoller's refresh (`AppState.kt:95-105`). Backing out of Detail during startup can interrupt the init worker, so the loaded config is never applied and the app silently stays in placeholder mode; a search in flight when a Detail screen is closed just never completes (no error surfaced).
*Fix:* make worker scopes per-owner (e.g. `spawn(owner, block)` and `cancelAll(owner)`; Detail owns only its own workers), or at minimum never cancel the init worker and re-post pending results. Add an `isInterrupted` check + completion callback for init.

**H4. Non-idempotent external write: `doRequest` can create duplicate *arr entries.**
`DetailActivity.kt:220-243` — the dedup check `AppState.requestFor(media.id) != null` runs on the main thread (line 224), but the actual atomic idempotency gate, `RequestStoreCore.create` (`Catalog.kt:163-170`, synchronized, returns null on duplicate), runs **after** the external side effect `AppState.repo?.request(media)` (line 232), which POSTs an add to Radarr/Sonarr. Two racing requests (e.g. from Main grid + Detail, or a retried tap) both pass the main-thread check and both hit the *arr API → duplicate library entries, while the local store only records one. The comment "check FIRST — never double-add in *arr" is not actually guaranteed.
*Fix:* reserve the request atomically first (`store.create(media.id, snapshot=…)` with `external=""`), then on the worker call `repo.request(media)` and attach the ref via `store.setExternal(...)` (`Catalog.kt:174-181`); on failure delete the reservation. That makes the external write happen at most once per mediaId.

### MEDIUM

**M1. SSRF guard is TOCTOU-vulnerable to DNS rebinding + blocking DNS on caller thread.**
`isSafeExternalUrl` (`Http.kt:53-66`) validates by resolving the host via `InetAddress.getAllByName(host)` and rejecting loopback/link-local, but `HttpURLConnection` re-resolves the hostname when it connects — an attacker-controlled DNS server can return a public IP for the check and `169.254.169.254`/loopback for the actual connection. Site-local is intentionally allowed (LAN *arr stack), which widens the impact. The lookup is also blocking on the caller's thread — safe today only because all callers are workers; nothing enforces that (StrictMode is debug-only, `MainActivity.java:12-17`), so a future main-thread call would be a silent release-build ANR (`NetworkOnMainThreadException`).
*Fix:* pin the validated `InetAddress` and connect through a custom `SocketFactory`/`HostnameVerifier` (or re-validate the connected peer), and add an explicit `Looper` guard (throw if called on main) or move validation into the transport's worker.

**M2. Redirect `Location` returned by `magnetFromDownloadUrl` is not re-validated.**
`Http.kt:135-143` — the Prowlarr `downloadUrl` is checked with `isSafeExternalUrl`, but the 3xx `Location` is only matched against `magnet:` / `.torrent` substrings (line 141); any `http(s)` URL containing ".torrent" (e.g. `http://169.254.169.254/x.torrent` or an internal host) is returned as the "resolved magnet". `DetailActivity.copyMagnet` (`DetailActivity.kt:281-289`) currently saves this because it re-checks `startsWith("magnet:")` — good defense-in-depth — but `LiveRepository.resolveMagnet` (`LiveRepository.kt:137-138`) exposes the raw value as a public API.
*Fix:* resolve `Location` against the base URL and run `isSafeExternalUrl` on it before returning; reject anything that isn't `magnet:`.

**M3. Persistence is async/non-durable on write, and first mutation after a corrupt read discards history.**
`RequestStoreCore.persist` (`Catalog.kt:250-267`) serializes under lock but writes via `persistIo.execute { save(text) }` where `save` is `prefs.edit().putString("requests", value).apply()` (`AppState.kt:36`) — a process kill between mutation and the executor run loses the change (user sees "Request submitted", it's gone after restart). On read, `reload()` (`Catalog.kt:210-215`) keeps *existing in-memory* items when parsing fails — at cold start that set is empty, so the first `create()`/`delete()` persists a list containing only the new mutation, silently overwriting the corrupt file and losing every other request.
*Fix:* use `commit()` (synchronous) for the small requests/config writes, or keep `apply()` but flush on `onStop`; on cold-start parse failure, quarantine the raw text (e.g. keep it under a `requests_corrupt` key) instead of letting the next `persist()` clobber it, and parse per-element so one bad record doesn't nuke the rest.

**M4. `wasWiped` flag is read before it can be set — the "config was reset" warning never fires.**
`AppState.kt:43-44` captures `val wiped = SecureStore.wasWiped` **before** `SecureStore.loadConfig(app)`, but it's `loadConfig` (`SecureStore.kt:66-71`) that sets `wasWiped = true` when decryption fails. The flag is therefore always stale-false on the very load that wiped, and the Settings warning (`SettingsActivity.kt:94-96`) is never shown — the user's keys vanish with no explanation.
*Fix:* read `SecureStore.wasWiped` after `loadConfig` returns (or have `loadConfig` return a result object with the wiped flag).

**M5. `WorkerScope.spawn`/`cancelAll` race + `StatusPoller.cancel()` doesn't do what its doc says.**
`AppState.kt:144-147` — `spawn` does prune → `workers.add` → `start()` non-atomically against `cancelAll`'s `synchronized(workers)` block (`AppState.kt:152-157`); a thread added just after the clear is started and never interrupted (leaked worker). Also the class doc (`AppState.kt:84`) says `cancel` "also interrupts worker threads", but `cancel() = stop()` (`AppState.kt:132`) only stops future ticks — an in-flight `refreshStatuses` keeps running; and `RequestsActivity.onDestroy` (`RequestsActivity.kt:77-81`) never cancels workers while `DetailActivity` does, so behavior is inconsistent across screens.
*Fix:* synchronize the whole spawn sequence on the same lock used by `cancelAll`; make `StatusPoller.cancel()` interrupt its in-flight worker (keep a reference to the spawned thread); give every screen the same teardown path.

**M6. `sameHost` ignores scheme/port when deciding whether to forward credentials on redirect.**
`Http.kt:42-47` compares only the host string; at `Http.kt:113` a cross-"host" check that passes keeps `Authorization`/`X-Api-Key` when the redirect changes port (e.g. `http://lanhost:8989` → `http://lanhost:9696`, a different service on the same LAN box) or scheme. The *arr/Jellyfin API key would then be sent to whatever listens on the other port.
*Fix:* compare scheme + host + port, or simply strip `SENSITIVE_HEADERS` on every redirect (safest default; same-host redirects rarely need auth re-sent since the original request already authenticated).

### LOW

**L1. Cleartext HTTP allow-listed for a hard-coded LAN IP.** `res/xml/network_security_config.xml` permits cleartext to `192.168.178.42` (+ loopback). API keys then travel unencrypted on the LAN; if DHCP reassigns that IP to another device, keys leak. NSC has no CIDR support so this is a deliberate trade-off, but the Settings hint hard-codes the same IP — consider validating the entered URL's host against the NSC allow-list and warning on cleartext.

**L2. `Config.fromJson` maps any parse failure to an empty config silently** (`Config.kt:63-73`, `getOrDefault(AppConfig())`). Combined with H2/H4 this can mask corruption; at minimum log it and distinguish "missing file" from "corrupt file".

**L3. ImageLoader: blocking DNS per redirect hop on a 2-thread pool; `view.tag` used as load token.** `ImageLoader.kt:21-24,36-38` — every poster fetch goes through `realHttp` with `followRedirects=true`, and each redirect hop runs the blocking `isSafeExternalUrl` DNS check on one of only 2 executor threads, so a slow/rebinding DNS stalls all poster loads. `view.tag` (line 29, 37, 46) doubles as the cancellation token and will misbehave if any other code tags the same ImageView. *Fix:* pre-validate poster hosts once, cache the check, use a dedicated key (`setTag(R.id.…)`) instead of the raw tag.

**L4. `AppState.init` double-init race.** `AppState.kt:31` `if (::store.isInitialized) return` is unsynchronized; two activities created in quick succession can both build a `RequestStoreCore` and spawn duplicate config-load workers. Benign in practice (same prefs lambdas) but can double-apply config. *Fix:* `@Synchronized fun init`.

**L5. `httpGetJsonRetrying` sleeps on the caller's thread** (`Http.kt:157-172`, `sleepQuiet` at 175-177). All current callers are workers, but nothing prevents a main-thread call, and release builds have no StrictMode (MainActivity.java:12-17) to catch it. *Fix:* throw if `Looper.myLooper() == Looper.getMainLooper()`, or move retry/backoff into an executor.

**L6. Encrypted config saved with async `apply()`; `wasWiped` cleared before the write lands.** `SecureStore.kt:58-59`. A kill in the window loses the just-saved config; `commit()` is cheap for this tiny write. *Fix:* `commit()` for `saveConfig`, set `wasWiped=false` only after a successful commit.

**L7. Redirect-loop edge returns the raw 3xx response.** `Http.kt:109-121` — when hops hit `MAX_REDIRECTS` or the target fails validation, the loop breaks and the 3xx `Resp` is returned; `httpGetJson` then throws `ApiException("HTTP 30x")` which is handled, but callers can't distinguish "loop detected" from a real 3xx. Minor; include hop count in the exception message.

## What's already solid (keep as-is)

- `realHttp` never throws (status-0 `Resp` on network error), caps bodies at 8 MB (`Http.kt:22,25-35`), follows redirects manually with sensitive-header stripping and per-hop re-validation, rewrites 301/302/303 to GET.
- Defensive JSON accessors (`JsonX.kt`): every field read is type-safe with defaults; `ignoreUnknownKeys` on the shared instance; `RequestStoreCore.parseAll` (`Catalog.kt:216-248`) is per-element tolerant and never wipes in-memory state on corrupt input.
- `RequestStoreCore` mutations are `synchronized` with a single-writer executor, so persisted writes are ordered; store ops are idempotent by mediaId.
- `StatusPoller` has an AtomicBoolean in-flight guard (`AppState.kt:90-118`), so slow *arr refreshes can't pile up; live-refresh failures keep previous statuses (`LiveRepository.kt:148-175` uses `runCatching` + `continue` on failure).
- `ImageLoader` has a bounded queue with `DiscardOldestPolicy`, byte-size LruCache, downsampling (`inSampleSize`), tag-based stale-result protection, and `isAttachedToWindow` checks.
- `SecureStore` uses Android Keystore AES/GCM with a random IV and wipes ciphertext on permanent key invalidation (with the flag-ordering bug noted in M4).
- Write-path *arr lookups throw instead of defaulting (`ArrClient.rootFolder/qualityProfileId`, `Clients.kt:212-222`), so a missing root folder can't produce a wrong write.
- Unit tests cover the parsing/SSRF/magnet/pagination paths with an injected fake transport (`app/src/test/…/LiveParsingTest.kt`, `RequestStoreCoreTest.kt`).
