package com.example.appa

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** App-wide singletons: config, catalog/repository, persisted request store. */
object AppState {
    lateinit var store: RequestStoreCore
        private set

    /** Atomic (config, repo) pair — never observe one without the other. */
    private data class State(val config: AppConfig, val repo: LiveRepository?)
    @Volatile private var state: State = State(AppConfig(), null)

    /** Set when SecureStore had to wipe the config (key invalidated) — Settings surfaces it. */
    @Volatile var configWasWiped: Boolean = false

    /** False until the async config load has landed — Settings must not save before this (would wipe keys). */
    @Volatile var configLoaded: Boolean = false

    /** Called on the main thread whenever the (config, repo) pair changes (async load/save). */
    @Volatile var onConfigChanged: (() -> Unit)? = null

    val config: AppConfig get() = state.config
    val repo: LiveRepository? get() = state.repo

    /** Live mode is on when the config is complete enough to serve real data. */
    val liveMode: Boolean get() = state.config.normalized().liveReady

    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized && configLoaded) return // fully loaded once; a later call only finishes a pending load
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("appa", Context.MODE_PRIVATE)
        if (!::store.isInitialized) {
            store = RequestStoreCore(
                load = { prefs.getString("requests", null) },
                save = { value -> prefs.edit().putString("requests", value).commit() }, // durable: a kill must not lose requests
                onCorrupt = { raw -> prefs.edit().putString("requests_corrupt", raw).commit() }, // quarantine, never clobber
                deferLoad = true // the disk read happens on the worker below, not on the main thread
            )
        }
        // Keystore load + AES-GCM decrypt + first prefs read all happen off the main thread (StrictMode/ANR)
        WorkerScope.spawn {
            store.reload()
            Catalog.all.size // warm the placeholder catalog
            val cfg = runCatching { SecureStore.loadConfig(app) }.getOrDefault(AppConfig())
            val wiped = SecureStore.wasWiped // read AFTER loadConfig sets it, or the warning never fires
            val st = State(cfg, RepositoryFactory.create(cfg))
            Handler(Looper.getMainLooper()).post {
                configWasWiped = wiped
                configLoaded = true
                state = st
                onConfigChanged?.invoke()
            }
        }
    }

    fun saveConfig(context: Context, cfg: AppConfig, onDone: ((Boolean) -> Unit)? = null) {
        val app = context.applicationContext
        val normalized = cfg.normalized()
        configWasWiped = false
        WorkerScope.spawn { // tracked + guarded: a Keystore failure can no longer kill the process
            val ok = runCatching { SecureStore.saveConfig(app, normalized) }.isSuccess // also clears SecureStore.wasWiped
            Handler(Looper.getMainLooper()).post {
                if (ok) {
                    state = State(normalized, RepositoryFactory.create(normalized))
                    onConfigChanged?.invoke()
                }
                onDone?.invoke(ok)
            }
        }
    }

    fun requestFor(mediaId: String): Request? = store.byMediaId(mediaId)

    /** Live repo first, then placeholder catalog, then the request's own snapshot. */
    fun mediaById(id: String): Media? =
        state.repo?.mediaById(id)
            ?: Catalog.byId[id]
            ?: if (::store.isInitialized) {
                store.byMediaId(id)?.snapshot?.let { s -> MediaSnapshot.fromJson(s)?.toMedia(id) }
            } else null
}

/**
 * Shared status poller for the main grid and the requests screen (single
 * implementation instead of two tickers). In-flight guard so slow *arr
 * refreshes never pile up; [stop] pauses it while a screen is covered (onStop/onDestroy).
 */
class StatusPoller(private val onTick: (changed: Boolean, failed: Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var running = false

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val live = AppState.liveMode
            if (live) {
                // in-flight guard spans the WHOLE async refresh — slow *arr calls must not pile up
                if (!busy.compareAndSet(false, true)) { schedule(); return }
                WorkerScope.spawn {
                    try {
                        val res = runCatching { AppState.repo?.refreshStatuses(AppState.store) ?: emptyList() }
                        handler.post {
                            if (running) onTick(AppState.store.tick().isNotEmpty() || res.getOrDefault(emptyList()).isNotEmpty(), res.isFailure)
                        }
                    } finally {
                        busy.set(false)
                    }
                }
            } else {
                val changed = AppState.store.tick().isNotEmpty()
                handler.post { if (running) onTick(changed, false) }
            }
            schedule()
        }
    }

    private fun schedule() {
        handler.postDelayed(runnable, if (AppState.liveMode) TICK_LIVE_MS else TICK_PLACEHOLDER_MS)
    }

    fun start() {
        if (!running) {
            running = true
            schedule()
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }

    private companion object {
        const val TICK_LIVE_MS = 5_000L
        const val TICK_PLACEHOLDER_MS = 1_000L
    }
}

/** Shared registry for fire-and-forget worker threads: spawn + interrupt-all on teardown. */
object WorkerScope {
    private val lock = Any()
    private val workers = mutableListOf<Thread>()

    fun spawn(block: () -> Unit): Thread = synchronized(lock) {
        workers.removeAll { !it.isAlive }
        Thread(block).also { workers.add(it); it.start() }
    }

    /** Process-wide teardown ONLY — never from an Activity: it interrupts unrelated screens' workers. */
    fun cancelAll() = synchronized(lock) {
        workers.forEach { it.interrupt() }
        workers.clear()
    }
}
