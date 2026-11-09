package com.pdownloader.browser.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

internal fun prepareUpdate(context: Context, downloadId: Long, release: AppRelease, input: InputStream): File {
    val directory = File(context.cacheDir, "app-updates")
    check(directory.isDirectory || directory.mkdirs()) { "无法创建更新缓存目录" }
    val temporary = File.createTempFile("verify-", ".apk", directory)
    try {
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        temporary.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                copied += size
                if (copied > release.size) throw UpdateProblem("更新包大小不符，请重新下载")
                digest.update(buffer, 0, size)
                output.write(buffer, 0, size)
            }
        }
        if (copied != release.size || digest.digest().toHex() != release.sha256) {
            throw UpdateProblem("更新包完整性校验失败，请重新下载")
        }
        verifyUpgrade(context, temporary, release.version)
        val target = File(directory, "$downloadId.apk")
        if (!temporary.renameTo(target)) throw UpdateProblem("无法保存已校验的更新包")
        return target
    } finally {
        temporary.delete()
    }
}

@Suppress("DEPRECATION")
internal fun verifyUpgrade(context: Context, apk: File, release: ReleaseVersion) {
    val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
    val manager = context.packageManager
    val archive = manager.getPackageArchiveInfo(apk.path, flags) ?: throw UpdateProblem("无法读取更新包或验证 APK 签名")
    val installed = manager.getPackageInfo(context.packageName, flags)
    if (archive.packageName != installed.packageName) throw UpdateProblem("更新包的应用 ID 与当前安装不同，已停止安装")
    val expected = installed.signers()
    if (expected.isEmpty() || archive.signers() != expected) throw UpdateProblem("更新包签名与当前安装不同，已停止安装")
    if (archive.code() <= installed.code() || ReleaseVersion.parse(archive.versionName.orEmpty()) != release) {
        throw UpdateProblem("更新包版本与 GitHub 发布信息不符，或当前版本已经更新")
    }
}

@Suppress("DEPRECATION")
private fun PackageInfo.code() = if (Build.VERSION.SDK_INT >= 28) longVersionCode else versionCode.toLong()

@Suppress("DEPRECATION")
private fun PackageInfo.signers(): Set<Signature> =
    (if (Build.VERSION.SDK_INT >= 28) signingInfo?.apkContentsSigners else signatures)?.toSet().orEmpty()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
