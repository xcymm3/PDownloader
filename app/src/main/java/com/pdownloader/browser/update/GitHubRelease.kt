package com.pdownloader.browser.update

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

internal data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val pattern = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
        fun parse(value: String): ReleaseVersion? {
            val parts = pattern.matchEntire(value)?.groupValues ?: return null
            return ReleaseVersion(parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null, parts[3].toIntOrNull() ?: return null)
        }
    }
}

internal data class AppRelease(val tag: String, val version: ReleaseVersion, val size: Long, val sha256: String) {
    val assetName get() = "PDownloader-$version.apk"
    val downloadUrl get() = "$PROJECT_URL/releases/download/$tag/$assetName"

    fun toJson() = JSONObject().put("tag", tag).put("size", size).put("sha256", sha256)

    companion object {
        private const val MAX_APK_SIZE = 150L * 1024 * 1024
        private val hexDigest = Regex("[0-9a-f]{64}")

        fun read(tag: String, size: Long, sha256: String): AppRelease? {
            val version = ReleaseVersion.parse(tag) ?: return null
            if (size !in 1..MAX_APK_SIZE || !hexDigest.matches(sha256)) return null
            return AppRelease(tag, version, size, sha256)
        }
    }
}

internal const val PROJECT_URL = "https://github.com/xcymm3/PDownloader"
private const val LATEST_RELEASE = "https://api.github.com/repos/xcymm3/PDownloader/releases/latest"

internal class UpdateProblem(message: String) : Exception(message)

internal fun fetchNewRelease(installed: ReleaseVersion): AppRelease? {
    val connection = URL(LATEST_RELEASE).openConnection() as HttpsURLConnection
    try {
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "PDownloader/$installed")
        if (connection.responseCode == 404) return null
        if (connection.responseCode != 200) throw UpdateProblem("GitHub 暂时无法提供版本信息，请稍后重试")
        val json = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                if (output.size() + size > 512 * 1024) throw UpdateProblem("GitHub 版本信息过大")
                output.write(buffer, 0, size)
            }
            JSONObject(output.toString(Charsets.UTF_8.name()))
        }
        return parseNewRelease(json, installed)
    } finally {
        connection.disconnect()
    }
}

internal fun parseNewRelease(json: JSONObject, installed: ReleaseVersion): AppRelease? {
    if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
    val tag = json.optString("tag_name")
    val version = ReleaseVersion.parse(tag) ?: throw UpdateProblem("GitHub 正式版本的标签格式无效")
    if (version <= installed) return null
    val assets = json.optJSONArray("assets")
    for (index in 0 until (assets?.length() ?: 0)) {
        val asset = assets?.optJSONObject(index) ?: continue
        if (asset.optString("name") != "PDownloader-$version.apk" || asset.optString("state") != "uploaded") continue
        val digest = asset.optString("digest")
        if (!digest.startsWith("sha256:")) continue
        val release = AppRelease.read(tag, asset.optLong("size"), digest.removePrefix("sha256:")) ?: continue
        if (asset.optString("browser_download_url") == release.downloadUrl) return release
    }
    throw UpdateProblem("检测到新版 $version，但 GitHub 尚未提供可校验的安装包")
}
