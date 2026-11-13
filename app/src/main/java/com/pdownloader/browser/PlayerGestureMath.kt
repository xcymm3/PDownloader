package com.pdownloader.browser

import kotlin.math.sign

/** Pure gesture calculations, independent of screen density and Android input events. */
internal object PlayerGestureMath {
    fun millisPerDp(durationMs: Long) = (durationMs / 600.0).coerceIn(100.0, 1000.0)
    fun tapRegion(x: Float, width: Int): Int = when {
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }
}

/** Rebase at the previous event on reversal so the first reversed movement takes effect. */
internal class ReversingDrag(
    coordinate: Double, initial: Double, private val unitsPerDp: Double,
    private val minimum: Double, private val maximum: Double
) {
    private var anchorCoordinate = coordinate
    private var anchorValue = initial.coerceIn(minimum, maximum)
    private var lastCoordinate = coordinate
    private var direction = 0.0
    var value = anchorValue
        private set

    fun move(coordinate: Double): Double {
        val movement = coordinate - lastCoordinate
        if (movement == 0.0) return value
        val nextDirection = movement.sign
        if (direction != 0.0 && nextDirection != direction) {
            anchorCoordinate = lastCoordinate
            anchorValue = value
        }
        value = (anchorValue + (coordinate - anchorCoordinate) * unitsPerDp).coerceIn(minimum, maximum)
        lastCoordinate = coordinate
        direction = nextDirection
        return value
    }

    // Reflect integer device-volume steps without discarding sub-step movement in the same direction.
    fun applied(actual: Double) { value = actual.coerceIn(minimum, maximum) }
}

internal data class GesturePlaybackPosition(
    val positionMs: Long, val durationMs: Long,
    val startMs: Long = 0, val endMs: Long = durationMs
) {
    val minimum get() = startMs.coerceIn(0, durationMs.coerceAtLeast(0))
    val maximum get() = endMs.coerceIn(minimum, durationMs.coerceAtLeast(0))
}

internal class DoubleTapSeek(private val timeoutMs: Long) {
    private var lastTime = Long.MIN_VALUE
    private var lastDirection = 0
    private var origin = 0L
    private var target = 0L
    fun isContinuing(direction: Int, time: Long) = direction != 0 && direction == lastDirection &&
        lastTime != Long.MIN_VALUE && time >= lastTime && time - lastTime <= timeoutMs

    fun step(position: GesturePlaybackPosition, direction: Int, time: Long): Pair<Long, Long> {
        if (!isContinuing(direction, time)) { origin = position.positionMs; target = origin }
        target = (target.toDouble() + direction * 10000.0).coerceIn(position.minimum.toDouble(), position.maximum.toDouble()).toLong()
        lastDirection = direction; lastTime = time
        return target to (target - origin)
    }
    fun reset() { lastTime = Long.MIN_VALUE; lastDirection = 0 }
}
