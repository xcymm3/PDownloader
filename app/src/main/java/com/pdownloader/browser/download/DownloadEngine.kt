package com.pdownloader.browser.download

import android.net.Uri
import com.pdownloader.browser.media.*
import java.io.File
import java.io.IOException

/** Each worker owns its engine and progress throttle for one transfer. */
class DownloadEngine(private val repository: DownloadRepository, private val http: MediaHttp, private val stopped: () -> Boolean = { false }) {
    class Stopped : Exception()
    private class SourceUnavailable(message: String) : IOException(message)
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
            if (task.resetPartial) {
                repository.folder(id).listFiles().orEmpty().forEach { file ->
                    check(id)
                    require(!file.exists() || file.isFile && file.delete()) { "无法清理旧下载进度" }
                }
                change(id) { it.copy(resetPartial = false) }
            }
            if (task.publicUri.isNotBlank()) {
                repository.publicDownloads.delete(Uri.parse(task.publicUri))
                change(id) { it.copy(publicUri = "") }
            }
            var attempt = 0
            while (true) {
                check(id)
                change(id) { it.copy(state = TaskState.CONNECTING, error = "") }
                try {
                    when {
                        task.variant.kind == MediaKind.HLS -> hls(id)
                        task.variant.audioUrl.isNotBlank() -> splitVideo(id, task)
                        else -> file(id)
                    }
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
            change(id) { it.copy(state = TaskState.FAILED, failure = failure(error), error = message(error)) }
        } finally {
            if (repository.get(id)?.state in setOf(null, TaskState.CANCELLED)) repository.folder(id).deleteRecursively()
        }
    }

    private fun splitVideo(id: String, task: DownloadTask) {
        val folder = repository.folder(id)
        val video = File(folder, "video-track.bin")
        val audio = File(folder, "audio-track.bin")
        change(id) { it.copy(state = TaskState.DOWNLOADING, downloaded = 0, total = -1, partsDone = 0, partsTotal = 2) }
        downloadTrack(id, task.variant.url, task.context.forTrack(task.variant.url), video, "video", 0)
        change(id) { it.copy(downloaded = video.length(), partsDone = 1) }
        downloadTrack(id, task.variant.audioUrl, task.context.forTrack(task.variant.audioUrl), audio, "audio", video.length())
        change(id) { it.copy(state = TaskState.PROCESSING, downloaded = video.length() + audio.length(), partsDone = 2) }
        val mp4 = File(folder, "merged-output.part")
        try {
            Mp4Output.write(video, audio, mp4, task.durationMs) { check(id) }
            finishMp4(id, task, mp4)
            if (repository.get(id)?.state == TaskState.COMPLETE) {
                video.delete()
                audio.delete()
            }
        } finally {
            mp4.delete()
        }
    }

    private fun downloadTrack(id: String, url: String, context: RequestContext, target: File, type: String, previous: Long) {
        check(id)
        if (target.isFile && target.length() > 0) return
        val temporary = File(target.parentFile, "${target.name}.part")
        try {
            http.open(url, context).use { response ->
                MediaHttp.requireSuccess(response.status)
                val mime = specificMime(response.mime)
                val validType = mime == null || mime == "application/mp4" || mime.startsWith("$type/") ||
                    type == "audio" && mime == "video/mp4"
                if (response.status != 200 || !validType) throw SourceUnavailable("服务器未返回完整的音视频轨道，请回原页重新检测")
                val length = response.length
                var copied = 0L
                response.body.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(id)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            copied += count
                            report(id) { it.copy(downloaded = previous + copied) }
                        }
                    }
                }
                if (copied <= 0 || length >= 0 && copied != length) throw IOException("音视频轨道传输不完整")
            }
            check(id)
            require(temporary.renameTo(target)) { "无法保存音视频轨道" }
        } finally {
            temporary.delete()
        }
    }

    private fun file(id: String, allowParallel: Boolean = true) {
        val task = repository.get(id) ?: throw Stopped()
        val folder = repository.folder(id)
        val partial = File(folder, "transfer.part")
        var offset = if (partial.exists() && task.validator.isNotBlank()) partial.length() else 0L
        if (task.total > 0 && offset >= task.total) offset = 0
        val headers = if (offset > 0) mapOf("Range" to "bytes=$offset-", "If-Range" to task.validator) else emptyMap()
        var connection = http.open(task.variant.url, task.context, headers = headers)
        if (offset > 0 && (connection.status == 416 || connection.status == 206 && !validRange(connection, task, offset))) {
            connection.close()
            offset = 0
            check(id)
            connection = http.open(task.variant.url, task.context)
        }
        connection.use { response ->
            check(id)
            MediaHttp.requireSuccess(response.status)
            val attachment = response.header("Content-Disposition")?.substringBefore(';')?.trim().equals("attachment", true)
            if (response.mime.contains("mpegurl") || response.mime.contains("html") && !(task.variant.kind == MediaKind.OTHER && attachment)) {
                throw SourceUnavailable("服务器返回了网页或清单，请回原页重新检测")
            }
            if (task.variant.kind != MediaKind.OTHER) {
                if (specificMime(response.mime) != null && downloadKind(response.url, response.mime) != task.variant.kind) {
                    throw SourceUnavailable("服务器返回的内容与资源类型不符，请回原页重新检测")
                }
            }
            if (response.status == 206) {
                val start = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                if (start != offset) throw SourceUnavailable("服务器返回的断点位置不一致，请回原页重新检测")
            } else offset = 0
            val total = if (response.status == 206) response.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1 else response.length
            val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified") ?: ""
            require(offset == 0L || validator == task.validator) { "远程文件发生变化，请取消后重新下载" }
            val mimeVariant = task.variant.copy(mime = specificMime(response.mime) ?: task.variant.mime.ifBlank { response.mime })
            val strongValidator = ParallelFileTransfer.validator(response)
            if (allowParallel && offset == 0L && response.status == 200 && task.variant.kind != MediaKind.IMAGE &&
                total >= ParallelFileTransfer.MIN_SIZE && strongValidator != null && ParallelFileTransfer.slots.tryAcquire()
            ) {
                try {
                    val resolved = response.url
                    response.close()
                    change(id) { it.copy(state = TaskState.DOWNLOADING, total = total, downloaded = 0, validator = "") }
                    val matched = ParallelFileTransfer(http).download(task.variant.url, resolved, task.context, partial,
                        total, strongValidator, { check(id) }) { count ->
                        report(id) { it.copy(downloaded = maxOf(it.downloaded, count)) }
                    }
                    if (matched) {
                        change(id) { it.copy(state = TaskState.PROCESSING, downloaded = total) }
                        finishFile(id, task, partial, mimeVariant, total)
                    } else file(id, allowParallel = false)
                    return
                } finally {
                    ParallelFileTransfer.slots.release()
                }
            }
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
            if (!((copied > 0 || task.variant.kind == MediaKind.OTHER && total == 0L) && (total < 0 || copied == total))) {
                throw IOException("传输不完整，请继续下载")
            }
            finishFile(id, task, partial, mimeVariant, copied)
        }
    }

    private fun finishFile(id: String, task: DownloadTask, partial: File, variant: MediaVariant, copied: Long) {
        check(id)
        val name = if (variant.kind == MediaKind.OTHER) otherFileName(task.title) else safeFileName(task.title, extension(variant))
        val folder = repository.folder(id)
        val output = File(folder, name)
        val completed: (DownloadTask) -> DownloadTask = {
            it.copy(state = TaskState.COMPLETE, downloaded = copied, total = copied, output = name, variant = variant, error = "")
        }
        if (task.saveToPublic) {
            val mime = specificMime(variant.mime)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(output.extension)
                ?: "application/octet-stream"
            publish(id, partial, name, mime, completed)
            partial.delete()
        } else change(id) {
            require(partial == output || partial.renameTo(output)) { "无法发布下载文件，请检查存储空间" }
            completed(it)
        }
        if (repository.get(id)?.state == TaskState.COMPLETE) runCatching { ParallelFileTransfer.clear(folder, keep = output) }
    }

    private fun validRange(response: MediaHttp.Response, task: DownloadTask, offset: Long): Boolean {
        val range = response.header("Content-Range") ?: return false
        val start = range.substringAfter("bytes ").substringBefore('-').toLongOrNull()
        val total = range.substringAfter('/').toLongOrNull()
        val validator = response.header("ETag")?.takeUnless { it.startsWith("W/") } ?: response.header("Last-Modified")
        return start == offset && validator == task.validator && (task.total <= 0 || total == task.total)
    }

    private fun hls(id: String) {
        val task = repository.get(id) ?: throw Stopped()
        val (url, text) = http.text(task.variant.url, task.context)
        if (!text.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")) {
            throw SourceUnavailable("地址未返回播放清单，请回原页重新检测")
        }
        val playlist = HlsPlaylist.parse(text, url)
        require(playlist.blocked.isEmpty()) { playlist.blocked }
        require(playlist.variants.isEmpty()) { "请选择具体清晰度后再下载" }
        val folder = repository.folder(id)
        val fingerprint = File(folder, "playlist.fingerprint")
        val identity = stableId((if (playlist.parts.any { it.encryption != null }) "aes128-v1\n" else "") + url + text)
        if (!fingerprint.exists() || fingerprint.readText() != identity) {
            folder.listFiles()?.filter { it.name.startsWith("part-") || it.name.endsWith(".m3u8") }?.forEach { it.delete() }
            fingerprint.writeText(identity)
        }
        var copied = 0L
        val keys = mutableMapOf<Pair<Int, String>, ByteArray>()
        fun keyFor(encryption: HlsEncryption): ByteArray = keys.getOrPut(encryption.keyIndex to encryption.keyUrl) {
            check(id)
            http.open(encryption.keyUrl, task.context).use { response ->
                MediaHttp.requireSuccess(response.status)
                val data = ByteArray(17)
                var length = 0
                response.body.use { input ->
                    while (length < data.size) {
                        check(id)
                        val count = input.read(data, length, data.size - length)
                        if (count < 0) break
                        length += count
                    }
                }
                if (length != 16) {
                    data.fill(0)
                    throw SourceUnavailable("分片地址未返回媒体，请回原页重新检测")
                }
                data.copyOf(16).also { data.fill(0) }
            }
        }
        change(id) { it.copy(state = TaskState.DOWNLOADING, total = -1, partsTotal = playlist.parts.size, partsDone = 0) }
        try { playlist.parts.forEachIndexed { index, part ->
            check(id)
            val target = File(folder, "part-$index.bin")
            if (!target.exists() || target.length() == 0L) {
                val partial = File(folder, "part-$index.part")
                val decryptor = part.encryption?.let { encryption ->
                    Aes128Hls.decryptor(keyFor(encryption), encryption.ivHex)
                }
                val headers = if (part.length > 0) mapOf("Range" to "bytes=${part.offset}-${part.offset + part.length - 1}") else emptyMap()
                http.open(part.url, task.context, headers = headers).use { response ->
                    MediaHttp.requireSuccess(response.status)
                    if (response.mime.contains("html") || response.mime.startsWith("text/") || response.mime.contains("json")) {
                        throw SourceUnavailable("分片地址未返回媒体，请回原页重新检测")
                    }
                    if (part.length > 0) {
                        val start = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                        require(response.status == 206 && start == part.offset) { "分片服务器不支持所需字节范围" }
                    }
                    var count = 0L
                    var written = 0L
                    partial.outputStream().use { output -> response.body.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(id)
                            val size = input.read(buffer)
                            if (size < 0) break
                            count += size
                            require(part.length < 0 || count <= part.length) { "分片长度超过请求范围" }
                            if (decryptor == null) {
                                output.write(buffer, 0, size); written += size
                            } else decryptor.update(buffer, 0, size)?.let { plain -> output.write(plain); written += plain.size }
                            report(id) { it.copy(downloaded = copied + written) }
                        }
                        if (count <= 0 || part.length >= 0 && count != part.length || response.length >= 0 && response.length != count) {
                            throw IOException("分片传输不完整，请继续下载")
                        }
                        if (decryptor != null) {
                            val tail = try { decryptor.doFinal() } catch (_: java.security.GeneralSecurityException) {
                                throw SourceUnavailable("分片地址未返回媒体，请回原页重新检测")
                            }
                            output.write(tail); written += tail.size
                            if (written == 0L) throw SourceUnavailable("分片地址未返回媒体，请回原页重新检测")
                        }
                    } }
                    check(id)
                    require(partial.renameTo(target)) { "无法保存分片" }
                }
            }
            copied += target.length()
            change(id) { it.copy(downloaded = copied, partsDone = index + 1) }
        } } finally { keys.values.forEach { it.fill(0) } }
        check(id)
        change(id) { it.copy(state = TaskState.PROCESSING) }
        val mp4 = File(folder, "merged-output.part")
        try {
            if (mergeHls(id, folder, playlist, mp4)) {
                finishMp4(id, task, mp4)
                if (repository.get(id)?.state == TaskState.COMPLETE) {
                    playlist.parts.indices.forEach { File(folder, "part-$it.bin").delete() }
                    fingerprint.delete()
                }
                return
            }
        } finally {
            mp4.delete()
        }
        val output = File(folder, "offline.m3u8")
        val temporary = File(folder, "offline.tmp")
        temporary.writeText(playlist.localLines.joinToString("\n", postfix = "\n"))
        check(id)
        val completed: (DownloadTask) -> DownloadTask = {
            it.copy(state = TaskState.COMPLETE, output = output.name, downloaded = copied, total = copied, error = "")
        }
        if (task.saveToPublic) {
            require(temporary.renameTo(output)) { "离线清单整理失败" }
            val archive = File(folder, "public-export.part")
            try {
                writeOfflineArchive(folder, output.name, archive) { check(id) }
                publish(id, archive, safeFileName(task.title, "zip"), "application/zip", completed)
            } finally {
                archive.delete()
            }
        } else change(id) {
            require(temporary.renameTo(output)) { "离线清单整理失败" }
            completed(it)
        }
    }

    private fun mergeHls(id: String, folder: File, playlist: HlsDocument, mp4: File): Boolean {
        if (playlist.durationMs <= 0 || playlist.localLines.any { it == "#EXT-X-DISCONTINUITY" } ||
            playlist.localLines.count { it.startsWith("#EXT-X-MAP:") } > 1) return false
        val stream = File(folder, "hls-input.part")
        return try {
            stream.outputStream().buffered().use { output ->
                playlist.parts.indices.forEach { index ->
                    File(folder, "part-$index.bin").inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            check(id)
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            Mp4Output.write(stream, null, mp4, playlist.durationMs) { check(id) }
            true
        } catch (stopped: Stopped) {
            throw stopped
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } catch (_: IllegalStateException) {
            false
        } finally {
            stream.delete()
        }
    }

    private fun finishMp4(id: String, task: DownloadTask, source: File) {
        val name = safeFileName(task.title, "mp4")
        val size = source.length()
        val completed: (DownloadTask) -> DownloadTask = {
            it.copy(state = TaskState.COMPLETE, output = name, downloaded = size, total = size, error = "")
        }
        if (task.saveToPublic) publish(id, source, name, "video/mp4", completed)
        else change(id) {
            require(source.renameTo(File(repository.folder(id), name))) { "无法保存 MP4，请检查存储空间" }
            completed(it)
        }
    }

    private fun publish(id: String, source: File, name: String, mime: String, complete: (DownloadTask) -> DownloadTask) {
        var uri: Uri? = null
        var committed = false
        try {
            check(id)
            val published = repository.publicDownloads.publish(source, name, mime, { check(id) }) { created ->
                uri = created
                var remembered = false
                change(id) { task -> task.copy(publicUri = created.toString()).also { remembered = true } }
                if (!remembered) throw Stopped()
            }
            var finished = false
            change(id) { task ->
                complete(task).copy(publicUri = published.toString()).also { finished = true }
            }
            if (!finished) throw Stopped()
            committed = true
        } finally {
            if (!committed) uri?.let { created ->
                if (runCatching { repository.publicDownloads.delete(created) }.isSuccess) {
                    repository.update(id) { task ->
                        if (task.publicUri == created.toString()) task.copy(publicUri = "") else task
                    }
                }
            }
        }
    }

    private fun check(id: String) { if (stopped() || repository.get(id)?.state !in transferStates) throw Stopped() }
    private fun change(id: String, transform: (DownloadTask) -> DownloadTask) {
        repository.update(id) { if (!stopped() && it.state in transferStates) transform(it) else it }
    }
    @Synchronized private fun report(id: String, transform: (DownloadTask) -> DownloadTask) {
        val now = System.currentTimeMillis()
        if (now - lastProgress > 600) { lastProgress = now; change(id, transform) }
    }
    private fun File.outputStreamAppend(append: Boolean) = java.io.FileOutputStream(this, append)
    private fun failure(error: Exception): DownloadFailure = when (error) {
        is MediaHttp.HttpFailure -> when (error.code) {
            401 -> DownloadFailure.LOGIN
            403 -> DownloadFailure.FORBIDDEN
            404, 410 -> DownloadFailure.SOURCE
            408, 429, in 500..599 -> DownloadFailure.NETWORK
            else -> DownloadFailure.OTHER
        }
        is SourceUnavailable -> DownloadFailure.SOURCE
        is java.io.FileNotFoundException -> DownloadFailure.OTHER
        is IOException -> DownloadFailure.NETWORK
        else -> DownloadFailure.OTHER
    }
    private fun message(error: Exception): String = when {
        error is SourceUnavailable -> error.message.orEmpty()
        error is java.net.SocketTimeoutException -> "网络超时，点击继续重试"
        error is java.net.UnknownHostException -> "网络不可用或无法解析资源域名"
        error is SecurityException -> "无法访问公共下载目录，请检查存储权限后继续"
        error is java.io.FileNotFoundException || error.message?.contains("ENOSPC") == true -> "无法写入文件，请检查存储空间"
        error is IllegalArgumentException || error is IllegalStateException -> error.message?.take(150) ?: "资源格式不支持"
        error is MediaHttp.HttpFailure -> error.message!!.take(150)
        else -> "网络中断或文件写入失败，请检查网络和存储后继续"
    }
}
