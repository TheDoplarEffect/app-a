package com.example.appa

import android.content.Context

/** App-wide singletons: catalog + persisted request store. */
object AppState {
    val catalog: Catalog = Catalog

    lateinit var store: RequestStoreCore
        private set

    fun init(context: Context) {
        if (::store.isInitialized) return
        val prefs = context.applicationContext.getSharedPreferences("appa", Context.MODE_PRIVATE)
        store = RequestStoreCore(
            load = { prefs.getString("requests", null) },
            save = { value -> prefs.edit().putString("requests", value).apply() }
        )
    }

    fun requestFor(mediaId: String): Request? = store.byMediaId(mediaId)
}
