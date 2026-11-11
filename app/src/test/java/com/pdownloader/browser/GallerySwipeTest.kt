package com.pdownloader.browser

import org.junit.Assert.*
import org.junit.Test

class GallerySwipeTest {
    @Test fun shortSlowSwipesAdvanceInEitherDirection() {
        assertTrue(GallerySwipe.shouldAdvance(24f, 0f, 1f))
        assertTrue(GallerySwipe.shouldAdvance(-48f, 0f, 2f))
    }
    @Test fun quickFlicksNeedOnlyEightDp() {
        assertTrue(GallerySwipe.shouldAdvance(16f, 800f, 2f))
        assertTrue(GallerySwipe.shouldAdvance(-16f, -800f, 2f))
    }
    @Test fun tapsAndTinyMovementsDoNotAdvance() {
        assertFalse(GallerySwipe.shouldAdvance(3f, 1000f, 1f))
        assertFalse(GallerySwipe.shouldAdvance(15f, 100f, 1f))
        assertFalse(GallerySwipe.shouldAdvance(10f, -900f, 1f))
    }
}
