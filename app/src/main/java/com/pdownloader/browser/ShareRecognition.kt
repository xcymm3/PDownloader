package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import com.pdownloader.browser.media.ShareMedia
import com.pdownloader.browser.media.SiteAdapter
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class ShareRecognition(
    private val activity: Activity,
    private val found: (String, String, ShareMedia.Found) -> Unit,
    private val openPage: (String, String?) -> Unit
) {
    private class Lookup(val dialog: AlertDialog) {
        var worker: Future<*>? = null
        var deadline: Runnable? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val workers = Executors.newFixedThreadPool(2)
    private var current: Lookup? = null

    fun start(url: String, userAgent: String, adapter: SiteAdapter) {
        cancel()
        val dialog = AlertDialog.Builder(activity).setTitle("正在识别")
            .setMessage("正在获取分享链接中的资源。")
            .setNegativeButton("取消") { _, _ -> cancel() }
            .setNeutralButton("打开原网页", null)
            .create()
        val lookup = Lookup(dialog)
        current = lookup
        dialog.setOnCancelListener { if (current === lookup) cancel() }
        dialog.setOnDismissListener { if (current === lookup) cancel() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (current === lookup) {
                cancel()
                openPage(url, null)
            }
        }
        lookup.deadline = Runnable {
            if (current === lookup) {
                cancel()
                openPage(url, "识别超时")
            }
        }.also { main.postDelayed(it, 13000) }
        lookup.worker = workers.submit {
            val result = runCatching { adapter.recognize(url, userAgent) }.getOrElse { ShareMedia.OpenPage("识别失败") }
            main.post {
                if (current !== lookup || !dialog.isShowing || activity.isFinishing || activity.isDestroyed) return@post
                cancel()
                when (result) {
                    is ShareMedia.Found -> found(url, userAgent, result)
                    is ShareMedia.OpenPage -> openPage(url, result.message)
                }
            }
        }
    }

    fun cancel() {
        val lookup = current ?: return
        current = null
        lookup.deadline?.let(main::removeCallbacks)
        lookup.worker?.cancel(true)
        lookup.dialog.dismiss()
    }

    fun close() { cancel(); workers.shutdownNow() }
}
