package com.pdownloader.browser.media

import java.net.URI
import java.math.BigInteger

data class HlsEncryption(val keyUrl: String, val ivHex: String, val keyIndex: Int)
data class HlsPart(val url: String, val length: Long = -1, val offset: Long = 0, val encryption: HlsEncryption? = null)
data class HlsDocument(
    val variants: List<MediaVariant> = emptyList(),
    val parts: List<HlsPart> = emptyList(),
    val localLines: List<String> = emptyList(),
    val blocked: String = "",
    val durationMs: Long = 0
)

/** Offline VOD parser. Ordinary identity AES-128 is supported; other protection stays blocked. */
object HlsPlaylist {
    private const val UNSUPPORTED_ENCRYPTION = "暂不支持加密 HLS 或 DRM 媒体"
    fun attributes(line: String): Map<String, String> = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")
        .findAll(line.substringAfter(':', "")).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun parse(text: String, baseUrl: String): HlsDocument {
        val lines = text.trimStart('\uFEFF').lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        require(lines.firstOrNull() == "#EXTM3U") { "地址没有返回有效的 HLS 清单" }
        require(lines.size <= 20000) { "播放清单过大" }
        fun absolute(path: String): String = URI(baseUrl).resolve(path).toString().also { require(webUrl(it)) { "清单含不支持的资源地址" } }
        if (lines.filter { it.startsWith("#EXT-X-SESSION-KEY") || it.startsWith("#EXT-X-KEY:") }.any { line ->
            val attrs = attributes(line)
            if (attrs["METHOD"] == "NONE" && line.startsWith("#EXT-X-KEY:")) false
            else attrs["METHOD"] != "AES-128" || attrs.getOrDefault("KEYFORMAT", "identity") != "identity" ||
                attrs.getOrDefault("KEYFORMATVERSIONS", "1") != "1" ||
                attrs["URI"].isNullOrBlank() || runCatching { absolute(attrs.getValue("URI")) }.isFailure ||
                attrs["IV"]?.let { !Aes128Hls.validIv(it) } == true
        }) {
            return HlsDocument(blocked = UNSUPPORTED_ENCRYPTION)
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
        var keyUrl: String? = null
        var explicitIv: String? = null
        var keyIndex = 0
        val sequenceText = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }?.substringAfter(':')
        var sequence = if (sequenceText == null) BigInteger.ZERO else
            requireNotNull(sequenceText.toBigIntegerOrNull()) { "地址没有返回有效的 HLS 清单" }
        require(sequence.signum() >= 0 && sequence.bitLength() <= 64) { "地址没有返回有效的 HLS 清单" }
        fun addPart(url: String, spec: String?): String {
            val length = spec?.substringBefore('@')?.toLongOrNull() ?: -1
            val offset = if (spec != null) spec.substringAfter('@', "").toLongOrNull()
                ?: previousEnd.also { require(previousUrl == url) { "字节范围缺少有效的前置偏移" } } else 0L
            require(spec == null || length > 0 && offset >= 0 && offset <= Long.MAX_VALUE - length) { "无效字节范围" }
            val encryption = keyUrl?.let { key -> HlsEncryption(key, explicitIv ?: Aes128Hls.sequenceIv(sequence), keyIndex) }
            parts.add(HlsPart(url, length, offset, encryption))
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
                    if (keyUrl != null && explicitIv == null) return HlsDocument(blocked = UNSUPPORTED_ENCRYPTION)
                    val path = addPart(absolute(attrs["URI"] ?: error("初始化片段缺少地址")), attrs["BYTERANGE"])
                    local.add("#EXT-X-MAP:URI=\"$path\"")
                }
                !line.startsWith('#') -> {
                    require(sequence.bitLength() <= 64) { "地址没有返回有效的 HLS 清单" }
                    local.add(addPart(absolute(line), range)); range = null
                    sequence += BigInteger.ONE
                }
                line.startsWith("#EXTINF:") -> { duration += line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0; local.add(line) }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = attributes(line)
                    keyIndex++
                    keyUrl = if (attrs["METHOD"] == "NONE") null else absolute(attrs.getValue("URI"))
                    explicitIv = if (keyUrl == null) null else attrs["IV"]?.let(Aes128Hls::normalizeIv)
                }
                line.startsWith("#EXT-X-RENDITION-REPORT:") -> Unit
                else -> if (line == "#EXTM3U" || listOf("#EXT-X-VERSION:", "#EXT-X-TARGETDURATION:", "#EXT-X-MEDIA-SEQUENCE:", "#EXT-X-DISCONTINUITY", "#EXT-X-PLAYLIST-TYPE:", "#EXT-X-ENDLIST", "#EXT-X-INDEPENDENT-SEGMENTS", "#EXT-X-PROGRAM-DATE-TIME:").any(line::startsWith)) local.add(line)
            }
        }
        require(parts.isNotEmpty()) { "清单没有可下载的分片" }
        return HlsDocument(parts = parts, localLines = local, durationMs = (duration * 1000).toLong())
    }
}
