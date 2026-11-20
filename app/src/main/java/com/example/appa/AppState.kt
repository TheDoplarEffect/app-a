package com.example.appa

import android.content.Context

/** App-wide singletons: config, catalog/repository, persisted request store. */
object AppState {
    lateinit var store: RequestStoreCore
        private set

    /** Atomic (config, repo) pair — never observe one without the other. */
    private data class State(val config: AppConfig, val repo: LiveRepository?)
    @Volatile private var state: State = State(AppConfig(), null)

    /** Set when SecureStore had to wipe the config (key invalidated) — Settings surfaces it. */
    @Volatile var configWasWiped: Boolean = false

    val config: AppConfig get() = state.config
    val repo: LiveRepository? get() = state.repo

    /** Live mode is on when the config is complete enough to serve real data. */
    val liveMode: Boolean get() = state.config.normalized().liveReady

    fun init(context: Context) {
        if (::store.isInitialized) return
        val prefs = context.applicationContext.getSharedPreferences("appa", Context.MODE_PRIVATE)
        store = RequestStoreCore(
            load = { prefs.getString("requests", null) },
            save = { value -> prefs.edit().putString("requests", value).apply() }
        )
        configWasWiped = SecureStore.wasWiped
        val cfg = SecureStore.loadConfig(context)
        state = State(cfg, if (cfg.normalized().liveReady) LiveRepository(cfg) else null)
    }

    fun saveConfig(context: Context, cfg: AppConfig) {
        val normalized = cfg.normalized()
        SecureStore.saveConfig(context, normalized)
        state = State(normalized, if (normalized.liveReady) LiveRepository(normalized) else null)
    }

    fun requestFor(mediaId: String): Request? = store.byMediaId(mediaId)

    /** Resolve a media id to a live item first, then the placeholder catalog. */
    fun mediaById(id: String): Media? = state.repo?.mediaById(id) ?: Catalog.byId[id]
}
