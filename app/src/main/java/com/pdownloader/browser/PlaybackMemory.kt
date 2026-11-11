package com.pdownloader.browser

import android.content.Context
import com.pdownloader.browser.download.DownloadTask
import com.pdownloader.browser.download.TaskState
import com.pdownloader.browser.media.stableId

internal class PlaybackMemory(context: Context) {
    private val preferences = context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)
    private fun key(task: DownloadTask) = if (task.state == TaskState.COMPLETE) task.id
        else stableId(task.variant.url + task.context.pageUrl)
    fun read(task: DownloadTask) = preferences.getLong(key(task), 0).coerceAtLeast(0)
    fun save(task: DownloadTask, position: Long) {
        preferences.edit().putLong(key(task), position.coerceAtLeast(0)).apply()
    }
}
