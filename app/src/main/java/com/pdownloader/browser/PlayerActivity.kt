package com.pdownloader.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.media.AudioManager
import android.provider.Settings
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.TimeBar
import com.pdownloader.browser.download.DownloadRepository
import com.pdownloader.browser.download.TaskState
import com.pdownloader.browser.media.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : ThemedActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var gestures: PlayerGestureFrame
    private var gestureSeekPlayer: ExoPlayer? = null
    private var gestureWasPlaying = false
    private var volumeWarningShown = false
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val playbackSettings by lazy { PlaybackSettings(this) }
    private val gestureHost = object : PlayerGestureHost {
        override val gesturesEnabled get() = playbackSettings.gesturesEnabled && !controlsLocked && !backgroundPaused && player?.let {
            it.playbackState != Player.STATE_IDLE && it.playerError == null
        } == true
        override fun playbackPosition() = player?.takeIf { canSeek }?.let { GesturePlaybackPosition(it.currentPosition, it.duration) }
        override fun beginSeek() { beginGestureSeek() }
        override fun seek(positionMs: Long) { seekToPosition(positionMs, remember = false) }
        override fun endSeek() { endGestureSeek() }
        override fun brightness(): Float {
            val current = window.attributes.screenBrightness
            return if (current >= 0) current.coerceIn(0f, 1f) else
                (Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(0f, 1f)
        }
        override fun setBrightness(value: Float): Float {
            val next = value.coerceIn(0f, 1f)
            window.attributes = window.attributes.apply { screenBrightness = next }
            return next
        }
        override fun volume(): Float {
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maximum
        }
        override fun setVolume(value: Float): Float {
            val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val minimum = if (Build.VERSION.SDK_INT >= 28) audio.getStreamMinVolume(AudioManager.STREAM_MUSIC) else 0
            try {
                val target = (value * maximum).roundToInt().coerceIn(minimum, maximum)
                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != target) audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            } catch (_: SecurityException) {
                if (!volumeWarningShown) Toast.makeText(this@PlayerActivity, "设备限制了媒体音量调节", Toast.LENGTH_SHORT).show()
                volumeWarningShown = true
            }
            return volume()
        }
        override fun togglePlayback(): Boolean {
            val engine = player ?: return false
            if (!engine.playWhenReady && engine.playbackState == Player.STATE_ENDED) engine.seekTo(0)
            engine.playWhenReady = !engine.playWhenReady
            play = engine.playWhenReady
            return play
        }
        override fun toggleController() {
            if (playerView.isControllerFullyVisible) playerView.hideController() else playerView.showController()
        }
    }
    private var position = 0L
    private var play = true
    private var task: com.pdownloader.browser.download.DownloadTask? = null
    private lateinit var lockButton: BrowserIcon
    private var controlsLocked = false
    private var playbackSpeed = 1f
    private lateinit var title: TextView
    private val memory by lazy { PlaybackMemory(this) }
    private var playlist = emptyList<com.pdownloader.browser.download.DownloadTask>()
    private val navigation = mutableListOf<Pair<View, Boolean>>()
    private val main = Handler(Looper.getMainLooper())
    private val remember = object : Runnable {
        override fun run() { savePosition(); main.postDelayed(this, 5000) }
    }
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    private var fullscreen = false
    private var backgroundPaused = false
    val canSeek: Boolean get() = player?.let { it.isCurrentMediaItemSeekable && it.duration > 0 && it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } == true
    val playbackPositionMs: Long get() = player?.currentPosition ?: position

    @SuppressLint("SourceLockedOrientationActivity")
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        play = savedInstanceState?.getBoolean("playing", true) ?: true
        playbackSpeed = savedInstanceState?.getFloat("speed", 1f) ?: 1f
        savedInstanceState?.getFloat("brightness", -1f)?.takeIf { it >= 0 }?.let { value ->
            window.attributes = window.attributes.apply { screenBrightness = value }
        }
        volumeControlStream = AudioManager.STREAM_MUSIC
        task = (savedInstanceState?.getString("task") ?: intent.getStringExtra("task"))?.let { DownloadRepository.get(this).get(it) }
            ?: (savedInstanceState?.getString("preview") ?: intent.getStringExtra("preview"))?.let { runCatching { DownloadRepository.decode(it) }.getOrNull() }
        if (task == null) { finish(); return }
        position = savedInstanceState?.getLong("position") ?: memory.read(task!!)
        if (task!!.state == TaskState.COMPLETE) {
            playlist = DownloadRepository.get(this).all().filter { it.state == TaskState.COMPLETE && it.variant.kind.isVideo }
        } else {
            playlist = runCatching {
                val items = org.json.JSONArray(intent.getStringExtra("previewQueue") ?: "[]")
                (0 until minOf(items.length(), 40)).map { DownloadRepository.decode(items.getJSONObject(it).toString()) }
                    .filter { it.variant.kind.isVideo && it.variant.blockedReason.isEmpty() }
            }.getOrDefault(emptyList())
        }
        val layout = column().apply { setBackgroundColor(color(R.color.video_background)) }
        window.statusBarColor = color(R.color.video_background)
        window.navigationBarColor = color(R.color.video_background)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        layout.setOnApplyWindowInsetsListener { _, insets ->
            // Reserve stable bar space even while immersive bars are transiently visible.
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                layout.setPadding(safe.left, safe.top, safe.right, safe.bottom)
                window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            } else {
                @Suppress("DEPRECATION")
                layout.setPadding(insets.stableInsetLeft, insets.stableInsetTop, insets.stableInsetRight, insets.stableInsetBottom)
            }
            insets
        }
        playerView = (layoutInflater.inflate(R.layout.browser_player_view, layout, false) as PlayerView).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setKeepContentOnPlayerReset(true)
        }
        title = playerView.findViewById<TextView>(R.id.player_title).apply { text = task!!.title }
        playerView.findViewById<View>(R.id.player_back).setOnClickListener { handleBack() }
        playerView.findViewById<View>(R.id.player_fullscreen_toggle).setOnClickListener { setPlayerFullscreen(!fullscreen) }
        playerView.findViewById<View>(R.id.player_speed).setOnClickListener { showPlaybackSpeeds() }
        updateSpeedLabel()
        listOf(R.id.player_previous to false, R.id.player_next to true).forEach { (id, next) ->
            val button = playerView.findViewById<View>(id)
            button.setOnClickListener { switchVideo(next) }
            navigation.add(button to next)
        }
        updateNavigation()
        gestures = PlayerGestureFrame(this, gestureHost)
        gestures.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, -1))
        gestures.installHint()
        lockButton = BrowserIcon(this, "lock", "锁定播放控件") { setControlsLocked(!controlsLocked) }.apply {
            foregroundColor = color(R.color.video_foreground)
        }
        gestures.addView(lockButton, android.widget.FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dp(12) })
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            lockButton.visibility = if (controlsLocked || visibility == View.VISIBLE) View.VISIBLE else View.GONE
        })
        gestures.excluded = { x, y ->
            val rect = android.graphics.Rect()
            controlsLocked || listOf<View?>(lockButton,
                playerView.findViewById(R.id.player_title_bar),
                playerView.findViewById(androidx.media3.ui.R.id.exo_bottom_bar)
            ).any { control -> control?.isShown == true && control.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt()) }
        }
        layout.addView(gestures, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { if (!canceled) seekToPosition(position) }
        })
        setPlayerFullscreen(savedInstanceState?.getBoolean("fullscreen") ?: false)
        setControlsLocked(savedInstanceState?.getBoolean("locked") ?: false)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { handleBack() }
    }
    override fun onStart() {
        super.onStart()
        backgroundPaused = false
        startPlayer()
        main.post(remember)
    }

    private fun startPlayer() {
        val selected = task ?: return
        val local = selected.state == TaskState.COMPLETE
        val uri = if (local) {
            val source = DownloadRepository.get(this).playbackUri(selected)
            val readable = runCatching { contentResolver.openFileDescriptor(source, "r")?.use { true } == true }.getOrDefault(false)
            if (!readable) { Toast.makeText(this, "本地文件已不存在", Toast.LENGTH_LONG).show(); finish(); return }
            source
        } else Uri.parse(selected.variant.url)
        val http = MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG)
        val factory = DefaultDataSource.Factory(this, DataSource.Factory { BrowserMediaSource(http, selected.context) })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setSeekBackIncrementMs(10000).setSeekForwardIncrementMs(10000).build().also { engine ->
            playerView.player = engine
            val item = MediaItem.Builder().setUri(uri)
            if (selected.variant.kind == MediaKind.HLS) item.setMimeType(MimeTypes.APPLICATION_M3U8)
            engine.setMediaItem(item.build()); engine.seekTo(position)
            engine.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) memory.save(selected, 0)
                }
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, if (local) "本地视频无法播放，可能为不支持的编码或文件已损坏" else "在线预览失败，请返回来源网页播放", Toast.LENGTH_LONG).show()
                }
            })
            engine.setPlaybackSpeed(playbackSpeed)
            engine.prepare(); engine.playWhenReady = play
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun seekToPosition(positionMs: Long, remember: Boolean = true): Boolean {
        val engine = player ?: return false
        if (!canSeek) return false
        val target = positionMs.coerceIn(0, engine.duration)
        if (kotlin.math.abs(engine.currentPosition - target) > 20) engine.seekTo(target)
        position = target
        if (remember) task?.let { memory.save(it, target) }
        return true
    }
    internal fun beginGestureSeek() {
        val engine = player?.takeIf { canSeek } ?: return
        if (gestureSeekPlayer != null) return
        gestureSeekPlayer = engine; gestureWasPlaying = engine.playWhenReady
        engine.pause()
    }
    internal fun endGestureSeek() {
        val engine = gestureSeekPlayer
        gestureSeekPlayer = null
        if (engine != null && player === engine) {
            play = gestureWasPlaying
            if (!backgroundPaused) engine.playWhenReady = gestureWasPlaying
        }
        savePosition()
    }
    private fun savePosition() {
        val selected = task ?: return
        val engine = player ?: return
        position = if (engine.playbackState == Player.STATE_ENDED) 0 else engine.currentPosition
        memory.save(selected, position)
    }

    internal fun setControlsLocked(value: Boolean) {
        if (value) gestures.cancelGesture()
        controlsLocked = value
        playerView.useController = !value
        if (value) playerView.hideController() else playerView.showController()
        lockButton.selectedIcon = value
        lockButton.contentDescription = if (value) "解锁播放控件" else "锁定播放控件"
        lockButton.tooltipText = lockButton.contentDescription
        lockButton.visibility = View.VISIBLE
    }

    private fun handleBack() {
        when {
            controlsLocked -> setControlsLocked(false)
            fullscreen -> setPlayerFullscreen(false)
            else -> finish()
        }
    }

    private fun showPlaybackSpeeds() {
        val speeds = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        android.app.AlertDialog.Builder(this).setTitle("播放倍速")
            .setSingleChoiceItems(speeds.map { "${it}×" }.toTypedArray(), speeds.indexOfFirst { it == playbackSpeed }) { dialog, which ->
                setPlaybackSpeed(speeds[which]); dialog.dismiss()
            }.setNegativeButton("取消", null).show()
    }

    internal fun setPlaybackSpeed(value: Float) {
        playbackSpeed = value.coerceIn(.5f, 2f)
        player?.setPlaybackSpeed(playbackSpeed)
        updateSpeedLabel()
    }

    private fun updateSpeedLabel() {
        playerView.findViewById<TextView>(R.id.player_speed).text = if (playbackSpeed == 1f) "倍速" else "${playbackSpeed}×"
    }

    private fun updateNavigation() {
        val index = playlist.indexOfFirst { it.id == task?.id }
        navigation.forEach { (button, next) ->
            button.isEnabled = index >= 0 && (index + if (next) 1 else -1) in playlist.indices
            button.alpha = if (button.isEnabled) 1f else .35f
        }
    }

    internal fun switchVideo(next: Boolean) {
        val index = playlist.indexOfFirst { it.id == task?.id }
        val selected = playlist.getOrNull(index + if (next) 1 else -1)?.takeIf { index >= 0 } ?: return
        gestures.cancelGesture()
        savePosition()
        playerView.player = null
        player?.release()
        player = null
        task = selected
        position = memory.read(selected)
        play = true
        title.text = selected.title
        updateNavigation()
        startPlayer()
    }

    override fun onPause() {
        val intendedPlay = when {
            gestureSeekPlayer != null -> gestureWasPlaying
            else -> player?.playWhenReady ?: play
        }
        backgroundPaused = true
        if (::gestures.isInitialized) {
            gestures.cancelGesture()
        }
        savePosition()
        main.removeCallbacks(remember)
        play = intendedPlay
        player?.pause()
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (backgroundPaused) {
            backgroundPaused = false
            player?.playWhenReady = play
        }
        main.removeCallbacks(remember)
        main.post(remember)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val savedPosition = if (player?.playbackState == Player.STATE_ENDED) 0 else player?.currentPosition ?: position
        outState.putLong("position", savedPosition); outState.putBoolean("fullscreen", fullscreen)
        outState.putFloat("speed", playbackSpeed); outState.putBoolean("locked", controlsLocked)
        outState.putFloat("brightness", window.attributes.screenBrightness)
        task?.let { selected ->
            if (selected.state == TaskState.COMPLETE) outState.putString("task", selected.id)
            else outState.putString("preview", DownloadRepository.encode(selected).toString())
        }
        outState.putBoolean("playing", if (gestureSeekPlayer != null) gestureWasPlaying else if (backgroundPaused) play else player?.playWhenReady ?: play)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    fun setPlayerFullscreen(value: Boolean) {
        if (value && !fullscreen) previousOrientation = requestedOrientation
        fullscreen = value
        requestedOrientation = if (value) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else previousOrientation
        playerView.findViewById<android.widget.ImageButton>(R.id.player_fullscreen_toggle).apply {
            setImageResource(if (value) R.drawable.player_exit_fullscreen else R.drawable.player_fullscreen)
            contentDescription = if (value) "退出全屏" else "全屏播放"
        }
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            if (value) { hide(WindowInsets.Type.systemBars()); systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            else show(WindowInsets.Type.systemBars())
        } else window.decorView.systemUiVisibility = if (value) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        window.decorView.requestApplyInsets()
    }
    @Deprecated("Handled by the platform back dispatcher on Android 13+")
    override fun onBackPressed() { handleBack() }
    override fun onStop() {
        main.removeCallbacks(remember)
        savePosition()
        player?.let { playerView.player = null; it.release() }; player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}
