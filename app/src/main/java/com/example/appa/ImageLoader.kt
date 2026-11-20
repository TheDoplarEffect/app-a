package com.example.appa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.Executors

/** Tiny async image loader (remote posters) with an in-memory LruCache. */
object ImageLoader {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String, view: ImageView) {
        // cache hit: set synchronously, zero flicker on re-renders
        cache.get(url)?.let { view.setImageBitmap(it); view.tag = url; return }
        if (view.tag == url && view.drawable != null) return // already loading/showing this url
        view.tag = url
        // neutral placeholder while loading (no white flash)
        view.setImageDrawable(null)
        view.setBackgroundColor(AppTheme.current.colors.surfaceGlassStrong)
        executor.execute {
            val bmp = runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) cache.put(url, bmp)
            main.post {
                if (view.tag == url) {
                    if (bmp != null) {
                        view.setImageBitmap(bmp)
                        view.background = null
                    } else {
                        view.setBackgroundColor(AppTheme.current.colors.surfaceGlassStrong)
                    }
                }
            }
        }
    }
}
