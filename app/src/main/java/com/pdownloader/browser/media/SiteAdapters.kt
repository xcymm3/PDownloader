package com.pdownloader.browser.media

import org.json.JSONObject
import java.net.URI
import java.util.Locale

internal sealed interface ShareMedia {
    data class Found(val page: String, val title: String, val groups: List<MediaGroup>) : ShareMedia
    data class OpenPage(val message: String) : ShareMedia
}

internal interface SiteAdapter {
    fun matches(url: String): Boolean
    fun recognize(url: String, userAgent: String): ShareMedia
}

internal class SiteAdapters(cookie: (String) -> String?) {
    private val adapters: List<SiteAdapter> = listOf(BilibiliAdapter(cookie))
    fun match(url: String) = adapters.firstOrNull { it.matches(url) }
}

private const val BILI_API = "https://api.bilibili.com"
private val BILI_PAGES = setOf("www.bilibili.com", "m.bilibili.com", "bilibili.com")
private const val BILI_SHORT = "b23.tv"
private val VIDEO_PATH = Regex("/video/(BV[0-9A-Za-z]{10}|av[0-9]{1,18})/?")
private val SHORT_PATH = Regex("/[0-9A-Za-z_-]{2,32}/?")
private val BVID = Regex("BV[0-9A-Za-z]{10}")

/** Uses Bilibili's first-party public web playback responses for progressive, single-file versions. */
internal class BilibiliAdapter(
    private val loadJson: (String, RequestContext) -> JSONObject,
    private val resolveShort: (String, RequestContext) -> String
) : SiteAdapter {
    constructor(cookie: (String) -> String?) : this(
        MediaHttp(cookie, timeoutMs = 3500, allowedTarget = { httpsHost(it) == "api.bilibili.com" }),
        MediaHttp(cookie, timeoutMs = 3500, allowedTarget = { httpsHost(it) in BILI_PAGES || httpsHost(it) == BILI_SHORT })
    )

    private constructor(api: MediaHttp, short: MediaHttp) : this(
        { url, context ->
            check(!Thread.currentThread().isInterrupted)
            JSONObject(api.text(url, context).second)
        },
        { url, context -> short.open(url, context).use { response -> MediaHttp.requireSuccess(response.status); response.url } }
    )

    override fun matches(url: String): Boolean {
        if (!webUrl(url)) return false
        val path = URI(url).path.orEmpty()
        return when (host(url)) {
            in BILI_PAGES -> VIDEO_PATH.matches(path)
            BILI_SHORT -> SHORT_PATH.matches(path)
            else -> false
        }
    }

    override fun recognize(url: String, userAgent: String): ShareMedia {
        if (!matches(url)) return fallback("此链接暂不支持直解析")
        return try {
            val resolved = if (host(url) == BILI_SHORT) {
                val secureUrl = "https://" + url.substringAfter("://")
                resolveShort(secureUrl, RequestContext(secureUrl, userAgent))
            } else url
            val page = if (webUrl(resolved) && host(resolved) in BILI_PAGES) URI(resolved) else return fallback("短链接未指向支持的视频页面")
            val id = VIDEO_PATH.matchEntire(page.path)?.groupValues?.get(1) ?: return fallback("链接未指向支持的视频页面")
            val partValue = page.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("p=") }?.substringAfter('=')
            val part = if (partValue == null) 1 else partValue.toIntOrNull()?.takeIf { it > 0 }
                ?: return fallback("无法识别视频分 P")
            val key = if (id.startsWith("BV")) "bvid=$id" else "aid=${id.removePrefix("av")}"
            val apiContext = RequestContext(resolved, userAgent, credentialOrigin = origin(BILI_API))
            val metadata = data(loadJson("$BILI_API/x/web-interface/view?$key", apiContext))
                ?: return fallback("未找到视频信息")
            val bvid = metadata.optString("bvid").takeIf(BVID::matches) ?: return fallback("未找到视频编号")
            if (id.startsWith("BV") && bvid != id) return fallback("视频编号与原链接不符")
            val canonical = "https://www.bilibili.com/video/$bvid/" + if (part > 1) "?p=$part" else ""
            val pages = metadata.optJSONArray("pages")
            val entry = (0 until minOf(pages?.length() ?: 0, 200)).asSequence().mapNotNull { pages?.optJSONObject(it) }
                .firstOrNull { it.optInt("page") == part }
            val cid = (entry?.optLong("cid") ?: if (part == 1) metadata.optLong("cid") else 0).takeIf { it > 0 }
                ?: return fallback("未找到指定分 P")
            val title = metadata.optString("title").ifBlank { bvid }.take(160)
            val name = if (part > 1) "$title · ${entry?.optString("part").orEmpty().ifBlank { "P$part" }}" else title
            val context = RequestContext(canonical, userAgent, credentialOrigin = origin(BILI_API))
            fun playback(quality: Int) = data(loadJson(
                "$BILI_API/x/player/playurl?bvid=$bvid&cid=$cid&qn=$quality&fnval=0&platform=html5", context
            ))
            val first = playback(64) ?: return fallback("未找到可直接下载的公开版本")
            val lower = first.optJSONArray("accept_quality")?.let { values ->
                (0 until minOf(values.length(), 12)).map { values.optInt(it) }.filter { it in 1 until first.optInt("quality") }.minOrNull()
            }
            val versions = buildList {
                progressive(first, context)?.let(::add)
                if (lower != null && !Thread.currentThread().isInterrupted) {
                    runCatching { playback(lower)?.let { progressive(it, context) } }.getOrNull()?.let(::add)
                }
            }.distinctBy { it.url }.distinctBy { it.height to it.quality }.toMutableList()
            if (versions.isEmpty()) return fallback("该视频未提供单文件版本")
            val cover = metadata.optString("pic").takeIf { webUrl(it) && host(it)?.endsWith(".hdslb.com") == true }.orEmpty()
            val duration = (entry?.optLong("duration") ?: metadata.optLong("duration")).takeIf { it in 1..86400 }?.times(1000) ?: -1
            val groups = mutableListOf(MediaGroup(stableId("bilibili:$bvid:$cid"), name, thumbnail = cover, durationMs = duration,
                variants = versions, context = context))
            if (cover.isNotEmpty()) groups.add(MediaGroup(stableId("image:$cover"), "$name 封面", image = true, thumbnail = cover,
                variants = mutableListOf(MediaVariant(cover, MediaKind.IMAGE)), context = context))
            ShareMedia.Found(canonical, name, groups)
        } catch (_: LoginRequired) {
            fallback("此视频需要登录，请在原网页登录后继续")
        } catch (error: MediaHttp.HttpFailure) {
            fallback(if (error.code == 401) "此视频需要登录，请在原网页登录后继续" else "站点暂时无法识别")
        } catch (_: java.net.SocketTimeoutException) {
            fallback("识别超时")
        } catch (_: Exception) {
            fallback("识别失败")
        }
    }

    private fun progressive(data: JSONObject, source: RequestContext): MediaVariant? {
        val entry = data.optJSONArray("durl")?.takeIf { it.length() == 1 }?.optJSONObject(0) ?: return null
        val url = entry.optString("url")
        if (!webUrl(url) || host(url)?.let { it.endsWith(".bilivideo.com") || it.endsWith(".bilivideo.cn") } != true) return null
        val format = data.optString("format").lowercase(Locale.ROOT)
        val mime = when {
            format.contains("mp4") -> "video/mp4"
            format.contains("flv") -> "video/x-flv"
            else -> return null
        }
        val quality = data.optInt("quality")
        val height = when (quality) { 6, 16 -> 360; 32 -> 480; 64, 74 -> 720; 80, 112, 116 -> 1080; else -> 0 }
        return MediaVariant(url, MediaKind.VIDEO, if (height > 0) "${height}p" else "原始版本",
            height, entry.optLong("size", -1), mime, requestContext = source.copy(credentialOrigin = origin(url)))
    }

    private fun data(result: JSONObject): JSONObject? = when (result.optInt("code", Int.MIN_VALUE)) {
        0 -> result.optJSONObject("data")
        -101 -> throw LoginRequired()
        else -> null
    }

    private class LoginRequired : Exception()
    private fun fallback(message: String) = ShareMedia.OpenPage(message)
}

private fun host(url: String): String? = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull()
private fun httpsHost(url: String): String? = runCatching {
    val uri = URI(url)
    uri.host?.lowercase(Locale.ROOT)?.takeIf { uri.scheme.equals("https", true) && uri.port in setOf(-1, 443) }
}.getOrNull()
