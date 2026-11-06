package com.pdownloader.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import android.webkit.CookieManager
import com.pdownloader.browser.media.MediaHttp
import com.pdownloader.browser.media.RequestContext
import java.util.concurrent.Executors

class MediaImages {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private var closed = false
    private var pending = 0
    fun load(view: ImageView, url: String, context: RequestContext) {
        view.tag = url
        if (url.isBlank() || closed) return
        val key = url + context.pageUrl
        cache.get(key)?.let { view.setImageBitmap(it); return }
        if (pending >= 40) return
        pending++
        workers.execute {
            val bitmap = runCatching {
                MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG).open(url, context).use { response ->
                    MediaHttp.requireSuccess(response.status)
                    val data = response.body.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 6 * 1024 * 1024) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        require(output.size() <= 6 * 1024 * 1024)
                        output.toByteArray()
                    }
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    options.inSampleSize = 1
                    while (options.outWidth / options.inSampleSize > 1280 || options.outHeight / options.inSampleSize > 1280) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)
                }
            }.getOrNull()
            main.post {
                pending--
                if (!closed && bitmap != null) {
                    cache.put(key, bitmap)
                    if (view.tag == url) view.setImageBitmap(bitmap)
                }
            }
        }
    }
    fun close() { closed = true; workers.shutdownNow(); cache.evictAll() }
}
