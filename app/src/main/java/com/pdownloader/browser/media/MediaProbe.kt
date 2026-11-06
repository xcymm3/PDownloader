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
    val relationships = mutableMapOf<String, String>()
    val requestContexts = mutableMapOf<String, RequestContext>()
    val hlsHints = mutableSetOf<String>()
    val frames = linkedSetOf<String>()
    val queue = ArrayDeque<Pair<String, Int>>()
    @Volatile var alive = true
    var pending = 0
    var frameCount = 0
    var message = "尚未发现资源，可先播放视频"
    var startedAt = System.currentTimeMillis()
    val unsupported: Boolean get() = runCatching { URI(pageUrl).host.lowercase().let { it == "youtube.com" || it.endsWith(".youtube.com") || it == "youtu.be" } }.getOrDefault(false)
}

/** All mutable session/UI state belongs to main. Worker replies retain the exact navigation object. */
class MediaProbe(private val context: Context, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private val http = MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG)
    private val script = context.assets.open("media-scan.js").bufferedReader().use { it.readText() }
    @Volatile private var active: Pair<ProbeSession, WebView>? = null
    private var helper: WebView? = null
    private var helperSession: ProbeSession? = null
    private var helperTimer: Runnable? = null
    @Volatile private var closed = false
    private var polling = false
    private val poll = object : Runnable {
        override fun run() {
            if (closed) return
            active?.let { (session, web) -> if (session.alive) scan(session, web, 0) }
            if (polling) main.postDelayed(this, 1200)
        }
    }
    init {
        android.webkit.ServiceWorkerController.getInstance().setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): android.webkit.WebResourceResponse? {
                val session = active?.first ?: return null
                val ref = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                // A shared worker without an attributable source is deliberately ignored.
                if (ref != null) observe(session, request) // Session/frame attribution is checked on main.
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

    fun observe(session: ProbeSession?, request: WebResourceRequest) {
        if (request.method != "GET" || request.isForMainFrame) return
        val url = request.url.toString()
        if (!webUrl(url)) return
        val headers = request.requestHeaders.toMap()
        main.post {
            if (session?.alive == true) {
                val ref = headers.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                if (ref != null && ref.substringBefore('#') != session.pageUrl.substringBefore('#') && session.frames.none { it.substringBefore('#') == ref.substringBefore('#') }) return@post
                val ctx = RequestContext(session.pageUrl, session.userAgent, ref ?: session.pageUrl, origin(url),
                    headers.entries.firstOrNull { it.key.equals("Authorization", true) }?.value ?: "")
                if (session.requestContexts.size < 60 && Regex("(m3u8|mp4|webm|video|play|stream|download)", RegexOption.IGNORE_CASE).containsMatchIn(URI(url).path.orEmpty())) session.requestContexts[url] = ctx
                // Without Referer, wait for this document's DOM/performance entries to prove ownership.
                if (ref != null || url in session.relationships) candidate(session, url, ctx, false)
            }
        }
    }
    fun observeUrl(session: ProbeSession?, url: String) {
        if (session?.alive == true && url in session.relationships) candidate(session, url, session.requestContexts[url] ?: RequestContext(session.pageUrl, session.userAgent), true)
    }
    fun direct(session: ProbeSession, url: String, mime: String, size: Long) {
        if (!session.alive || !webUrl(url) || session.unsupported) return
        val kind = if (mime.startsWith("image/")) MediaKind.IMAGE else if (mime.contains("mpegurl") || url.substringBefore('?').endsWith(".m3u8", true)) MediaKind.HLS else MediaKind.VIDEO
        if (kind == MediaKind.HLS) {
            if (session.hlsHints.size < 60) session.hlsHints.add(url)
            candidate(session, url, RequestContext(session.pageUrl, session.userAgent), false)
        }
        else merge(session, "direct:$url", session.title, listOf(MediaVariant(url, kind, size = size, mime = mime)), RequestContext(session.pageUrl, session.userAgent))
    }

    private fun scan(session: ProbeSession, web: WebView, depth: Int) {
        if (!session.alive || session.unsupported || closed) return
        web.evaluateJavascript(script) { raw ->
            if (!session.alive || closed || raw.length > 512 * 1024) return@evaluateJavascript
            runCatching {
                val text = JSONTokener(raw).nextValue() as? String ?: return@runCatching
                val root = JSONObject(text)
                val page = root.optString("url")
                if (depth == 0 && page != session.pageUrl) {
                    // SPA identity is handled by the owner as a fresh navigation, never merged here.
                    return@runCatching
                }
                val ctx = RequestContext(session.pageUrl, session.userAgent, if (webUrl(page)) page else session.pageUrl)
                root.optJSONArray("videos")?.let { videos -> repeat(minOf(videos.length(), 40)) { index ->
                    val video = videos.optJSONObject(index) ?: return@repeat
                    val variants = video.optJSONArray("variants") ?: return@repeat
                    val values = mutableListOf<MediaVariant>()
                    repeat(minOf(variants.length(), 12)) { v ->
                        val variant = variants.optJSONObject(v) ?: return@repeat
                        val url = variant.optString("url")
                        if (webUrl(url)) {
                            session.relationships[url] = video.optString("id")
                            if (URI(url).path.endsWith(".m3u8", true) || variant.optString("mime").contains("mpegurl")) {
                                if (session.hlsHints.size < 60) session.hlsHints.add(url)
                                candidate(session, url, ctx, false)
                            }
                            else values.add(MediaVariant(url, MediaKind.VIDEO, variant.optString("quality").ifBlank { "原始版本" }, mime = variant.optString("mime"), requestContext = session.requestContexts[url]))
                        }
                    }
                    if (values.isNotEmpty()) merge(session, video.optString("id"), video.optString("title"), values, ctx, video.optString("thumbnail"), video.optLong("duration", -1))
                } }
                root.optJSONArray("images")?.let { images -> repeat(minOf(images.length(), 200)) { index ->
                    val image = images.optJSONObject(index) ?: return@repeat
                    val url = image.optString("url")
                    if (webUrl(url)) merge(session, "image:$url", image.optString("title"), listOf(MediaVariant(url, MediaKind.IMAGE, quality = if (image.optInt("width") > 0) "${image.optInt("width")} × ${image.optInt("height")}" else "图片")), ctx, url)
                } }
                root.optJSONArray("requests")?.let { requests -> repeat(minOf(requests.length(), 200)) { val url = requests.optString(it); candidate(session, url, session.requestContexts[url] ?: ctx, true) } }
                root.optJSONArray("frames")?.let { frames -> repeat(minOf(frames.length(), 8)) {
                    val url = frames.optString(it)
                    if (depth < 2 && webUrl(url) && url != session.pageUrl && session.frames.size < 12 && session.frames.add(url)) session.queue.add(url to (depth + 1))
                } }
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

    private fun candidate(session: ProbeSession, url: String, ctx: RequestContext, extensionsOnly: Boolean) {
        if (closed || !session.alive || session.unsupported || !webUrl(url) || session.seenRequests.size >= 60) return
        val path = runCatching { URI(url).path.lowercase() }.getOrDefault("")
        val hls = path.endsWith(".m3u8") || url in session.hlsHints
        val video = Regex("\\.(mp4|webm|mov|mkv)$").containsMatchIn(path)
        if (!hls && !video && (extensionsOnly || !Regex("(video|play|stream|download)", RegexOption.IGNORE_CASE).containsMatchIn(path) || Regex("\\.(js|css|json|ts|m4s|woff2?|png|jpg|gif|svg)$").containsMatchIn(path))) return
        if (url in session.seenRequests) return
        if (session.pending >= 4) {
            if (session.deferred.size < 60) session.deferred.putIfAbsent(url, ctx to extensionsOnly)
            return
        }
        session.seenRequests.add(url)
        session.pending++
        workers.execute {
            if (!session.alive || closed) { main.post { session.pending-- }; return@execute }
            val result = runCatching {
                if (hls) hlsVariants(url, ctx) else {
                    val head = http.open(url, ctx, "HEAD")
                    val response = if (head.status in setOf(405, 501)) { head.close(); http.open(url, ctx, headers = mapOf("Range" to "bytes=0-1023")) } else head
                    response.use {
                    MediaHttp.requireSuccess(response.status)
                    if (response.mime.contains("mpegurl")) hlsVariants(url, ctx)
                    else if (response.mime.startsWith("video/") || video && response.mime == "application/octet-stream") listOf(MediaVariant(url, MediaKind.VIDEO,
                        size = if (response.status == 206) response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1 else response.length, mime = response.mime)) else emptyList()
                    }
                }
            }
            main.post {
                session.pending--
                if (!session.alive || closed) return@post
                result.onSuccess { variants -> if (variants.isNotEmpty()) merge(session, session.relationships[url] ?: "network:$url", session.title, variants, ctx) }
                    .onFailure { session.message = "部分候选验证失败，可播放后重新检测"; changed() }
                session.deferred.entries.firstOrNull()?.let { (nextUrl, data) -> session.deferred.remove(nextUrl); candidate(session, nextUrl, data.first, data.second) }
            }
        }
    }
    private fun hlsVariants(url: String, ctx: RequestContext): List<MediaVariant> {
        val (finalUrl, text) = http.text(url, ctx)
        val parsed = HlsPlaylist.parse(text, finalUrl)
        return parsed.variants.ifEmpty { listOf(MediaVariant(finalUrl, MediaKind.HLS, "HLS 点播", blockedReason = parsed.blocked)) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun pump(session: ProbeSession) {
        if (helper != null || !polling || active?.first !== session || session.queue.isEmpty() || session.frameCount >= 6 || closed) return
        val (url, depth) = session.queue.removeFirst()
        session.frameCount++
        helperSession = session
        helper = WebView(context).apply {
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
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
}
