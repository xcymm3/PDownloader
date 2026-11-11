package com.pdownloader.browser

import kotlin.math.abs

internal object GallerySwipe {
    fun shouldAdvance(distance: Float, velocity: Float, density: Float): Boolean =
        abs(distance) >= 24f * density ||
            (abs(distance) >= 8f * density && abs(velocity) >= 300f * density && distance * velocity > 0)
}
