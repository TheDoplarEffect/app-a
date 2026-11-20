package com.example.appa

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Collections
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

    /** Called on the main thread whenever the (config, repo) pair changes (async load/save). */
    @Volatile var onConfigChanged: (() -> Unit)? = null

    val config: AppConfig get() = state.config
    val repo: LiveRepository? get() = state.repo

    /** Live mode is on when the config is complete enough to serve real data. */
    val liveMode: Boolean get() = state.config.normalized().liveReady

    fun init(context: Context) {
        if (::store.isInitialized) return
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("appa", Context.MODE_PRIVATE)
        store = RequestStoreCore(
            load = { prefs.getString("requests", null) },
            save = { value -> prefs.edit().putString("requests", value).apply() }
        )
        // Keystore load + AES-GCM decrypt can take 100ms+ — keep it off the main
        // thread (StrictMode/ANR). State starts empty and is posted when ready.
        Thread {
            val wiped = SecureStore.wasWiped
            val cfg = SecureStore.loadConfig(app)
            val st = State(cfg, RepositoryFactory.create(cfg))
            Handler(Looper.getMainLooper()).post {
                configWasWiped = wiped
                state = st
                onConfigChanged?.invoke()
            }
        }.start()
    }

    fun saveConfig(context: Context, cfg: AppConfig, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val normalized = cfg.normalized()
        configWasWiped = false
        Thread {
            SecureStore.saveConfig(app, normalized) // also clears SecureStore.wasWiped
            val st = State(normalized, RepositoryFactory.create(normalized))
            Handler(Looper.getMainLooper()).post {
                state = st
                onConfigChanged?.invoke()
                onDone?.invoke()
            }
        }.start()
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
 * refreshes never pile up; [stop] pauses while a screen is covered;
 * [cancel] also interrupts worker threads (call from onDestroy).
 */
class StatusPoller(private val onTick: (changed: Boolean, failed: Boolean) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    private val workers = Collections.synchronizedList(mutableListOf<Thread>())
    @Volatile private var running = false

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val live = AppState.liveMode
            if (!busy.compareAndSet(false, true)) { schedule(live); return }
            try {
                val changedLocal = AppState.store.tick().isNotEmpty()
                if (live) {
                    val th = Thread {
                        val res = runCatching { AppState.repo?.refreshStatuses(AppState.store) ?: emptyList() }
                        handler.post {
                            if (running) onTick(changedLocal || res.getOrDefault(emptyList()).isNotEmpty(), res.isFailure)
                        }
                    }
                    workers.add(th)
                    th.start()
                } else {
                    handler.post { if (running) onTick(changedLocal, false) }
                }
            } finally {
                busy.set(false)
            }
            schedule(live)
        }
    }

    private fun schedule(live: Boolean) {
        handler.postDelayed(runnable, if (live) TICK_LIVE_MS else TICK_PLACEHOLDER_MS)
    }

    fun start() {
        if (!running) {
            running = true
            schedule(AppState.liveMode)
        }
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }

    fun cancel() {
        stop()
        synchronized(workers) { workers.forEach { it.interrupt() }; workers.clear() }
    }

    private companion object {
        const val TICK_LIVE_MS = 5_000L
        const val TICK_PLACEHOLDER_MS = 1_000L
    }
}
