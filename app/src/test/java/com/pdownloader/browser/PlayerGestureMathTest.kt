package com.pdownloader.browser

import org.junit.Assert.*
import org.junit.Test

class PlayerGestureMathTest {
    @Test fun sensitivityFollowsDurationWithALowerAndUpperLimit() {
        assertEquals(100.0, PlayerGestureMath.millisPerDp(30000), 0.0)
        assertEquals(100.0, PlayerGestureMath.millisPerDp(60000), 0.0)
        assertEquals(500.0, PlayerGestureMath.millisPerDp(300000), 0.0)
        assertEquals(1000.0, PlayerGestureMath.millisPerDp(600000), 0.0)
        assertEquals(1000.0, PlayerGestureMath.millisPerDp(3600000), 0.0)
    }
    @Test fun oneHundredDpSeeksFiftySecondsInAFiveMinuteVideo() {
        val drag = ReversingDrag(0.0, 10000.0, PlayerGestureMath.millisPerDp(300000), 0.0, 300000.0)
        assertEquals(60000.0, drag.move(100.0), 0.0)
        assertEquals(59500.0, drag.move(99.0), 0.0)
    }
    @Test fun reversalImmediatelyLeavesTheEndDespitePreviousOvershoot() {
        val drag = ReversingDrag(0.0, 590000.0, 1000.0, 0.0, 600000.0)
        assertEquals(600000.0, drag.move(100.0), 0.0)
        assertEquals(600000.0, drag.move(200.0), 0.0)
        assertEquals(599000.0, drag.move(199.0), 0.0)
        assertEquals(600000.0, drag.move(200.0), 0.0)
    }
    @Test fun reversalImmediatelyLeavesTheStart() {
        val drag = ReversingDrag(0.0, 1000.0, 1000.0, 0.0, 600000.0)
        assertEquals(0.0, drag.move(-100.0), 0.0)
        assertEquals(1000.0, drag.move(-99.0), 0.0)
    }
    @Test fun brightnessChangesByHalfForOneHundredDpAndReversesAtTheLimit() {
        val drag = ReversingDrag(0.0, .4, 1.0 / 200, 0.0, 1.0)
        assertEquals(.9, drag.move(100.0), .0001)
        assertEquals(1.0, drag.move(300.0), 0.0)
        assertEquals(.995, drag.move(299.0), .0001)
    }
    @Test fun volumePreservesSmallMovementsAcrossIntegerDeviceSteps() {
        val drag = ReversingDrag(0.0, .5, 1.0 / 300, 0.0, 1.0)
        drag.move(1.0); drag.applied(.5)
        assertEquals(.6, drag.move(30.0), .0001)
        assertEquals(.833333, drag.move(100.0), .0001)
    }
    @Test fun seekRangeCanRestrictPlaybackToASubsection() {
        val state = GesturePlaybackPosition(30000, 120000, 20000, 80000)
        val drag = ReversingDrag(0.0, state.positionMs.toDouble(), 200.0, state.minimum.toDouble(), state.maximum.toDouble())
        assertEquals(80000.0, drag.move(1000.0), 0.0)
        assertEquals(20000.0, drag.move(-1000.0), 0.0)
    }
    @Test fun doubleTapZonesUseLeftCenterAndRightThirds() {
        assertEquals(-1, PlayerGestureMath.tapRegion(99f, 300))
        assertEquals(0, PlayerGestureMath.tapRegion(150f, 300))
        assertEquals(1, PlayerGestureMath.tapRegion(201f, 300))
    }
    @Test fun successiveSideTapsAccumulateAndChangingDirectionResetsTheCount() {
        val taps = DoubleTapSeek(300)
        val state = GesturePlaybackPosition(30000, 120000)
        assertEquals(40000L to 10000L, taps.step(state, 1, 1000))
        assertEquals(50000L to 20000L, taps.step(state, 1, 1200))
        assertEquals(40000L to -10000L, taps.step(state.copy(positionMs = 50000), -1, 1300))
        assertEquals(70000L to 10000L, taps.step(state.copy(positionMs = 60000), 1, 2000))
    }
    @Test fun doubleTapRespectsPlaybackBoundaries() {
        val taps = DoubleTapSeek(300)
        assertEquals(80000L to 1000L, taps.step(GesturePlaybackPosition(79000, 120000, 20000, 80000), 1, 0))
        assertEquals(20000L to -1000L, taps.step(GesturePlaybackPosition(21000, 120000, 20000, 80000), -1, 100))
    }
}
