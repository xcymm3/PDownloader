package com.pdownloader.browser

import android.content.Context

internal class PlaybackSettings(context: Context) {
    private val preferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    var gesturesEnabled: Boolean
        get() = preferences.getBoolean("touch_gestures", true)
        set(value) { preferences.edit().putBoolean("touch_gestures", value).apply() }
}
