package com.pdownloader.browser

import android.content.Context
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Debug-only loopback fixture server. Not packaged in release builds. */
class FixtureServer(private val context: Context) : Closeable {
    private val server = ServerSocket(0, 20, InetAddress.getByName("127.0.0.1"))
    private val workers = Executors.newCachedThreadPool()
    @Volatile private var running = true
    val base = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    @Volatile var rejectSegment = false
    @Volatile var beforeSlowBody: ((String) -> Unit)? = null
    init {
        workers.execute {
            while (running) runCatching { server.accept() }.getOrNull()?.let { socket -> workers.execute { runCatching { serve(socket) }; socket.close() } }
        }
    }
    private fun serve(socket: Socket) {
        socket.soTimeout = 10000
        val reader = socket.getInputStream().bufferedReader()
        val first = reader.readLine()?.split(' ') ?: return
        if (first.size < 2) return
        val method = first[0]
        val path = first[1].substringBefore('?')
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        requests.add(path to headers)
        var status = 200
        var mime = "video/mp4"
        var extra = "ETag: \"fixture-v1\"\r\nAccept-Ranges: bytes\r\n"
        val body = when {
            path == "/" -> { mime = "text/html; charset=utf-8"; page().toByteArray() }
            path == "/frame" -> { mime = "text/html"; "<video preload=\"none\" controls src=\"$base/sample.mp4?frame=1\"></video>".toByteArray() }
            path == "/notes.txt" -> {
                mime = "text/plain; charset=utf-8"
                extra += "Content-Disposition: attachment; filename=\"notes.txt\"\r\n"
                "测试附件\n".toByteArray()
            }
            path == "/master.m3u8" -> { mime = "application/vnd.apple.mpegurl"; "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=400000,RESOLUTION=160x288\nmedia.m3u8?quality=288\n#EXT-X-STREAM-INF:BANDWIDTH=200000,RESOLUTION=80x144\nmedia.m3u8?quality=144\n".toByteArray() }
            path == "/redirect.mp4" -> { status = 302; extra += "Location: $base/sample.mp4\r\n"; ByteArray(0) }
            path == "/protected.mp4" && headers["cookie"]?.contains("fixture=yes") != true -> { status = 403; ByteArray(0) }
            path == "/picture.png" -> {
                mime = "image/png"
                java.io.ByteArrayOutputStream().use { output ->
                    val bitmap = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.RED)
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                    bitmap.recycle()
                    output.toByteArray()
                }
            }
            path == "/playlist" -> { mime = "application/vnd.apple.mpegurl"; context.assets.open("media-fixture/media.m3u8").use { it.readBytes() } }
            path == "/fake.m3u8" -> { mime = "text/html"; "<html>not a playlist</html>".toByteArray() }
            path == "/live.m3u8" -> { mime = "application/vnd.apple.mpegurl"; "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment-000.ts\n".toByteArray() }
            path == "/encrypted.m3u8" -> { mime = "application/vnd.apple.mpegurl"; "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"secret.key\"\n#EXTINF:1,\nsegment-000.ts\n#EXT-X-ENDLIST\n".toByteArray() }
            path == "/segment-001.ts" && rejectSegment -> { status = 503; ByteArray(0) }
            else -> {
                val name = if (path in setOf("/protected.mp4", "/slow.mp4", "/ignore-range.mp4")) "sample.mp4" else path.removePrefix("/")
                if (!name.matches(Regex("(sample\\.mp4|media\\.m3u8|single\\.m3u8|single\\.mp4|segment-[0-9]{3}\\.ts)"))) { status = 404; ByteArray(0) }
                else {
                    mime = when { name.endsWith("m3u8") -> "application/vnd.apple.mpegurl"; name.endsWith("ts") -> "video/mp2t"; else -> "video/mp4" }
                    context.assets.open("media-fixture/$name").use { it.readBytes() }
                }
            }
        }
        var bytes = body
        if (status == 200 && headers.containsKey("range") && path != "/ignore-range.mp4") {
            val range = headers["range"]!!.removePrefix("bytes=")
            val start = range.substringBefore('-').toIntOrNull() ?: 0
            val end = range.substringAfter('-').toIntOrNull()?.coerceAtMost(body.lastIndex) ?: body.lastIndex
            if (start >= body.size || start < 0 || end < start) { status = 416; bytes = ByteArray(0) }
            else { status = 206; bytes = body.copyOfRange(start, end + 1); extra += "Content-Range: bytes $start-$end/${body.size}\r\n" }
        }
        val output = socket.getOutputStream()
        output.write("HTTP/1.1 $status ${if (status == 200) "OK" else "Result"}\r\nContent-Type: $mime\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray())
        if (method != "HEAD") {
            if (path == "/slow.mp4") {
                beforeSlowBody?.invoke(first[1])
                bytes.asList().chunked(2048).forEach { chunk -> output.write(chunk.toByteArray()); output.flush(); Thread.sleep(60) }
            } else output.write(bytes)
        }
        output.flush()
    }
    private fun page() = """
        <!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
        <title>PDownloader 本地下载测试</title><style>:root{color-scheme:light dark}body{font:16px sans-serif;padding:20px}video{width:100%;max-height:40vh}img{width:96px;height:96px;image-rendering:pixelated}a{display:block;padding:12px}</style>
        <h1>视频与图片下载测试</h1><p>全部媒体由应用内本地服务器提供，不依赖外部网站。点击右下方入口选择并下载，再到“已下载”离线播放。</p>
        <video controls poster="$base/picture.png"><source src="$base/sample.mp4" type="video/mp4" label="原始 MP4"><source src="$base/sample.mp4?version=2" type="video/mp4" label="备用版本"></video>
        <p>HLS 清单有两条测试版本，实际分片相同；用于验证版本分组。</p>
        <a href="$base/master.m3u8">HLS 主清单</a><a href="$base/media.m3u8">HLS 点播</a><a href="$base/picture.png">图片</a>
        <img src="$base/picture.png" alt="测试图片"><div id="delayed"></div>
        <iframe title="跨源播放器测试" src="${base.replace("127.0.0.1", "localhost")}/frame"></iframe>
        <script>setTimeout(()=>{document.getElementById('delayed').innerHTML='<video controls src="$base/sample.mp4?delayed=1"></video>';fetch('$base/master.m3u8')},1500)</script>
        </html>
    """.trimIndent()
    override fun close() { running = false; server.close(); workers.shutdownNow() }
}

object DebugFixtures {
    private var server: FixtureServer? = null
    fun start(context: Context) { if (server == null) server = FixtureServer(context.applicationContext) }
    fun url(): String? = server?.base?.plus("/")
}
