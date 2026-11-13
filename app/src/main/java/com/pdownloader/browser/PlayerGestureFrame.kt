package com.pdownloader.browser

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import kotlin.math.abs
import kotlin.math.roundToInt

internal interface PlayerGestureHost {
    val gesturesEnabled: Boolean
    fun playbackPosition(): GesturePlaybackPosition?
    fun beginSeek()
    fun seek(positionMs: Long)
    fun endSeek()
    fun brightness(): Float
    fun setBrightness(value: Float): Float
    fun volume(): Float
    fun setVolume(value: Float): Float
    fun togglePlayback(): Boolean
    fun toggleController()
}

/** Native-player gestures; website fullscreen handling stays in SeekGestureFrame. */
@android.annotation.SuppressLint("ViewConstructor")
internal class PlayerGestureFrame(context: Context, private val host: PlayerGestureHost) : FrameLayout(context) {
    var excluded: (Float, Float) -> Boolean = { _, _ -> false }
    private enum class Mode { NONE, SEEK, BRIGHTNESS, VOLUME, BLOCKED }
    private var mode = Mode.NONE
    private val density = resources.displayMetrics.density.toDouble()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var bypass = false
    private var playback: GesturePlaybackPosition? = null
    private var drag: ReversingDrag? = null
    private var target = 0L
    private var lastSeekAt = 0L
    private var seeking = false
    private val tapSeek = DoubleTapSeek(ViewConfiguration.getDoubleTapTimeout().toLong())
    private var consumedTap = Long.MIN_VALUE
    private val message = context.label("", 22f).apply {
        gravity = Gravity.CENTER; setTextColor(context.color(R.color.video_foreground))
    }
    private val brightnessIcon = hintIcon("brightness", "亮度")
    private val volumeIcon = hintIcon("volume", "媒体音量")
    private val meter = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = ColorStateList.valueOf(context.color(R.color.video_foreground))
        progressBackgroundTintList = ColorStateList.valueOf(0x55FFFFFF)
    }
    private val hint = context.column().apply {
        gravity = Gravity.CENTER; background = context.shape(R.color.gallery_controls, 12)
        setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(16))
        visibility = View.GONE; isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val heading = context.row().apply { gravity = Gravity.CENTER }
        heading.addView(brightnessIcon, LinearLayout.LayoutParams(context.dp(32), context.dp(40)))
        heading.addView(volumeIcon, LinearLayout.LayoutParams(context.dp(32), context.dp(40)))
        heading.addView(message, LinearLayout.LayoutParams(-2, context.dp(40)))
        addView(heading, LinearLayout.LayoutParams(-1, -2))
        addView(meter, LinearLayout.LayoutParams(-1, context.dp(3)))
    }
    private val hideHint = Runnable {
        hint.animate().alpha(0f).setDuration(180).withEndAction { hint.visibility = View.GONE }.start()
    }
    private val applyPendingSeek = Runnable {
        if (mode == Mode.SEEK && host.gesturesEnabled) {
            host.seek(target); lastSeekAt = SystemClock.uptimeMillis()
        }
    }
    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val region = PlayerGestureMath.tapRegion(e.x, width)
            if (tapSeek.isContinuing(region, e.eventTime)) {
                consumedTap = e.downTime
                doubleTap(region, e.eventTime)
            }
            return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (e.downTime != consumedTap && host.gesturesEnabled) host.toggleController()
            return true
        }
        override fun onDoubleTap(e: MotionEvent) = true
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP) doubleTap(PlayerGestureMath.tapRegion(e.x, width), e.eventTime)
            return true
        }
    }).apply { setIsLongpressEnabled(false) }

    private fun hintIcon(kind: String, label: String) = BrowserIcon(context, kind, label).apply {
        foregroundColor = context.color(R.color.video_foreground)
        background = null; isClickable = false; isFocusable = false; visibility = View.GONE
    }
    fun installHint() { addView(hint, LayoutParams(context.dp(260), -2, Gravity.CENTER)) }
    private fun showHint(value: String, ratio: Double?, type: Mode = Mode.SEEK) {
        removeCallbacks(hideHint); hint.animate().cancel(); hint.alpha = 1f; hint.visibility = View.VISIBLE
        message.text = value
        brightnessIcon.visibility = if (type == Mode.BRIGHTNESS) View.VISIBLE else View.GONE
        volumeIcon.visibility = if (type == Mode.VOLUME) View.VISIBLE else View.GONE
        meter.visibility = if (ratio == null) View.GONE else View.VISIBLE
        meter.progress = ((ratio ?: 0.0) * 1000).roundToInt().coerceIn(0, 1000)
    }
    internal fun showSeekHint(targetMs: Long, durationMs: Long) = showHint(
        "${formatMediaTime(targetMs)} / ${formatMediaTime(durationMs)}", targetMs.toDouble() / durationMs.coerceAtLeast(1)
    )

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            bypass = !host.gesturesEnabled || excluded(event.rawX, event.rawY)
        }
        return !bypass
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bypass) return super.onTouchEvent(event)
        if (!host.gesturesEnabled) { cancelGesture(); return true }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelGesture(cancelTaps = false)
                downX = event.x; downY = event.y; mode = Mode.NONE
                taps.onTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> { cancelTap(event); finishGesture(); mode = Mode.BLOCKED }
            MotionEvent.ACTION_MOVE -> {
                if (mode == Mode.NONE) {
                    val dx = abs(event.x - downX); val dy = abs(event.y - downY)
                    if (maxOf(dx, dy) <= slop) return true
                    cancelTap(event); tapSeek.reset()
                    if (dx > dy) {
                        val state = host.playbackPosition()
                        if (downX < context.dp(10) || downX > width - context.dp(10) || state == null || state.durationMs <= 0) {
                            mode = Mode.BLOCKED; return true
                        }
                        beginProgress(downX.toDouble() / density, state)
                    } else {
                        beginLevel(downX < width / 2f, -downY / density)
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                when (mode) {
                    Mode.SEEK -> moveProgress(event.x / density)
                    Mode.BRIGHTNESS, Mode.VOLUME -> {
                        moveLevel(-event.y / density)
                    }
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.NONE) { taps.onTouchEvent(event); performClick() }
                else {
                    if (mode == Mode.SEEK) moveProgress(event.x / density)
                    finishGesture()
                }
                mode = Mode.NONE
            }
            MotionEvent.ACTION_CANCEL -> { cancelTap(event); finishGesture(); mode = Mode.NONE }
        }
        return true
    }

    // Also used by native integration tests through state APIs, without injecting touch events.
    internal fun beginProgress(coordinateDp: Double, state: GesturePlaybackPosition) {
        if (!host.gesturesEnabled || state.durationMs <= 0) return
        playback = state; mode = Mode.SEEK; target = state.positionMs
        drag = ReversingDrag(coordinateDp, state.positionMs.toDouble(), PlayerGestureMath.millisPerDp(state.durationMs), state.minimum.toDouble(), state.maximum.toDouble())
        host.beginSeek(); seeking = true; lastSeekAt = 0L
    }
    internal fun moveProgress(coordinateDp: Double) {
        if (mode != Mode.SEEK || !host.gesturesEnabled) return
        target = drag?.move(coordinateDp)?.toLong() ?: return
        showSeekHint(target, playback!!.durationMs)
        removeCallbacks(applyPendingSeek)
        val wait = (100 - (SystemClock.uptimeMillis() - lastSeekAt)).coerceAtLeast(0)
        if (wait == 0L) applyPendingSeek.run() else postDelayed(applyPendingSeek, wait)
    }
    internal fun beginLevel(brightness: Boolean, coordinateDp: Double) {
        if (!host.gesturesEnabled) return
        mode = if (brightness) Mode.BRIGHTNESS else Mode.VOLUME
        val value = if (brightness) host.brightness() else host.volume()
        drag = ReversingDrag(coordinateDp, value.toDouble(), if (brightness) 1.0 / 200 else 1.0 / 300, 0.0, 1.0)
    }
    internal fun moveLevel(coordinateDp: Double) {
        if (!host.gesturesEnabled || mode !in setOf(Mode.BRIGHTNESS, Mode.VOLUME)) return
        val requested = drag?.move(coordinateDp)?.toFloat() ?: return
        val actual = if (mode == Mode.BRIGHTNESS) host.setBrightness(requested) else host.setVolume(requested)
        drag?.applied(actual.toDouble())
        showHint("${(actual * 100).roundToInt()}%", actual.toDouble(), mode)
    }
    internal fun finishGesture() {
        removeCallbacks(applyPendingSeek)
        if (seeking) {
            // A canceled gesture keeps the most recently requested position and restores play intent.
            try { host.seek(target) } finally { seeking = false; host.endSeek() }
        }
        drag = null; playback = null; mode = Mode.NONE
        parent?.requestDisallowInterceptTouchEvent(false)
        removeCallbacks(hideHint); postDelayed(hideHint, 400)
    }
    internal fun doubleTap(region: Int, time: Long) {
        if (!host.gesturesEnabled) return
        if (region == 0) {
            tapSeek.reset()
            showHint(if (host.togglePlayback()) "播放" else "暂停", null)
        } else {
            val state = host.playbackPosition()?.takeIf { it.durationMs > 0 } ?: return
            val (position, delta) = tapSeek.step(state, region, time)
            host.seek(position); host.endSeek()
            showHint("${if (delta >= 0) "快进" else "快退"} ${abs(delta) / 1000} 秒", null)
        }
        removeCallbacks(hideHint); postDelayed(hideHint, 650)
    }
    private fun cancelTap(event: MotionEvent) {
        MotionEvent.obtain(event).also { it.action = MotionEvent.ACTION_CANCEL; taps.onTouchEvent(it); it.recycle() }
    }
    internal fun cancelGesture(cancelTaps: Boolean = true) {
        finishGesture()
        if (cancelTaps) {
            val now = SystemClock.uptimeMillis()
            MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0).also { taps.onTouchEvent(it); it.recycle() }
            tapSeek.reset()
        }
        removeCallbacks(hideHint); hint.animate().cancel(); hint.visibility = View.GONE
    }
    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (host.gesturesEnabled && host.playbackPosition() != null) {
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
            info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
        }
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        val state = host.playbackPosition()
        if (host.gesturesEnabled && state != null && action in setOf(
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,
            android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            val delta = if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) 10000 else -10000
            host.seek((state.positionMs + delta).coerceIn(state.minimum, state.maximum)); host.endSeek()
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (oldw > 0 && (w != oldw || h != oldh)) cancelGesture()
        super.onSizeChanged(w, h, oldw, oldh)
    }
    override fun onDetachedFromWindow() {
        cancelGesture(); tapSeek.reset()
        super.onDetachedFromWindow()
    }
    override fun performClick(): Boolean = super.performClick()
}
