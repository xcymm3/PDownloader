package com.pdownloader.browser.media

import java.net.URI
import java.security.MessageDigest

enum class MediaKind {
    VIDEO, IMAGE, HLS, OTHER;

    val isVideo get() = this == VIDEO || this == HLS
}

data class RequestContext(
    val pageUrl: String,
    val userAgent: String,
    val referer: String = pageUrl,
    val credentialOrigin: String = "",
    val authorization: String = ""
)

data class MediaVariant(
    val url: String,
    val kind: MediaKind,
    val quality: String = "原始版本",
    val height: Int = 0,
    val size: Long = -1,
    val mime: String = "",
    val blockedReason: String = "",
    val requestContext: RequestContext? = null
) { val id: String get() = stableId(kind.name + url) }

data class MediaGroup(
    val id: String,
    var title: String,
    val image: Boolean = false,
    var thumbnail: String = "",
    var durationMs: Long = -1,
    val variants: MutableList<MediaVariant> = mutableListOf(),
    var context: RequestContext
)

fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

fun origin(url: String): String = runCatching {
    val uri = URI(url)
    "${uri.scheme.lowercase()}://${uri.host.lowercase()}:${if (uri.port != -1) uri.port else if (uri.scheme.equals("https", true)) 443 else 80}"
}.getOrDefault("")

fun webUrl(value: String): Boolean = runCatching {
    val uri = URI(value)
    value.length <= 8192 && uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

fun safeFileName(title: String, extension: String): String {
    // Stay below the filesystem's byte limit even when every character is Chinese.
    val base = title.replace(Regex("[\\x00-\\x1f\\\\/:*?\"<>|]"), "_").trim().trim('.').take(60)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.ifBlank { "媒体" }
    val suffix = extension.lowercase().replace(Regex("[^a-z0-9]"), "").take(8)
    return if (suffix.isEmpty() || base.endsWith(".$suffix", true)) base else "$base.$suffix"
}

internal fun otherFileName(name: String): String {
    val leaf = name.substringAfterLast('/').substringAfterLast('\\')
    val suffix = leaf.substringAfterLast('.', "")
    return if (suffix.matches(Regex("[A-Za-z0-9]{1,8}")) && leaf.substringBeforeLast('.').isNotBlank())
        safeFileName(leaf.substringBeforeLast('.'), suffix)
    else safeFileName(leaf, "")
}

internal fun specificMime(mime: String): String? =
    mime.substringBefore(';').trim().lowercase().takeUnless {
        it.isEmpty() || it in setOf("application/octet-stream", "binary/octet-stream", "application/force-download", "application/download", "application/x-download", "*/*")
    }

internal fun downloadKind(url: String, mime: String, fileName: String = ""): MediaKind {
    val type = specificMime(mime)
    val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
    if (type?.startsWith("image/") == true) return MediaKind.IMAGE
    if (type?.contains("mpegurl") == true || ((type == null || type.startsWith("video/")) &&
            (fileName.endsWith(".m3u8", true) || path.endsWith(".m3u8", true)))) return MediaKind.HLS
    if (type?.startsWith("video/") == true || type in setOf("application/mp4", "application/dash+xml")) return MediaKind.VIDEO
    if (type != null && type !in setOf("application/ogg", "application/x-matroska")) return MediaKind.OTHER
    for (name in listOf(fileName, path)) {
        when (name.substringAfterLast('.', "").lowercase()) {
            "mp4", "webm", "mov", "mkv", "m4v", "avi", "ts", "3gp", "mpg", "mpeg", "ogv", "flv" -> return MediaKind.VIDEO
            "jpg", "jpeg", "png", "webp", "gif", "avif", "svg", "bmp", "ico", "heic", "heif", "tiff" -> return MediaKind.IMAGE
        }
    }
    return MediaKind.OTHER
}

fun extension(variant: MediaVariant): String {
    if (variant.kind == MediaKind.HLS) return "m3u8"
    val mime = variant.mime.substringBefore(';').lowercase()
    val known = mapOf("video/mp4" to "mp4", "video/webm" to "webm", "video/quicktime" to "mov", "video/mp2t" to "ts", "video/x-msvideo" to "avi", "image/jpeg" to "jpg", "image/png" to "png", "image/webp" to "webp", "image/gif" to "gif", "image/avif" to "avif", "image/svg+xml" to "svg")
    return known[mime] ?: runCatching { URI(variant.url).path.substringAfterLast('.').lowercase().takeIf { it in setOf("mp4", "webm", "mov", "mkv", "m4v", "avi", "ts", "jpg", "jpeg", "png", "webp", "gif", "avif", "svg") } }.getOrNull()
        ?: "bin"
}

fun bytes(value: Long): String = when {
    value < 0 -> "大小未知"
    value < 1024 -> "$value B"
    value < 1024 * 1024 -> "%.1f KB".format(value / 1024.0)
    value < 1024L * 1024 * 1024 -> "%.1f MB".format(value / (1024.0 * 1024))
    else -> "%.2f GB".format(value / (1024.0 * 1024 * 1024))
}
