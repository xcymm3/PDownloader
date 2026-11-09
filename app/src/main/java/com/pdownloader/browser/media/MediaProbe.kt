package com.pdownloader.browser.media

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.pdownloader.browser.BuildConfig
import org.json.JSONObject
import org.json.JSONTokener
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

class ProbeSession(val tabId: String, val pageUrl: String, val userAgent: String, var title: String) {
    val token: String = UUID.randomUUID().toString()
    val groups = linkedMapOf<String, MediaGroup>()
    val seenRequests = mutableSetOf<String>()
    val deferred = linkedMapOf<String, Pair<RequestContext, Boolean>>()
    internal val observed = linkedMapOf<String, ProbeRequest>()
    val relationships = mutableMapOf<String, String>()
    val requestContexts = mutableMapOf<String, RequestContext>()
    val hlsHints = mutableSetOf<String>()
    val frames = linkedSetOf<String>()
    val queue = ArrayDeque<Pair<String, Int>>()
    @Volatile var alive = true
    var pending = 0
    var opaqueChecks = 0
    var frameCount = 0
    var message = "尚未发现资源，可先播放视频"
    var startedAt = System.currentTimeMillis()
    val unsupported: Boolean get() = runCatching { URI(pageUrl).host.lowercase().let { it == "youtube.com" || it.endsWith(".youtube.com") || it == "youtu.be" } }.getOrDefault(false)
}

internal data class ProbeRequest(val referer: String, val authorization: String, val shared: Boolean)

/** All mutable session/UI state belongs to main. Worker replies retain the exact navigation object. */
class MediaProbe(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val http = MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG, timeoutMs = 6000)
    private val script = context.assets.open("media-scan.js").bufferedReader().use { it.readText() }
    @Volatile private var active: Pair<ProbeSession, WebView>? = null
    private var helper: WebView? = null
    private var helperSession: ProbeSession? = null
    private var helperDepth = 0
    private var helperTimer: Runnable? = null
    @Volatile private var closed = false
    private var polling = false
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            active?.let { (session, web) ->
                if (session.alive) {
                    scan(session, web, 0)
                    helper?.takeIf { helperSession === session }?.let { scan(session, it, helperDepth) }
                }
            }
            if (polling) main.postDelayed(this, 1200)
        }
    }
    init {
        android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val session = active?.first ?: return null
                observe(session, request, shared = true)
                return null
            }
        })
    }

    fun begin(tabId: String, url: String, ua: String, title: String): ProbeSession = ProbeSession(tabId, url, ua, title).also {
        if (it.unsupported) it.message = "暂不支持 YouTube 资源下载"
    }
    fun attach(session: ProbeSession?, web: WebView?) {
        if (active?.first !== session) stopHelper()
        active = if (session != null && web != null && session.alive) session to web else null
        main.removeCallbacks(poll)
        if (polling && active != null) main.post(poll)
        changed()
    }
    fun resume() { polling = true; main.removeCallbacks(poll); if (active != null) main.post(poll) }
    fun pause() { polling = false; main.removeCallbacks(poll); stopHelper() }
    fun retire(session: ProbeSession?) {
        session?.alive = false
        if (helperSession === session) stopHelper()
        if (active?.first === session) active = null
    }
    fun close() { closed = true; pause(); workers.shutdownNow(); android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(null) }

    fun observe(session: ProbeSession?, request: WebResourceRequest, shared: Boolean = false) {
        if (session?.alive != true || request.method != "GET" || request.isForMainFrame) return
        val url = request.url.toString()
        if (!webUrl(url) || ignoredResource(url)) return
        val headers = request.requestHeaders
        val observation = ProbeRequest(headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value.orEmpty(),
            headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value.orEmpty(), shared)
        main.post {
            if (!session.alive || closed) return@post
            if (url !in session.observed && session.observed.size >= 120) session.observed.remove(session.observed.keys.first())
            val previous = session.observed[url]
            if (previous == null || previous.shared && !observation.shared ||
                previous.authorization.isEmpty() && observation.authorization.isNotEmpty() && previous.shared == observation.shared) {
                session.observed[url] = observation
            }
            attribute(session, url)
        }
    }

    /** An origin-only or shared-worker observation needs corroboration from this navigation's DOM or performance entries. */
    private fun attribute(session: ProbeSession, url: String, document: String? = null): RequestContext? {
        val request = session.observed[url]
        val ref = request?.referer.orEmpty().substringBefore('#')
        val documents = sequenceOf(session.pageUrl).plus(session.frames.asSequence())
        val exact = ref.isNotEmpty() && documents.any { it.substringBefore('#') == ref }
        val originOnly = webUrl(ref) && URI(ref).let { it.rawPath.orEmpty() in setOf("", "/") && it.rawQuery == null }
        val knownDocument = document?.takeIf { webUrl(it) &&
            (it.substringBefore('#') == session.pageUrl.substringBefore('#') || session.frames.any { frame -> frame.substringBefore('#') == it.substringBefore('#') }) }
        val source = when {
            knownDocument != null -> knownDocument
            exact && !originOnly && request?.shared == false -> ref
            else -> return null
        }
        val compatible = request != null && (ref.isEmpty() || ref == source.substringBefore('#') || originOnly && origin(ref) == origin(source))
        val ctx = if (compatible && request != null) RequestContext(session.pageUrl, session.userAgent, ref.ifEmpty { source }, origin(url), request.authorization)
            else RequestContext(session.pageUrl, session.userAgent, source)
        if (session.requestContexts.size < 120 || url in session.requestContexts) session.requestContexts[url] = ctx
        if (knownDocument == null) candidate(session, url, ctx)
        return ctx
    }

    fun observeUrl(session: ProbeSession?, url: String) {
        if (session?.alive == true && url in session.relationships) {
            candidate(session, url, session.requestContexts[url] ?: RequestContext(session.pageUrl, session.userAgent), allowOpaque = true)
        }
    }
    fun direct(session: ProbeSession, url: String, mime: String, size: Long, kind: MediaKind = downloadKind(url, mime)) {
        if (!session.alive || !webUrl(url) || session.unsupported || kind == MediaKind.OTHER) return
        if (kind == MediaKind.HLS) {
            if (session.hlsHints.size < 60) session.hlsHints.add(url)
            candidate(session, url, RequestContext(session.pageUrl, session.userAgent))
        }
        else merge(session, "direct:$url", session.title, listOf(MediaVariant(url, kind, size = size, mime = mime)), RequestContext(session.pageUrl, session.userAgent))
    }

    private fun scan(session: ProbeSession, web: WebView, depth: Int) {
        if (!session.alive || session.unsupported || closed) return
        web.evaluateJavascript(script) { raw ->
            if (!session.alive || closed || depth > 0 && helper !== web || raw.length > 512 * 1024) return@evaluateJavascript
            runCatching {
                val text = JSONTokener(raw).nextValue() as? String ?: return@runCatching
                val root = JSONObject(text)
                val page = root.optString("url")
                if (depth == 0 && page.substringBefore('#') != session.pageUrl.substringBefore('#')) {
                    // SPA identity is handled by the owner as a fresh navigation, never merged here.
                    return@runCatching
                }
                root.optJSONArray("frames")?.let { frames -> repeat(minOf(frames.length(), 8)) {
                    val url = frames.optString(it)
                    if (depth < 2 && webUrl(url) && url != session.pageUrl && session.frames.size < 12 && session.frames.add(url)) session.queue.add(url to (depth + 1))
                } }
                val ctx = RequestContext(session.pageUrl, session.userAgent, if (webUrl(page)) page else session.pageUrl)
                root.optJSONArray("videos")?.let { videos -> repeat(minOf(videos.length(), 40)) { index ->
                    val video = videos.optJSONObject(index) ?: return@repeat
                    val variants = video.optJSONArray("variants") ?: return@repeat
                    val values = mutableListOf<MediaVariant>()
                    val videoKey = video.optString("id")
                    val videoPage = video.optString("page", page)
                    repeat(minOf(variants.length(), 12)) { v ->
                        val variant = variants.optJSONObject(v) ?: return@repeat
                        val url = variant.optString("url")
                        if (webUrl(url)) {
                            if (session.relationships.size < 240) session.relationships[url] = videoKey
                            val requestContext = attribute(session, url, videoPage) ?: ctx
                            val mime = variant.optString("mime")
                            if (URI(url).path.endsWith(".m3u8", true) || mime.contains("mpegurl", true)) {
                                if (session.hlsHints.size < 60) session.hlsHints.add(url)
                                candidate(session, url, requestContext)
                            }
                            else if (mime.startsWith("video/") || VIDEO_SUFFIX.containsMatchIn(URI(url).path.orEmpty())) {
                                values.add(MediaVariant(url, MediaKind.VIDEO, variant.optString("quality").ifBlank { "原始版本" }, mime = mime, requestContext = requestContext))
                            } else candidate(session, url, requestContext, allowOpaque = true)
                        }
                    }
                    if (values.isNotEmpty()) merge(session, video.optString("id"), video.optString("title"), values, ctx, video.optString("thumbnail"), video.optLong("duration", -1))
                } }
                root.optJSONArray("images")?.let { images -> repeat(minOf(images.length(), 200)) { index ->
                    val image = images.optJSONObject(index) ?: return@repeat
                    val url = image.optString("url")
                    if (webUrl(url)) merge(session, "image:$url", image.optString("title"), listOf(MediaVariant(url, MediaKind.IMAGE, quality = if (image.optInt("width") > 0) "${image.optInt("width")} × ${image.optInt("height")}" else "图片")), ctx, url)
                } }
                root.optJSONArray("requests")?.let { requests -> repeat(minOf(requests.length(), 120)) {
                    val item = requests.optJSONObject(it) ?: return@repeat
                    val url = item.optString("url")
                    if (!webUrl(url)) return@repeat
                    val source = attribute(session, url, item.optString("page")) ?: return@repeat
                    val video = item.optString("video")
                    if (video.isNotEmpty() && session.relationships.size < 240) session.relationships.putIfAbsent(url, video)
                    candidate(session, url, source, allowOpaque = true)
                } }
                session.observed.keys.forEach { url -> if (url !in session.seenRequests && url !in session.deferred) attribute(session, url) }
                if (session.groups.isEmpty() && System.currentTimeMillis() - session.startedAt > 15000) session.message = "尚未发现资源，请先播放视频或重新检测"
                changed(); pump(session)
            }
        }
    }

    private fun merge(session: ProbeSession, key: String, title: String, variants: List<MediaVariant>, ctx: RequestContext, thumbnail: String = "", duration: Long = -1) {
        if (!session.alive || session.groups.size >= 240) return
        val ids = variants.map { it.id }.toSet()
        val matching = session.groups.values.filter { it.variants.any { variant -> variant.id in ids } }
        val group = matching.firstOrNull() ?: session.groups.getOrPut(stableId(key)) { MediaGroup(stableId(key), title.ifBlank { session.title }.take(200), variants.first().kind == MediaKind.IMAGE, context = ctx) }
        matching.drop(1).forEach { other -> other.variants.forEach { if (group.variants.none { old -> old.id == it.id }) group.variants.add(it) }; session.groups.remove(other.id) }
        variants.forEach { variant ->
            val variantContext = variant.requestContext ?: ctx
            val old = group.variants.indexOfFirst { it.id == variant.id }
            if (old < 0) group.variants.add(variant.copy(requestContext = variantContext)) else {
                val previous = group.variants[old]
                val priorContext = previous.requestContext
                val richerContext = if (variantContext.authorization.isNotBlank() || priorContext == null || priorContext.authorization.isBlank() && (variantContext.credentialOrigin.isNotBlank() || priorContext.credentialOrigin.isBlank())) variantContext else priorContext
                group.variants[old] = previous.copy(
                    quality = if (variant.height > previous.height || previous.quality in setOf("原始版本", "HLS 点播", "")) variant.quality else previous.quality,
                    height = maxOf(previous.height, variant.height), size = if (variant.size >= 0) variant.size else previous.size,
                    mime = variant.mime.ifBlank { previous.mime }, blockedReason = variant.blockedReason.ifBlank { previous.blockedReason }, requestContext = richerContext)
            }
        }
        if (thumbnail.isNotBlank() && webUrl(thumbnail)) group.thumbnail = thumbnail
        if (duration >= 0) group.durationMs = duration
        if (ctx.authorization.isNotBlank() || group.context.authorization.isBlank() && (ctx.credentialOrigin.isNotBlank() || group.context.credentialOrigin.isBlank())) group.context = ctx
        changed()
    }

    private fun candidate(session: ProbeSession, url: String, ctx: RequestContext, allowOpaque: Boolean = false) {
        if (closed || !session.alive || session.unsupported || !webUrl(url) || session.seenRequests.size >= 60) return
        val path = URI(url).path.orEmpty().lowercase()
        val hls = path.endsWith(".m3u8") || url in session.hlsHints
        val video = VIDEO_SUFFIX.containsMatchIn(path)
        val opaque = !hls && !video && !MEDIA_HINT.containsMatchIn(path)
        if (!hls && !video && (ignoredResource(url) || opaque && (!allowOpaque || session.opaqueChecks >= 12))) return
        if (url in session.seenRequests) return
        if (session.pending >= 4) {
            if (session.deferred.size < 32) {
                val previous = session.deferred[url]
                if (previous == null || allowOpaque && !previous.second || ctx.authorization.isNotEmpty() && previous.first.authorization.isEmpty()) {
                    session.deferred[url] = ctx to allowOpaque
                }
            }
            return
        }
        session.seenRequests.add(url)
        if (opaque) session.opaqueChecks++
        session.pending++
        workers.execute {
            if (!session.alive || closed) { main.post { session.pending-- }; return@execute }
            val result = runCatching {
                if (hls) hlsVariants(url, ctx) else confirm(url, ctx, video)
            }
            main.post {
                session.pending--
                if (!session.alive || closed) return@post
                result.onSuccess { variants -> if (variants.isNotEmpty()) merge(session, session.relationships[url] ?: "network:$url", session.title, variants, ctx) }
                    .onFailure { session.message = "部分候选验证失败，可播放后重新检测"; changed() }
                while (session.pending < 4 && session.deferred.isNotEmpty()) {
                    val (nextUrl, data) = session.deferred.entries.first()
                    session.deferred.remove(nextUrl)
                    candidate(session, nextUrl, data.first, data.second)
                }
            }
        }
    }

    private fun confirm(url: String, ctx: RequestContext, videoSuffix: Boolean): List<MediaVariant> {
        http.open(url, ctx, "HEAD").use { head ->
            if (head.status !in setOf(403, 405, 501)) MediaHttp.requireSuccess(head.status)
            if (head.status in 200..299 && head.mime.contains("mpegurl")) return hlsVariants(url, ctx)
            if (head.status in 200..299 && videoSuffix && head.mime.startsWith("video/")) return listOf(videoVariant(url, head))
        }
        val sample = http.open(url, ctx, headers = mapOf("Range" to "bytes=0-2047")).use { response ->
            MediaHttp.requireSuccess(response.status)
            val prefix = response.body.use { input ->
                val buffer = ByteArray(2048)
                var size = 0
                while (size < buffer.size) {
                    val read = input.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                buffer.decodeToString(0, size)
            }
            val playlist = response.mime.contains("mpegurl") || prefix.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")
            val variant = if (response.mime.startsWith("video/") && (videoSuffix || response.mime != "video/mp2t") ||
                videoSuffix && response.mime == "application/octet-stream") videoVariant(url, response) else null
            playlist to variant
        }
        return if (sample.first) hlsVariants(url, ctx) else listOfNotNull(sample.second)
    }

    private fun videoVariant(url: String, response: MediaHttp.Response) = MediaVariant(url, MediaKind.VIDEO,
        size = if (response.status == 206) response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1 else response.length,
        mime = response.mime)

    private fun hlsVariants(url: String, ctx: RequestContext): List<MediaVariant> {
        val (finalUrl, text) = http.text(url, ctx)
        require(text.lineSequence().any { it.trimStart().startsWith("#EXT-X-") }) { "地址没有返回 HLS 播放清单" }
        val parsed = HlsPlaylist.parse(text, finalUrl)
        return parsed.variants.ifEmpty { listOf(MediaVariant(finalUrl, MediaKind.HLS, "HLS 点播", blockedReason = parsed.blocked)) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun pump(session: ProbeSession) {
        if (helper != null || !polling || active?.first !== session || session.queue.isEmpty() || session.frameCount >= 6 || closed) return
        val (url, depth) = session.queue.removeFirst()
        session.frameCount++
        helperSession = session
        helperDepth = depth
        helper = WebView(context).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.userAgentString = session.userAgent
            settings.mediaPlaybackRequiresUserGesture = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.blockNetworkImage = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) { if (webUrl(url) && session.frames.size < 12) session.frames.add(url) }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !webUrl(request.url.toString())
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    observe(session, request); return null
                }
                override fun onPageFinished(view: WebView, url: String) {
                    view.evaluateJavascript("document.querySelectorAll('video,audio').forEach(v=>{v.muted=true;v.pause()})", null)
                    if (helper === view) scan(session, view, depth)
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    main.post { if (helper === view) stopHelper() }; return true
                }
            }
            loadUrl(url, mapOf("Referer" to session.pageUrl))
        }
        helperTimer = Runnable { stopHelper(); if (session.alive) pump(session) }.also { main.postDelayed(it, 10000) }
    }
    private fun stopHelper() {
        helperTimer?.let(main::removeCallbacks); helperTimer = null
        helper?.apply { stopLoading(); onPause(); webViewClient = WebViewClient(); destroy() }
        helper = null; helperSession = null
    }

    private companion object {
        val VIDEO_SUFFIX = Regex("\\.(mp4|webm|mov|mkv|m4v)$", RegexOption.IGNORE_CASE)
        val MEDIA_HINT = Regex("video|play|stream|download|manifest|playlist")
        val STATIC_SUFFIX = Regex("\\.(js|css|ts|m4s|woff2?|png|jpe?g|gif|svg|ico|webp|map)$", RegexOption.IGNORE_CASE)
        fun ignoredResource(url: String) = STATIC_SUFFIX.containsMatchIn(URI(url).path.orEmpty())
    }
}
