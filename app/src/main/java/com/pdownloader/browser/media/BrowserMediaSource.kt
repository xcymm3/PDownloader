package com.pdownloader.browser.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.InputStream

/** Same scoped, redirect-aware transport for preview and HLS child requests. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BrowserMediaSource(private val http: MediaHttp, private val context: RequestContext) : BaseDataSource(true) {
    private var response: MediaHttp.Response? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val range = if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
            val end = if (dataSpec.length > 0) (dataSpec.position + dataSpec.length - 1).toString() else ""
            mapOf("Range" to "bytes=${dataSpec.position}-$end")
        } else emptyMap()
        try {
            val current = http.open(dataSpec.uri.toString(), context, headers = range)
            response = current
            MediaHttp.requireSuccess(current.status)
            val stream = current.body
            input = stream
            if (current.status == 206) {
                require(current.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull() == dataSpec.position) { "视频服务器返回错误的字节范围" }
            } else if (dataSpec.position > 0) {
                var skip = dataSpec.position
                val buffer = ByteArray(8192)
                while (skip > 0) {
                    val count = stream.read(buffer, 0, minOf(skip, buffer.size.toLong()).toInt())
                    if (count < 0) throw java.io.EOFException()
                    skip -= count
                }
            }
            remaining = if (dataSpec.length >= 0) dataSpec.length else if (current.length >= 0) current.length - if (current.status == 200) dataSpec.position else 0 else C.LENGTH_UNSET.toLong()
            opened = true; transferStarted(dataSpec)
            return remaining
        } catch (error: Exception) { close(); throw java.io.IOException(error.message, error) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = input?.read(buffer, offset, if (remaining >= 0) minOf(length.toLong(), remaining).toInt() else length) ?: C.RESULT_END_OF_INPUT
        if (count > 0) { if (remaining >= 0) remaining -= count; bytesTransferred(count) }
        return count
    }
    override fun getUri(): Uri? = response?.url?.let(Uri::parse)
    override fun getResponseHeaders(): Map<String, List<String>> = response?.connection?.headerFields?.filterKeys { it != null } ?: emptyMap()
    override fun close() {
        try { input?.close() } finally {
            input = null; response?.close(); response = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
