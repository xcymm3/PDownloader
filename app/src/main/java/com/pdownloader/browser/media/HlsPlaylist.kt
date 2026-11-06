package com.pdownloader.browser.media

import java.net.URI

data class HlsPart(val url: String, val length: Long = -1, val offset: Long = 0)
data class HlsDocument(
    val variants: List<MediaVariant> = emptyList(),
    val parts: List<HlsPart> = emptyList(),
    val localLines: List<String> = emptyList(),
    val blocked: String = "",
    val durationMs: Long = 0
)

/** A conservative offline VOD parser. Never concatenates containers or fetches DRM keys. */
object HlsPlaylist {
    fun attributes(line: String): Map<String, String> = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        .findAll(line.substringAfter(':', "")).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun parse(text: String, baseUrl: String): HlsDocument {
        val lines = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.firstOrNull() == "#EXTM3U") { "地址没有返回有效的 HLS 清单" }
        require(lines.size <= 20000) { "播放清单过大" }
        fun absolute(path: String): String = URI(baseUrl).resolve(path).toString().also { require(webUrl(it)) { "清单含不支持的资源地址" } }
        if (lines.any { it.startsWith("#EXT-X-SESSION-KEY") || (it.startsWith("#EXT-X-KEY:") && attributes(it)["METHOD"] != "NONE") }) {
            return HlsDocument(blocked = "暂不支持加密 HLS 或 DRM 媒体")
        }
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF:") }) {
            val separateAudio = lines.filter { it.startsWith("#EXT-X-MEDIA:") }
                .map(::attributes).filter { it["TYPE"] == "AUDIO" && it.containsKey("URI") }.mapNotNull { it["GROUP-ID"] }.toSet()
            val variants = mutableListOf<MediaVariant>()
            lines.forEachIndexed { i, line ->
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    val attrs = attributes(line)
                    val target = lines.getOrNull(i + 1)?.takeUnless { it.startsWith('#') } ?: error("主清单缺少子清单地址")
                    val height = attrs["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0
                    val label = if (height > 0) "${height}p" else attrs["BANDWIDTH"]?.toLongOrNull()?.let { "${it / 1000} kbps" } ?: "HLS 版本 ${variants.size + 1}"
                    variants.add(MediaVariant(absolute(target), MediaKind.HLS, label, height,
                        blockedReason = if (attrs["AUDIO"] in separateAudio) "此版本使用独立音轨，暂不支持离线下载，请回网页播放" else ""))
                }
            }
            return HlsDocument(variants = variants.distinctBy { it.url })
        }
        if ("#EXT-X-ENDLIST" !in lines) return HlsDocument(blocked = "暂不支持直播或尚未结束的 HLS 列表")
        if (lines.any { it.startsWith("#EXT-X-I-FRAMES-ONLY") || it.startsWith("#EXT-X-DEFINE:") || it.startsWith("#EXT-X-PART:") || it.startsWith("#EXT-X-GAP") }) return HlsDocument(blocked = "暂不支持此 HLS 扩展格式")
        val parts = mutableListOf<HlsPart>()
        val local = mutableListOf<String>()
        var range: String? = null
        var previousEnd = 0L
        var previousUrl = ""
        var duration = 0.0
        fun addPart(url: String, spec: String?): String {
            val length = spec?.substringBefore('@')?.toLongOrNull() ?: -1
            val offset = if (spec != null) spec.substringAfter('@', "").toLongOrNull()
                ?: previousEnd.also { require(previousUrl == url) { "字节范围缺少有效的前置偏移" } } else 0L
            require(spec == null || length > 0 && offset >= 0 && offset <= Long.MAX_VALUE - length) { "无效字节范围" }
            parts.add(HlsPart(url, length, offset))
            previousEnd = if (length > 0) offset + length else 0
            previousUrl = url
            require(parts.size <= 5000) { "点播分片超过 5000 个，请缩短视频范围" }
            return "part-${parts.lastIndex}.bin"
        }
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-BYTERANGE:") -> range = line.substringAfter(':')
                line.startsWith("#EXT-X-MAP:") -> {
                    val attrs = attributes(line)
                    val path = addPart(absolute(attrs["URI"] ?: error("初始化片段缺少地址")), attrs["BYTERANGE"])
                    local.add("#EXT-X-MAP:URI=\"$path\"")
                }
                !line.startsWith('#') -> { local.add(addPart(absolute(line), range)); range = null }
                line.startsWith("#EXTINF:") -> { duration += line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0; local.add(line) }
                line.startsWith("#EXT-X-KEY:") || line.startsWith("#EXT-X-RENDITION-REPORT:") -> Unit
                else -> if (line == "#EXTM3U" || listOf("#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:", "#EXT-X-DISCONTINUITY", "#EXT-X-PLAYLIST-TYPE:", "#EXT-X-ENDLIST", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-PROGRAM-DATE-TIME:").any(line::startsWith)) local.add(line)
            }
        }
        require(parts.isNotEmpty()) { "清单没有可下载的分片" }
        return HlsDocument(parts = parts, localLines = local, durationMs = (duration * 1000).toLong())
    }
}
