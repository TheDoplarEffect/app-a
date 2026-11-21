package com.example.appa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Tiny async image loader (remote posters) with an in-memory LruCache. */
object ImageLoader {
    private val cache: LruCache<String, Bitmap> = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val executor = ThreadPoolExecutor(
        2, 2, 30, TimeUnit.SECONDS,
        LinkedBlockingQueue(24) // bounded: a burst of posters can't queue unbounded work
    )
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
            if (view.tag != url) return@execute // obsolete before we even hit the network
            val bmp = runCatching {
                val resp = realHttp("GET", url, emptyMap(), null, true)
                if (!resp.ok || resp.bytes.size > MAX_IMAGE_BYTES) return@runCatching null
                // downsample large images instead of decoding full-resolution bitmaps (OOM guard)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(resp.bytes, 0, resp.bytes.size, bounds)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_PX)
                }
                BitmapFactory.decodeByteArray(resp.bytes, 0, resp.bytes.size, opts)
            }.getOrNull()
            if (bmp != null) cache.put(url, bmp)
            main.post {
                if (view.tag == url && view.isAttachedToWindow) {
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

    /** Cap download size (bytes) and decoded pixels to bound memory. */
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_DECODE_PX = 1600

    private fun calculateSampleSize(w: Int, h: Int, maxPx: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var sample = 1
        while (w / sample > maxPx || h / sample > maxPx) sample *= 2
        return sample
    }
}
