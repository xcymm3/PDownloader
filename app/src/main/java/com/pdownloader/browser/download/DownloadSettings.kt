package com.pdownloader.browser.download

import android.content.Context

internal class DownloadSettings(context: Context) {
    val preferences = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)

    var parallelDownloads: Int
        get() = preferences.getInt(PARALLEL_DOWNLOADS, 2).coerceIn(1, MAX_PARALLEL_DOWNLOADS)
        set(value) {
            require(value in 1..MAX_PARALLEL_DOWNLOADS)
            preferences.edit().putInt(PARALLEL_DOWNLOADS, value).apply()
        }

    companion object {
        const val PARALLEL_DOWNLOADS = "parallel_downloads"
        const val MAX_PARALLEL_DOWNLOADS = 5
    }
}
