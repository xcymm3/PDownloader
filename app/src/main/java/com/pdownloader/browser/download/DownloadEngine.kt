package com.pdownloader.browser.download

import com.pdownloader.browser.media.*
import java.io.File

/** Each worker owns its engine and progress throttle for one transfer. */
class DownloadEngine(private val repository: DownloadRepository, private val http: MediaHttp, private val stopped: () -> Boolean = { false }) {
    class Stopped : Exception()
    private var lastProgress = 0L

    fun run(id: String) {
        var claimed: DownloadTask? = null
        repository.update(id) { task ->
            if (task.state == TaskState.QUEUED && !stopped()) {
                task.copy(state = TaskState.CONNECTING, error = "").also { claimed = it }
            } else task
        }
        val task = claimed ?: return
        try {
            repository.folder(id).mkdirs()
            var attempt = 0
            while (true) {
                check(id)
                change(id) { it.copy(state = TaskState.CONNECTING, error = "") }
                try {
                    if (task.variant.kind == MediaKind.HLS) hls(id) else file(id)
                    break
                } catch (error: Exception) {
                    val retryable = error is java.net.SocketTimeoutException || error is java.net.SocketException || error is java.net.UnknownHostException || error is MediaHttp.HttpFailure && error.code in setOf(500, 502, 503, 504)
                    if (!retryable || attempt >= 2) throw error
                    attempt++
                    change(id) { it.copy(error = "网络连接失败，正在进行第 $attempt 次重试") }
                    Thread.sleep(1000L * attempt)
                }
            }
        } catch (_: Stopped) {
            // The task already has the requested state, including QUEUED after a quick resume.
        } catch (error: Exception) {
            change(id) { it.copy(state = TaskState.FAILED, error = message(error)) }
        } finally {
            if (repository.get(id)?.state in setOf(null, TaskState.CANCELLED)) repository.folder(id).deleteRecursively()
        }
    }

    private fun file(id: String) {
        val task = repository.get(id) ?: throw Stopped()
        val folder = repository.folder(id)
        val partial = File(folder, "transfer.part")
        var offset = if (partial.exists() && task.validator.isNotBlank()) partial.length() else 0L
        if (task.total > 0 && offset >= task.total) offset = 0
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to task.validator) else emptyMap()
        http.open(task.variant.url, task.context, headers = headers).use { response ->
            check(id)
            MediaHttp.requireSuccess(response.status)
            require(!response.mime.contains("html") && !response.mime.contains("mpegurl")) { "服务器返回了网页或清单，请回原网页重新检测" }
            require(!response.mime.startsWith("text/") && !response.mime.contains("json")) { "服务器返回了文本数据，未获得有效媒体" }
            if (response.status == 206) {
                val start = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                require(start == offset) { "服务器返回的断点位置不一致，请重新下载" }
            } else offset = 0
            val total = if (response.status == 206) response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1 else response.length
            val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified") ?: ""
            require(offset == 0L || validator == task.validator) { "远程文件发生变化，请取消后重新下载" }
            change(id) { it.copy(state = TaskState.DOWNLOADING, total = total, downloaded = offset, validator = validator) }
            var copied = offset
            partial.outputStreamAppend(offset > 0).use { output ->
                response.body.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        check(id)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); copied += count
                        report(id) { it.copy(downloaded = copied, total = total) }
                    }
                }
            }
            require(copied > 0 && (total < 0 || copied == total)) { "传输不完整，请继续下载" }
            check(id)
            val mimeVariant = task.variant.copy(mime = response.mime.ifEmpty { task.variant.mime })
            val output = File(folder, safeFileName(task.title, extension(mimeVariant)))
            change(id) {
                require(partial.renameTo(output)) { "无法发布下载文件，请检查存储空间" }
                it.copy(state = TaskState.COMPLETE, downloaded = copied, total = copied, output = output.name, variant = mimeVariant, error = "")
            }
        }
    }

    private fun hls(id: String) {
        val task = repository.get(id) ?: throw Stopped()
        val (url, text) = http.text(task.variant.url, task.context)
        val playlist = HlsPlaylist.parse(text, url)
        require(playlist.blocked.isEmpty()) { playlist.blocked }
        require(playlist.variants.isEmpty()) { "请选择具体清晰度后再下载" }
        val folder = repository.folder(id)
        val fingerprint = File(folder, "playlist.fingerprint")
        val identity = stableId(url + text)
        if (!fingerprint.exists() || fingerprint.readText() != identity) {
            folder.listFiles()?.filter { it.name.startsWith("part-") || it.name.endsWith(".m3u8") }?.forEach { it.delete() }
            fingerprint.writeText(identity)
        }
        var copied = 0L
        change(id) { it.copy(state = TaskState.DOWNLOADING, total = -1, partsTotal = playlist.parts.size, partsDone = 0) }
        playlist.parts.forEachIndexed { index, part ->
            check(id)
            val target = File(folder, "part-$index.bin")
            if (!target.exists() || target.length() == 0L) {
                val partial = File(folder, "part-$index.part")
                val headers = if (part.length > 0) mapOf("Range" to "bytes=${part.offset}-${part.offset + part.length - 1}") else emptyMap()
                http.open(part.url, task.context, headers = headers).use { response ->
                    MediaHttp.requireSuccess(response.status)
                    require(!response.mime.contains("html")) { "分片地址返回了网页" }
                    if (part.length > 0) {
                        val start = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                        require(response.status == 206 && start == part.offset) { "分片服务器不支持所需字节范围" }
                    }
                    var count = 0L
                    partial.outputStream().use { output -> response.body.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(id)
                            val size = input.read(buffer)
                            if (size < 0) break
                            output.write(buffer, 0, size); count += size
                            require(part.length < 0 || count <= part.length) { "分片长度超过请求范围" }
                            report(id) { it.copy(downloaded = copied + count) }
                        }
                    } }
                    require(count > 0 && (part.length < 0 || count == part.length) && (response.length < 0 || response.length == count)) { "分片传输不完整" }
                    check(id)
                    require(partial.renameTo(target)) { "无法保存分片" }
                }
            }
            copied += target.length()
            change(id) { it.copy(downloaded = copied, partsDone = index + 1) }
        }
        check(id)
        change(id) { it.copy(state = TaskState.PROCESSING) }
        val output = File(folder, "offline.m3u8")
        val temporary = File(folder, "offline.tmp")
        temporary.writeText(playlist.localLines.joinToString("\n", postfix = "\n"))
        check(id)
        change(id) {
            require(temporary.renameTo(output)) { "离线清单整理失败" }
            it.copy(state = TaskState.COMPLETE, output = output.name, downloaded = copied, total = copied, error = "")
        }
    }

    private fun check(id: String) { if (stopped() || repository.get(id)?.state !in transferStates) throw Stopped() }
    private fun change(id: String, transform: (DownloadTask) -> DownloadTask) {
        repository.update(id) { if (!stopped() && it.state in transferStates) transform(it) else it }
    }
    private fun report(id: String, transform: (DownloadTask) -> DownloadTask) {
        val now = System.currentTimeMillis()
        if (now - lastProgress > 600) { lastProgress = now; change(id, transform) }
    }
    private fun File.outputStreamAppend(append: Boolean) = java.io.FileOutputStream(this, append)
    private fun message(error: Exception): String = when {
        error is java.net.SocketTimeoutException -> "网络超时，点击继续重试"
        error is java.net.UnknownHostException -> "网络不可用或无法解析资源域名"
        error is java.io.FileNotFoundException || error.message?.contains("ENOSPC") == true -> "无法写入文件，请检查存储空间"
        error is IllegalArgumentException || error is IllegalStateException -> error.message?.take(150) ?: "资源格式不支持"
        error is MediaHttp.HttpFailure -> error.message!!.take(150)
        else -> "网络中断或文件写入失败，请检查网络和存储后继续"
    }
}
