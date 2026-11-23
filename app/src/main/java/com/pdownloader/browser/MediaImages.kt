package com.pdownloader.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.File
import java.io.RandomAccessFile
import android.webkit.CookieManager
import com.pdownloader.browser.media.MediaHttp
import com.pdownloader.browser.media.RequestContext
import java.util.concurrent.Executors

class MediaImages {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val previews = Executors.newSingleThreadExecutor()
    private val localCovers = Executors.newFixedThreadPool(2)
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
        loadUri(view, Uri.fromFile(file), onResult)
    }
    fun loadUri(view: ImageView, uri: Uri, onResult: (Boolean) -> Unit) {
        val resolver = view.context.applicationContext.contentResolver
        val key = uri.toString()
        view.tag = key
        if (closed) return
        cache.get(key)?.let { view.setImageBitmap(it); onResult(true); return }
        previews.execute {
            val bitmap = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                options.inSampleSize = 1
                while (options.outWidth / options.inSampleSize > 2048 || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2
                options.inJustDecodeBounds = false
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }.getOrNull()
            main.post {
                if (!closed && bitmap != null) cache.put(key, bitmap)
                if (!closed && view.tag == key) { if (bitmap != null) view.setImageBitmap(bitmap); onResult(bitmap != null) }
            }
        }
    }
    fun loadDownloaded(view: ImageView, task: com.pdownloader.browser.download.DownloadTask, folder: java.io.File, duration: (Long) -> Unit) {
        val file = java.io.File(folder, task.output)
        val context = view.context.applicationContext
        val uri = com.pdownloader.browser.download.DownloadRepository.get(context).playbackUri(task)
        if (task.variant.kind == com.pdownloader.browser.media.MediaKind.IMAGE) { loadUri(view, uri) {}; return }
        val key = "cover:$uri"
        view.tag = key
        cache.get(key)?.let { view.setImageBitmap(it); durations[key]?.let(duration); return }
        localCovers.execute {
            var length = task.durationMs
            val bitmap = runCatching {
                if (task.isHlsBundle) {
                    val segments = playlistSegments(file)
                    if (length <= 0) length = segments.sumOf { it.durationMs }
                    val probes = listOf(segments.size / 10, segments.size / 4, 1, 0)
                    probes.distinct().firstNotNullOfOrNull { index ->
                        val segment = segments.getOrNull(index) ?: return@firstNotNullOfOrNull null
                        runCatching {
                            if (segment.init == null) extractCover({ it.setDataSource(segment.file.path) }) { segment.durationMs }
                            else FragmentCoverSource(segment.init, segment.file).use { source ->
                                extractCover({ it.setDataSource(source) }) { segment.durationMs }
                            }
                        }.getOrNull()
                    }
                } else extractCover({ it.setDataSource(context, uri) }) { retriever ->
                    length = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: length
                    length
                }
            }.getOrNull()
            if (length > 0) durations[key] = length
            main.post {
                if (!closed && bitmap != null) cache.put(key, bitmap)
                if (!closed && view.tag == key) {
                    if (bitmap != null) view.setImageBitmap(bitmap)
                    else if (task.thumbnail.isNotBlank()) load(view, task.thumbnail, task.context)
                    duration(length)
                }
            }
        }
    }

    private fun extractCover(open: (MediaMetadataRetriever) -> Unit, duration: (MediaMetadataRetriever) -> Long): Bitmap? =
        MediaMetadataRetriever().let { retriever ->
            try {
                open(retriever)
                val length = duration(retriever)
                val moments = if (length > 0) listOf(minOf(length / 10, 5_000), minOf(length / 4, 15_000),
                    minOf(length / 2, 45_000), 0L) else listOf(3_000L, 0L)
                moments.distinct().firstNotNullOfOrNull { ms ->
                    val frame = runCatching {
                        val option = if (ms == 0L) MediaMetadataRetriever.OPTION_CLOSEST_SYNC else MediaMetadataRetriever.OPTION_CLOSEST
                        if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(ms * 1000, option, 480, 320)
                        else retriever.getFrameAtTime(ms * 1000, option)?.let { original ->
                            Bitmap.createScaledBitmap(original, 480, 320, true).also { if (it !== original) original.recycle() }
                        }
                    }.getOrNull()
                    frame?.takeIf(::hasVisibleDetail).also { if (frame != null && it == null) frame.recycle() }
                }
            } finally { retriever.release() }
        }

    private fun hasVisibleDetail(frame: Bitmap): Boolean {
        val histogram = IntArray(16)
        for (y in 0 until 12) for (x in 0 until 16) {
            val pixel = frame.getPixel((2 * x + 1) * frame.width / 32, (2 * y + 1) * frame.height / 24)
            val light = (3 * android.graphics.Color.red(pixel) + 6 * android.graphics.Color.green(pixel) +
                android.graphics.Color.blue(pixel)) / 10
            histogram[light / 16]++
        }
        return (0 until 16).all { bin -> histogram[bin] + histogram.getOrElse(bin + 1) { 0 } < 182 }
    }

    private data class CoverSegment(val file: File, val init: File?, val durationMs: Long)

    private fun playlistSegments(playlist: File): List<CoverSegment> {
        val folder = playlist.parentFile
        val localPart = Regex("part-[0-9]+\\.bin")
        var init: File? = null
        var duration = 0L
        return buildList {
            playlist.forEachLine { line ->
                when {
                    line.startsWith("#EXT-X-MAP:") -> {
                        val name = com.pdownloader.browser.media.HlsPlaylist.attributes(line)["URI"]
                        init = name?.takeIf(localPart::matches)?.let { File(folder, it) }
                    }
                    line.startsWith("#EXTINF:") ->
                        duration = ((line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0) * 1000).toLong()
                    localPart.matches(line) -> add(CoverSegment(File(folder, line), init, duration))
                }
            }
        }
    }

    private class FragmentCoverSource(init: File, segment: File) : MediaDataSource() {
        private val header = RandomAccessFile(init, "r")
        private val media = try { RandomAccessFile(segment, "r") }
            catch (error: Exception) { header.close(); throw error }
        private val boundary = header.length()
        private val length = boundary + media.length()
        override fun getSize() = length
        @Synchronized override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size == 0) return 0
            if (position >= length) return -1
            if (position >= boundary) {
                media.seek(position - boundary)
                return media.read(buffer, offset, size)
            }
            header.seek(position)
            val read = header.read(buffer, offset, minOf(size.toLong(), boundary - position).toInt())
            if (read <= 0 || read == size || position + read < boundary) return read
            media.seek(0)
            return read + media.read(buffer, offset + read, size - read).coerceAtLeast(0)
        }
        @Synchronized override fun close() { try { header.close() } finally { media.close() } }
    }

    fun close() { closed = true; workers.shutdownNow(); previews.shutdownNow(); localCovers.shutdownNow(); cache.evictAll() }
}
