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
    @Test fun longVideosSeekByTheSamePercentageAsTheDragWidth() {
        val drag = SeekSession(SeekPosition(600000, 3600000))
        assertEquals(1500000, drag.target(100f, 400))
        assertEquals(2400000, drag.target(200f, 400))
        assertEquals(1500000, drag.target(200f, 800))
    }
    @Test fun fullWidthDragCanReachEitherEndOfALongVideo() {
        val drag = SeekSession(SeekPosition(1800000, 3600000))
        assertEquals(3600000, drag.target(400f, 400))
        assertEquals(0, drag.target(-400f, 400))
    }
    @Test fun draggingLeftSubtractsAPercentageOfTheTotalDuration() {
        assertEquals(900000, SeekSession(SeekPosition(1800000, 3600000)).target(-100f, 400))
    }
    @Test fun unavailableDurationIsNotSeekable() {
        assertFalse(SeekSession(SeekPosition(0, -1)).valid)
        assertFalse(SeekSession(SeekPosition(0, 0)).valid)
    }
}
