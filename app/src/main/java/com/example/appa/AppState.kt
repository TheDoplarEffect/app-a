package com.example.appa

import android.content.Context

/** App-wide singletons: config, catalog/repository, persisted request store. */
object AppState {
    val catalog: Catalog = Catalog

    lateinit var store: RequestStoreCore
        private set

    var config: AppConfig = AppConfig()
        private set

    var repo: LiveRepository? = null
        private set

    /** Live mode is on when the config is complete enough to serve real data. */
    val liveMode: Boolean get() = config.normalized().liveReady

    fun init(context: Context) {
        if (::store.isInitialized) return
        val prefs = context.applicationContext.getSharedPreferences("appa", Context.MODE_PRIVATE)
        store = RequestStoreCore(
            load = { prefs.getString("requests", null) },
            save = { value -> prefs.edit().putString("requests", value).apply() }
        )
        config = SecureStore.loadConfig(context)
        if (config.normalized().liveReady) {
            repo = LiveRepository(config)
        }
    }

    fun saveConfig(context: Context, cfg: AppConfig) {
        val normalized = cfg.normalized()
        SecureStore.saveConfig(context, normalized)
        config = normalized
        repo = if (normalized.liveReady) LiveRepository(normalized) else null
    }

    fun requestFor(mediaId: String): Request? = store.byMediaId(mediaId)

    /** Resolve a media id to a live item first, then the placeholder catalog. */
    fun mediaById(id: String): Media? = repo?.mediaById(id) ?: Catalog.byId[id]
}
