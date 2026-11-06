package com.pdownloader.browser

import org.junit.Assert.*
import org.junit.Test

class SeekSessionTest {
    @Test fun dragMovesRelativeToTheStartingPositionAndClampsToTheVideo() {
        val drag = SeekSession(SeekPosition(30000, 60000))
        assertEquals(45000, drag.target(100f, 400))
        assertEquals(0, drag.target(-1000f, 400))
        assertEquals(60000, drag.target(1000f, 400))
    }
    @Test fun longVideosHaveAFiniteTwoMinuteRangePerScreenWidth() {
        assertEquals(660000, SeekSession(SeekPosition(600000, 3600000)).target(200f, 400))
    }
    @Test fun unavailableDurationIsNotSeekable() {
        assertFalse(SeekSession(SeekPosition(0, -1)).valid)
        assertFalse(SeekSession(SeekPosition(0, 0)).valid)
    }
}
