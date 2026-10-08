package com.zynelabs.stb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * v4.9: minimal poster/logo loader — OkHttp + BitmapFactory + LruCache.
 * No new dependencies (Glide's full transitive closure isn't worth it
 * for thumbnails). The ImageView is tagged with the URL so recycled
 * views can't show a stale bitmap.
 *
 * Usage: ImageLoader.load(url, imageView) { /* show fallback tile */ }
 */
object ImageLoader {

    private const val MAG_UA =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 " +
            "(KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("User-Agent", MAG_UA)
                        .header("Referer", "http://localhost/")
                        .build()
                )
            }
            .build()
    }

    // ~8MB bitmap cache.
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Loads [url] into [view]. On success the bitmap is set, [onSuccess]
     * runs, and [onFail] is NOT called; on any failure [onFail] runs
     * (caller shows its letter-tile fallback). A blank url immediately
     * calls [onFail].
     */
    fun load(
        url: String?,
        view: ImageView,
        onFail: () -> Unit,
        onSuccess: () -> Unit = {}
    ) {
        if (url.isNullOrBlank()) {
            onFail()
            return
        }
        view.tag = url
        cache.get(url)?.let { bmp ->
            if (view.tag == url) {
                view.setImageBitmap(bmp)
                view.visibility = android.view.View.VISIBLE
            }
            return
        }
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { fetch(url) }
            if (view.tag != url) return@launch // recycled
            if (bmp != null) {
                cache.put(url, bmp)
                view.setImageBitmap(bmp)
                view.visibility = android.view.View.VISIBLE
                onSuccess()
            } else {
                onFail()
            }
        }
    }

    /** Cancels a pending load (e.g. when a view is recycled). */
    fun cancel(view: ImageView) {
        view.tag = null
    }

    private fun fetch(url: String): Bitmap? {
        return try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body?.bytes() ?: return null
                if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
                // Downsample huge posters to save memory.
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 320 &&
                    bounds.outHeight / (sample * 2) >= 320
                ) {
                    sample *= 2
                }
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            }
        } catch (e: Exception) {
            null
        }
    }
}
