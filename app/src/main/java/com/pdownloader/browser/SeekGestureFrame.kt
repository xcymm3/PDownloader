package com.pdownloader.browser

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.Gravity
import android.widget.FrameLayout
import kotlin.math.abs

data class SeekPosition(val positionMs: Long, val durationMs: Long)

/** The same calculation is used by native and HTML-video gestures. Unknown/live durations cannot seek. */
class SeekSession(private val initial: SeekPosition) {
    val valid get() = initial.durationMs > 0
    fun target(distance: Float, width: Int): Long = (initial.positionMs + distance / width.coerceAtLeast(1) * minOf(initial.durationMs, 120000L)).toLong().coerceIn(0, initial.durationMs.coerceAtLeast(0))
}

/** Intercepts horizontal movement only; taps, vertical motion and controller buttons keep their normal handling. */
@android.annotation.SuppressLint("ViewConstructor")
class SeekGestureFrame(context: Context, private val read: () -> SeekPosition?, private val commit: (Long) -> Unit) : FrameLayout(context) {
    var excluded: (Float, Float) -> Boolean = { _, _ -> false }
    private var downX = 0f; private var downY = 0f
    private var session: SeekSession? = null
    private var blocked = false; private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var target = 0L
    private val hint = context.label("", 18f).apply {
        gravity = Gravity.CENTER; setTextColor(context.color(R.color.video_foreground)); background = context.shape(R.color.gallery_controls, 12)
        setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12)); visibility = View.GONE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    fun installHint() { addView(hint, LayoutParams(-2, -2, Gravity.CENTER)) }
    override fun performClick(): Boolean = super.performClick()
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (read()?.durationMs?.let { it > 0 } == true) {
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val now = read()
        if (now != null && now.durationMs > 0 && action in setOf(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            commit((now.positionMs + if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 10000 else -10000).coerceIn(0, now.durationMs))
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; dragging = false
                blocked = excluded(event.rawX, event.rawY)
                session = if (blocked) null else read()?.takeIf { it.durationMs > 0 }?.let(::SeekSession)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { blocked = true; finish(false) }
            MotionEvent.ACTION_MOVE -> {
                if (!blocked && session != null) {
                    val dx = abs(event.x - downX); val dy = abs(event.y - downY)
                    if (dy > slop && dy > dx) blocked = true
                    if (dx > slop && dx > dy * 1.25f) { dragging = true; parent?.requestDisallowInterceptTouchEvent(true); return true }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(false)
        }
        return false
    }
    // This handles drags only. PlayerView owns taps; accessible seek actions are implemented above.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                target = session?.target(event.x - downX, width) ?: return true
                hint.text = context.getString(R.string.gesture_seek_time, formatMediaTime(target))
                hint.visibility = View.VISIBLE
            }
            MotionEvent.ACTION_UP -> { target = session?.target(event.x - downX, width) ?: target; finish(true) }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finish(false)
        }
        return true
    }
    private fun finish(save: Boolean) {
        if (save && dragging) commit(target)
        dragging = false; session = null; hint.visibility = View.GONE
        parent?.requestDisallowInterceptTouchEvent(false)
    }
}

internal fun formatMediaTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)
}
