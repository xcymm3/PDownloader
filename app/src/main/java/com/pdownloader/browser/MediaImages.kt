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
    private val previews = Executors.newSingleThreadExecutor()
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val durations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var closed = false
    private var pending = 0
    fun load(view: ImageView, url: String, context: RequestContext) {
        loadRemote(view, url, context, false, null)
    }
    fun loadPreview(view: ImageView, url: String, context: RequestContext, onResult: (Boolean) -> Unit) {
        loadRemote(view, url, context, true, onResult)
    }
    private fun loadRemote(view: ImageView, url: String, context: RequestContext, preview: Boolean, onResult: ((Boolean) -> Unit)?) {
        val key = url + context.pageUrl
        view.tag = key
        if (url.isBlank() || closed) { onResult?.invoke(false); return }
        cache.get(key)?.let { view.setImageBitmap(it); onResult?.invoke(true); return }
        if (!preview && pending >= 40) return
        if (!preview) pending++
        (if (preview) previews else workers).execute {
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
                if (!preview) pending--
                if (!closed && bitmap != null) {
                    cache.put(key, bitmap)
                }
                if (!closed && view.tag == key) { if (bitmap != null) view.setImageBitmap(bitmap); onResult?.invoke(bitmap != null) }
            }
        }
    }
    fun loadFile(view: ImageView, file: java.io.File, onResult: (Boolean) -> Unit) {
        val key = file.path + ":" + file.lastModified()
        view.tag = key
        if (closed) return
        cache.get(key)?.let { view.setImageBitmap(it); onResult(true); return }
        previews.execute {
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                BitmapFactory.decodeFile(file.path, options)
            }.getOrNull()
            main.post {
                if (!closed && bitmap != null) cache.put(key, bitmap)
                if (!closed && view.tag == key) { if (bitmap != null) view.setImageBitmap(bitmap); onResult(bitmap != null) }
            }
        }
    }
    fun loadDownloaded(view: ImageView, task: com.pdownloader.browser.download.DownloadTask, folder: java.io.File, duration: (Long) -> Unit) {
        val file = java.io.File(folder, task.output)
        if (task.variant.kind == com.pdownloader.browser.media.MediaKind.IMAGE) { loadFile(view, file) {}; return }
        val key = "cover:${file.path}:${file.lastModified()}"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); durations[key]?.let(duration); return }
        workers.execute {
            var length = task.durationMs
            val bitmap = runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    val hls = task.variant.kind == com.pdownloader.browser.media.MediaKind.HLS
                    if (hls && length <= 0) length = file.useLines { lines -> lines.filter { it.startsWith("#EXTINF:") }.sumOf { it.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0 }.times(1000).toLong() }
                    retriever.setDataSource(if (hls) java.io.File(folder, "part-0.bin").path else file.path)
                    if (!hls) length = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: length
                    if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 320)
                    else retriever.getFrameAtTime(0)?.let { frame ->
                        android.graphics.Bitmap.createScaledBitmap(frame, 480, 320, true).also { if (it !== frame) frame.recycle() }
                    }
                } finally { retriever.release() }
            }.getOrNull()
            if (length > 0) durations[key] = length
            main.post {
                if (!closed && view.tag == key) {
                    if (bitmap != null) { cache.put(key, bitmap); view.setImageBitmap(bitmap) }
                    else if (task.thumbnail.isNotBlank()) load(view, task.thumbnail, task.context)
                    duration(length)
                }
            }
        }
    }
    fun close() { closed = true; workers.shutdownNow(); previews.shutdownNow(); cache.evictAll() }
}
