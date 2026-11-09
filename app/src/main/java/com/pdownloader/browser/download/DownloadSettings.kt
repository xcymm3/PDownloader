package com.pdownloader.browser.download

import android.content.Context

internal class DownloadSettings(context: Context) {
    val preferences = context.getSharedPreferences("download_settings", Context.MODE_PRIVATE)

    var parallelDownloads: Int
        get() = preferences.getInt(PARALLEL_DOWNLOADS, MAX_PARALLEL_DOWNLOADS).coerceIn(1, MAX_PARALLEL_DOWNLOADS)
        set(value) {
            require(value in 1..MAX_PARALLEL_DOWNLOADS)
            preferences.edit().putInt(PARALLEL_DOWNLOADS, value).apply()
        }

    var saveToPublic: Boolean
        get() = preferences.getBoolean("save_to_public", true)
        set(value) { preferences.edit().putBoolean("save_to_public", value).apply() }

    var notifyCompletion: Boolean
        get() = preferences.getBoolean("notify_completion", false)
        set(value) { preferences.edit().putBoolean("notify_completion", value).apply() }

    var warnOnMetered: Boolean
        get() = preferences.getBoolean("warn_on_metered", false)
        set(value) { preferences.edit().putBoolean("warn_on_metered", value).apply() }

    companion object {
        const val PARALLEL_DOWNLOADS = "parallel_downloads"
        const val MAX_PARALLEL_DOWNLOADS = 8
    }
}
