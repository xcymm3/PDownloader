package com.pdownloader.browser.download

import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Copies encoded samples into MP4 without changing resolution or re-encoding. */
internal object Mp4Output {
    private data class Track(val source: MediaExtractor, val format: MediaFormat, val firstUs: Long)
    private val videoTypes = setOf("video/avc", "video/hevc")
    private val audioTypes = setOf("audio/mp4a-latm")
    private const val MAX_SAMPLE_BYTES = 16 * 1024 * 1024

    fun write(video: File, audio: File?, output: File, durationMs: Long, check: () -> Unit) {
        val opened = mutableListOf<MediaExtractor>()
        try {
            val videoTrack = select(video, videoTypes, opened, check) ?: error("视频轨道无法封装为 MP4")
            val audioTrack = select(audio ?: video, audioTypes, opened, check)
            require(audio == null || audioTrack != null) { "音频轨道无法封装为 MP4" }
            val tracks = listOfNotNull(videoTrack, audioTrack)
            val firstUs = tracks.minOf { it.firstUs }.let { if (audio == null) it else minOf(0L, it) }
            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val format = videoTrack.format
                if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(format.getInteger(MediaFormat.KEY_ROTATION))
                }
                val indices = tracks.map { muxer.addTrack(it.format) }
                muxer.start()
                var buffer = ByteBuffer.allocateDirect(if (Build.VERSION.SDK_INT >= 28) 256 * 1024 else MAX_SAMPLE_BYTES)
                val info = BufferInfo()
                val latest = LongArray(tracks.size) { -1L }
                val transportStream = audio == null && video.inputStream().use { it.read() == 0x47 }
                while (true) {
                    val index = if (tracks.size == 1 || tracks[1].source.sampleTrackIndex < 0 ||
                        tracks[0].source.sampleTrackIndex >= 0 && tracks[0].source.sampleTime <= tracks[1].source.sampleTime) 0 else 1
                    val track = tracks[index]
                    val input = track.source
                    if (input.sampleTrackIndex < 0) break
                    check()
                    val expected = if (Build.VERSION.SDK_INT >= 28) input.sampleSize else null
                    if (expected != null) {
                        require(expected in 1..MAX_SAMPLE_BYTES.toLong()) { "媒体样本大小不支持" }
                        if (buffer.capacity() < expected) buffer = ByteBuffer.allocateDirect(expected.toInt())
                    }
                    buffer.clear()
                    val size = input.readSampleData(buffer, 0)
                    if (size !in 1..buffer.capacity() || expected != null && size.toLong() != expected) throw IOException("媒体样本不完整")
                    require(input.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED == 0) { "视频包含受保护的媒体" }
                    val timeUs = input.sampleTime - firstUs
                    require(timeUs >= 0) { "媒体时间戳不支持" }
                    val flags = if (input.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    if (track === audioTrack && isAdts(track.format, transportStream, buffer, size)) {
                        val frameTime = writeAdts(muxer, indices[index], buffer, size, timeUs, track.format, info)
                        latest[index] = maxOf(latest[index], frameTime + firstUs)
                    } else {
                        info.set(0, size, timeUs, flags)
                        muxer.writeSampleData(indices[index], buffer, info)
                        latest[index] = maxOf(latest[index], input.sampleTime)
                    }
                    input.advance()
                }
                tracks.forEachIndexed { index, track ->
                    require(latest[index] >= track.firstUs) { "媒体轨道没有样本" }
                    if (durationMs > 0) {
                        val spanMs = (latest[index] - track.firstUs) / 1000
                        val tolerance = (durationMs / 20).coerceIn(500, 1500)
                        require(spanMs >= durationMs - tolerance && spanMs <= durationMs + tolerance) { "媒体时长与清单不一致" }
                    }
                }
                check()
                muxer.stop()
            } finally {
                muxer.release()
            }
            require(output.length() > 0) { "MP4 文件为空" }
        } finally {
            opened.forEach { it.release() }
        }
    }

    private fun isAdts(format: MediaFormat, transportStream: Boolean, buffer: ByteBuffer, size: Int): Boolean =
        format.containsKey(MediaFormat.KEY_IS_ADTS) && format.getInteger(MediaFormat.KEY_IS_ADTS) != 0 ||
            transportStream && size >= 7 && isAdtsSync(buffer, 0)

    private fun isAdtsSync(buffer: ByteBuffer, offset: Int) =
        buffer.get(offset).toInt() and 255 == 255 && buffer.get(offset + 1).toInt() and 0xF6 == 0xF0

    /** The platform TS extractor can group several ADTS frames in one buffer. MP4 needs one raw AAC frame per sample. */
    private fun writeAdts(muxer: MediaMuxer, track: Int, data: ByteBuffer, size: Int, startUs: Long,
                          format: MediaFormat, info: BufferInfo): Long {
        val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        require(rate > 0) { "AAC 采样率无效" }
        var offset = 0
        var frames = 0
        var timeUs = startUs
        while (offset < size) {
            require(size - offset >= 7 && isAdtsSync(data, offset)) { "AAC 帧头不完整" }
            fun byte(index: Int) = data.get(offset + index).toInt() and 255
            val header = if (byte(1) and 1 == 1) 7 else 9
            val length = ((byte(3) and 3) shl 11) or (byte(4) shl 3) or (byte(5) shr 5)
            require(byte(6) and 3 == 0 && length > header && length <= size - offset) { "AAC 帧长度不支持" }
            timeUs = startUs + frames * 1_024_000_000L / rate
            info.set(offset + header, length - header, timeUs, android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME)
            muxer.writeSampleData(track, data, info)
            frames++
            offset += length
        }
        return timeUs
    }

    private fun select(file: File, types: Set<String>, opened: MutableList<MediaExtractor>, check: () -> Unit): Track? {
        check()
        val input = MediaExtractor().also { opened.add(it) }
        input.setDataSource(file.absolutePath)
        val prefix = types.first().substringBefore('/') + "/"
        val tracks = (0 until input.trackCount).map { it to input.getTrackFormat(it) }
            .filter { (_, format) -> format.getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }
        if (tracks.isEmpty()) return null
        require(tracks.size == 1) { "媒体含多条同类轨道" }
        val (index, format) = tracks.single()
        require(format.getString(MediaFormat.KEY_MIME) in types) { "媒体编码暂不支持封装为 MP4" }
        input.selectTrack(index)
        require(input.sampleTrackIndex >= 0) { "媒体轨道没有样本" }
        return Track(input, format, input.sampleTime)
    }
}
