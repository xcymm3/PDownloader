package com.pdownloader.browser.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Publishes complete files to the shared Download/PDownloader folder. */
internal class PublicDownloads(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun publish(source: File, name: String, mime: String, check: () -> Unit, remember: (Uri) -> Unit): Uri {
        if (Build.VERSION.SDK_INT < 29) return publishLegacy(source, name, mime, check, remember)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/PDownloader")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = checkNotNull(resolver.insert(collection, values)) { "无法创建公共下载文件" }
        // The worker records this URI before writing and rolls it back if the task does not commit.
        remember(uri)
        checkNotNull(resolver.openOutputStream(uri, "w")) { "无法写入公共下载目录" }.use { copy(source, it, check) }
        check()
        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        require(resolver.update(uri, published, null, null) == 1) { "无法发布公共下载文件" }
        return uri
    }

    @Suppress("DEPRECATION")
    private fun publishLegacy(source: File, name: String, mime: String, check: () -> Unit, remember: (Uri) -> Unit): Uri {
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PDownloader")
        require(directory.isDirectory || directory.mkdirs() || directory.isDirectory) { "无法创建公共下载目录，请检查存储权限" }
        val temporary = File.createTempFile(".download-", ".part", directory)
        try {
            temporary.outputStream().use { copy(source, it, check) }
            check()
            val file = synchronized(legacyLock) {
                val stem = name.substringBeforeLast('.')
                val extension = name.substringAfterLast('.', "")
                val target = generateSequence(0) { it + 1 }.map { n ->
                    File(directory, if (n == 0) name else "$stem ($n)" + if (extension.isBlank()) "" else ".$extension")
                }.first { !it.exists() }
                require(temporary.renameTo(target)) { "无法发布公共下载文件" }
                target
            }
            return Uri.fromFile(file).also {
                remember(it)
                MediaScannerConnection.scanFile(context, arrayOf(file.path), arrayOf(mime), null)
            }
        } finally {
            temporary.delete()
        }
    }

    fun delete(uri: Uri) {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            require(!file.exists() || file.delete()) { "无法删除公共下载文件" }
            MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
        } else resolver.delete(uri, null, null)
    }

    private fun copy(source: File, output: OutputStream, check: () -> Unit) {
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check()
                val size = input.read(buffer)
                if (size < 0) break
                output.write(buffer, 0, size)
            }
        }
    }

    private companion object { val legacyLock = Any() }
}

internal fun writeOfflineArchive(folder: File, playlist: String, archive: File, check: () -> Unit = {}) {
    ZipOutputStream(archive.outputStream().buffered()).use { zip ->
        zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
        val parts = folder.listFiles().orEmpty().filter { it.name == playlist || it.name.matches(Regex("part-[0-9]+\\.bin")) }
        require(parts.any { it.name == playlist }) { "本地清单已不存在" }
        val buffer = ByteArray(64 * 1024)
        for (part in parts.sortedBy { it.name }) {
            zip.putNextEntry(ZipEntry(part.name))
            part.inputStream().use { input ->
                while (true) {
                    check()
                    val size = input.read(buffer)
                    if (size < 0) break
                    zip.write(buffer, 0, size)
                }
            }
            zip.closeEntry()
        }
    }
}
