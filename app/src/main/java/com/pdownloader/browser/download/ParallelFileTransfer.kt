package com.pdownloader.browser.download

import com.pdownloader.browser.media.MediaHttp
import com.pdownloader.browser.media.RequestContext
import com.pdownloader.browser.media.stableId
import java.io.File
import java.io.FileOutputStream
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class ParallelFileTransfer(private val http: MediaHttp) {
    private class RangeRejected : Exception()

    fun download(
        url: String, resolvedUrl: String, context: RequestContext, destination: File, total: Long, validator: String,
        check: () -> Unit, progress: (Long) -> Unit
    ): Boolean {
        val folder = requireNotNull(destination.parentFile)
        val manifest = File(folder, "ranges.identity")
        val identity = stableId("$url\n$resolvedUrl\n$total\n$validator")
        if (!manifest.isFile || manifest.readText() != identity) {
            clear(folder)
            manifest.writeText(identity)
        }

        val chunkSize = maxOf(MIN_CHUNK, total / MAX_CHUNKS + 1)
        val lastIndex = ((total - 1) / chunkSize).toInt()
        fun length(index: Int) = minOf(chunkSize, total - index * chunkSize)
        val files = (0..lastIndex).map { File(folder, "ranges-$it.bin") }
        val bytes = AtomicLong(files.withIndex().sumOf { (index, file) ->
            if (file.length() > length(index)) require(file.delete()) { "无法清理无效分片" }
            file.length()
        })
        val failure = AtomicReference<Exception>()
        val next = AtomicInteger()
        val count = minOf(WORKERS, files.size)
        val workers = Executors.newFixedThreadPool(count)
        try {
            progress(bytes.get())
            val pending = List(count) {
                workers.submit {
                    try {
                        while (failure.get() == null) {
                            check()
                            val index = next.getAndIncrement()
                            if (index >= files.size) break
                            val file = files[index]
                            val size = length(index)
                            val existing = file.length()
                            if (existing == size) continue
                            val start = index * chunkSize
                            val from = start + existing
                            val to = start + size - 1
                            http.open(url, context, headers = mapOf("Range" to "bytes=$from-$to", "If-Range" to validator)).use { response ->
                                val expectedRange = "bytes $from-$to/$total"
                                if (response.status in setOf(200, 416)) throw RangeRejected()
                                MediaHttp.requireSuccess(response.status)
                                val encoding = response.header("Content-Encoding")
                                if (response.status != 206 || response.url != resolvedUrl || response.header("Content-Range") != expectedRange ||
                                    response.header("ETag") != validator || encoding != null && !encoding.equals("identity", true) ||
                                    response.length != size - existing) throw RangeRejected()
                                var remaining = size - existing
                                response.body.use { input ->
                                    FileOutputStream(file, true).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (remaining > 0) {
                                            check()
                                            if (failure.get() != null) return@submit
                                            val read = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                                            if (read < 0) throw SocketException("分片传输不完整")
                                            output.write(buffer, 0, read)
                                            remaining -= read
                                            progress(bytes.addAndGet(read.toLong()))
                                        }
                                        if (input.read() != -1) throw RangeRejected()
                                    }
                                }
                            }
                        }
                    } catch (error: Exception) {
                        failure.compareAndSet(null, error)
                    }
                }
            }
            pending.forEach { it.get() }
        } finally {
            workers.shutdown()
            // A task may be deleted as soon as this method returns. Finish all file writes first.
            var interrupted = false
            while (!workers.isTerminated) {
                try { workers.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS) }
                catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        check()
        when (val error = failure.get()) {
            is RangeRejected -> { clear(folder); return false }
            null -> Unit
            else -> throw error
        }
        destination.outputStream().buffered().use { output ->
            val buffer = ByteArray(64 * 1024)
            files.forEachIndexed { index, file ->
                require(file.length() == length(index)) { "下载分片长度不一致" }
                file.inputStream().use { input ->
                    while (true) {
                        check()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
        return true
    }

    companion object {
        const val MIN_SIZE = 8L * 1024 * 1024
        private const val MIN_CHUNK = 4L * 1024 * 1024
        private const val MAX_CHUNKS = 2048
        private const val WORKERS = 3
        val slots = Semaphore(2)

        fun validator(response: MediaHttp.Response): String? =
            response.header("ETag")?.takeIf { it.length >= 2 && it.startsWith('"') && it.endsWith('"') }

        fun clear(folder: File, keep: File? = null) {
            folder.listFiles().orEmpty().filter { it.name == "ranges.identity" || it.name.matches(Regex("ranges-[0-9]+\\.bin")) }
                .filter { it != keep }
                .forEach { require(!it.exists() || it.delete()) { "无法清理下载分片" } }
        }
    }
}
