package com.pdownloader.browser.media

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL

class MediaHttp(
    private val cookieForUrl: (String) -> String? = { null },
    private val allowLocalTests: Boolean = false,
    private val timeoutMs: Int? = null
) {
    class HttpFailure(val code: Int) : IOException(when (code) {
        401 -> "需要登录，请打开原网页登录后重试"
        403 -> "服务器拒绝访问，可能需要登录或链接已过期"
        404, 410 -> "资源不存在或链接已过期，请打开原网页重新检测"
        else -> "服务器返回 HTTP $code"
    })
    class Response(val connection: HttpURLConnection, val url: String) : Closeable {
        val status: Int get() = connection.responseCode
        val mime: String get() = connection.contentType?.substringBefore(';')?.lowercase() ?: ""
        val length: Long get() = connection.getHeaderFieldLong("Content-Length", -1)
        val body: InputStream get() = connection.inputStream
        fun header(name: String): String? = connection.getHeaderField(name)
        override fun close() { connection.disconnect() }
    }

    fun open(url: String, context: RequestContext, method: String = "GET", headers: Map<String, String> = emptyMap()): Response {
        var target = url
        repeat(6) {
            validateTarget(target)
            val connection = URL(target).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs ?: 15000; connection.readTimeout = timeoutMs ?: 20000
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", clean(context.userAgent))
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            if (webUrl(context.referer) && !(context.referer.startsWith("https:") && target.startsWith("http:"))) {
                val ref = if (origin(context.referer) == origin(target) || origin(target) == context.credentialOrigin) context.referer.substringBefore('#') else URI(context.referer).let { "${it.scheme}://${it.rawAuthority}/" }
                connection.setRequestProperty("Referer", clean(ref))
            }
            if (context.authorization.isNotBlank() && origin(target) == context.credentialOrigin) connection.setRequestProperty("Authorization", clean(context.authorization))
            // Cookies are resolved for the exact target on every redirect, never copied across hosts.
            cookieForUrl(target)?.takeIf(String::isNotBlank)?.let { value -> connection.setRequestProperty("Cookie", clean(value)) }
            headers.filterKeys { it in setOf("Range", "If-Range") }.forEach { (key, value) -> connection.setRequestProperty(key, clean(value)) }
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val next = connection.getHeaderField("Location") ?: throw IOException("重定向缺少目标")
                    target = URI(target).resolve(next).toString()
                    connection.disconnect()
                } else return Response(connection, target)
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        throw IOException("重定向次数过多")
    }

    fun text(url: String, context: RequestContext): Pair<String, String> = open(url, context).use { response ->
        requireSuccess(response.status)
        val data = response.body.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() <= 1024 * 1024) {
                val count = input.read(buffer, 0, minOf(buffer.size, 1024 * 1024 + 1 - output.size()))
                if (count < 0) break
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(data.size <= 1024 * 1024) { "播放清单超过 1 MB" }
        response.url to data.toString(Charsets.UTF_8)
    }

    fun validateTarget(url: String) {
        require(webUrl(url)) { "只允许 HTTP 或 HTTPS 媒体地址" }
        val host = URI(url).host
        val allowedDebug = allowLocalTests && host in setOf("127.0.0.1", "localhost", "10.0.2.2", "[::1]", "::1")
        if (!allowedDebug) {
            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty() && addresses.none {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress || it.isMulticastAddress ||
                    (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc) ||
                    (it.address.size == 4 && (it.address[0].toInt() and 255) == 100 && (it.address[1].toInt() and 255) in 64..127)
            }) { "媒体下载不允许访问本机或内网目标" }
        }
    }

    companion object {
        fun requireSuccess(code: Int) {
            if (code !in 200..299) throw HttpFailure(code)
        }
        private fun clean(value: String) = value.replace("\r", "").replace("\n", "").take(8192)
    }
}
