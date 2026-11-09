package com.pdownloader.browser.update

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import com.pdownloader.browser.BuildConfig
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

internal class AppUpdateController(private val activity: Activity) {
    private data class Pending(val id: Long, val release: AppRelease, val autoInstall: Boolean)

    private val context = activity.applicationContext
    private val preferences = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val installed = requireNotNull(ReleaseVersion.parse(BuildConfig.VERSION_NAME))
    private val main = Handler(Looper.getMainLooper())
    private var active = false
    private var busy = false
    private var dialog: AlertDialog? = null

    fun resume(automatic: Boolean) {
        active = true
        val pending = pending()
        val permissionFor = preferences.getLong("permission_for", -1)
        if (permissionFor != -1L) preferences.edit().remove("permission_for").apply()
        when {
            pending != null && pending.release.version <= installed -> runWork(
                { discard(pending) },
                { if (automatic) checkAutomatically() }
            )
            pending != null && (pending.autoInstall ||
                permissionFor == pending.id && activity.packageManager.canRequestPackageInstalls()) -> watch(pending)
            permissionFor != -1L -> Unit
            automatic -> checkAutomatically()
        }
    }

    fun pause() {
        active = false
        main.removeCallbacksAndMessages(null)
        dialog?.dismiss()
        dialog = null
    }

    fun check() {
        if (busy) {
            toast("正在处理更新，请稍候")
            return
        }
        val pending = pending()?.takeIf { it.release.version > installed }
        if (pending != null) {
            main.removeCallbacksAndMessages(null)
            watch(pending, manual = true)
        } else {
            checkRelease(manual = true)
        }
    }

    private fun checkAutomatically() {
        val elapsed = System.currentTimeMillis() - preferences.getLong("last_check", 0)
        if (!busy && elapsed !in 0 until CHECK_INTERVAL) checkRelease(manual = false)
    }

    private fun checkRelease(manual: Boolean) {
        runWork({
            try {
                fetchNewRelease(installed).also { if (it == null) rememberCheck() }
            } catch (error: Exception) {
                preferences.edit().putLong("last_check", System.currentTimeMillis() - CHECK_INTERVAL + RETRY_INTERVAL).apply()
                throw error
            }
        }) { result ->
            result.onSuccess { release ->
                if (release == null) {
                    if (manual) toast("当前已是最新版本")
                } else {
                    val ready = pending()?.release == release
                    show(AlertDialog.Builder(activity)
                        .setTitle("检测到新版本 ${release.version}")
                        .setMessage(if (ready) "可以继续安装此更新。" else "立即更新将自动下载安装包。下载完成后，请在 Android 安装页面确认更新。")
                        .setOnCancelListener { rememberCheck() }
                        .setNegativeButton("稍后更新") { _, _ -> rememberCheck() }
                        .setPositiveButton("立即更新") { _, _ ->
                            rememberCheck()
                            startDownload(release)
                        })
                }
            }.onFailure {
                if (manual) toast(message(it, "无法检查更新，请检查网络后重试"))
            }
        }
    }

    @Suppress("DEPRECATION") // Keep private updates out of the Downloads UI on Android 8 and 9.
    private fun startDownload(release: AppRelease) {
        runWork({
            val previous = pending()
            if (previous?.release == release) {
                previous.copy(autoInstall = true).also(::save)
            } else {
                previous?.let(::discard)
                val request = DownloadManager.Request(Uri.parse(release.downloadUrl))
                    .setTitle("PDownloader ${release.version} 更新")
                    .setMimeType("application/octet-stream")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setVisibleInDownloadsUi(false)
                    .setDestinationInExternalFilesDir(
                        context, Environment.DIRECTORY_DOWNLOADS, "app-updates/${release.version}-${System.nanoTime()}.apk"
                    )
                Pending(downloads.enqueue(request), release, autoInstall = true).also(::save)
            }
        }) { result ->
            result.onSuccess {
                toast("正在准备更新")
                watch(it)
            }.onFailure { toast("无法开始下载更新，请在设置中重试") }
        }
    }

    private fun watch(pending: Pending, manual: Boolean = false) {
        if (!active || busy) return
        runWork({
            if (pending()?.id != pending.id) return@runWork null
            try {
                val status = downloads.query(DownloadManager.Query().setFilterById(pending.id))?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else null
                }
                when (status) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> null
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        ParcelFileDescriptor.AutoCloseInputStream(downloads.openDownloadedFile(pending.id)).use {
                            prepareUpdate(context, pending.id, pending.release, it)
                        }
                    }
                    else -> throw UpdateProblem("更新下载失败，请在设置中重试")
                }
            } catch (error: Exception) {
                discard(pending)
                throw error
            }
        }) { result ->
            result.onSuccess { apk ->
                if (pending()?.id != pending.id) return@onSuccess
                if (apk == null) {
                    if (manual) toast("更新正在下载")
                    main.postDelayed({ watch(pending) }, 1500)
                } else {
                    save(pending.copy(autoInstall = false))
                    install(pending.id, apk)
                }
            }.onFailure { toast(message(it, "无法验证更新包，请在设置中重新下载")) }
        }
    }

    private fun install(id: Long, apk: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            show(AlertDialog.Builder(activity)
                .setTitle("允许安装更新")
                .setMessage("更新已下载并通过校验。请在 Android 设置中为 PDownloader 开启「允许来自此来源」，返回后继续安装。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往设置") { _, _ ->
                    preferences.edit().putLong("permission_for", id).apply()
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                    } catch (_: Exception) {
                        preferences.edit().remove("permission_for").apply()
                        toast("无法打开安装权限设置")
                    }
                })
            return
        }
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("PDownloader 更新", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (_: Exception) {
            toast("无法打开 Android 安装器，请在设置中重试")
        }
    }

    private fun pending(): Pending? = runCatching {
        val json = JSONObject(preferences.getString("pending", null) ?: return null)
        val id = json.getLong("id").takeIf { it > 0 } ?: return null
        val release = json.getJSONObject("release")
        val parsed = AppRelease.read(release.getString("tag"), release.getLong("size"), release.getString("sha256")) ?: return null
        Pending(id, parsed, json.getBoolean("auto_install"))
    }.getOrNull()

    private fun save(pending: Pending) {
        val json = JSONObject().put("id", pending.id).put("release", pending.release.toJson()).put("auto_install", pending.autoInstall)
        preferences.edit().putString("pending", json.toString()).apply()
    }

    private fun discard(pending: Pending) {
        if (pending()?.id != pending.id) return
        preferences.edit().remove("pending").remove("permission_for").apply()
        runCatching { downloads.remove(pending.id) }
        File(context.cacheDir, "app-updates/${pending.id}.apk").delete()
    }

    private fun <T> runWork(work: () -> T, deliver: (Result<T>) -> Unit) {
        if (!active || busy) return
        busy = true
        worker.execute {
            val result = runCatching(work)
            activity.runOnUiThread {
                busy = false
                if (active && !activity.isFinishing && !activity.isDestroyed) deliver(result)
            }
        }
    }

    private fun show(builder: AlertDialog.Builder) {
        dialog?.dismiss()
        dialog = builder.show()
    }

    private fun rememberCheck() {
        preferences.edit().putLong("last_check", System.currentTimeMillis()).apply()
    }

    private fun toast(text: String) = Toast.makeText(activity, text, Toast.LENGTH_SHORT).show()

    private fun message(error: Throwable, fallback: String) = (error as? UpdateProblem)?.message ?: fallback

    private companion object {
        const val RETRY_INTERVAL = 60 * 60 * 1000L
        const val CHECK_INTERVAL = 24 * RETRY_INTERVAL
        val worker = Executors.newSingleThreadExecutor()
    }
}
