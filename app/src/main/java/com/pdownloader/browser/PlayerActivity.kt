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
    private var position = 0L
    private var play = true
    private var task: com.pdownloader.browser.download.DownloadTask? = null
    private lateinit var header: LinearLayout
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        play = savedInstanceState?.getBoolean("playing", true) ?: true
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
        @Suppress("DEPRECATION")
        window.statusBarColor = color(R.color.video_background)
        @Suppress("DEPRECATION")
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
        header = row().apply { contentDescription = "播放器标题栏" }
        header.addView(BrowserIcon(this, "back", "返回浏览器") { finish() }.apply { foregroundColor = color(R.color.video_foreground) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        title = label(task!!.title, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(color(R.color.video_foreground)) }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
        listOf(false, true).forEach { next ->
            val button = BrowserIcon(this, if (next) "next_video" else "previous_video", if (next) "下一个视频" else "上一个视频") { switchVideo(next) }
                .apply { foregroundColor = color(R.color.video_foreground) }
            navigation.add(button to next)
            header.addView(button, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        layout.addView(header)
        if (task!!.state != TaskState.COMPLETE) header.addView(BrowserIcon(this, "external", "打开来源网页") {
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(task!!.context.pageUrl))); finish()
        }.apply { foregroundColor = color(R.color.video_foreground) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        playerView = (layoutInflater.inflate(R.layout.browser_player_view, layout, false) as PlayerView).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setKeepContentOnPlayerReset(true)
        }
        listOf(R.id.player_previous to false, R.id.player_next to true).forEach { (id, next) ->
            val button = playerView.findViewById<View>(id)
            button.setOnClickListener { switchVideo(next) }
            navigation.add(button to next)
        }
        updateNavigation()
        val gestures = SeekGestureFrame(this, read = { player?.takeIf { canSeek }?.let { SeekPosition(it.currentPosition, it.duration) } }, commit = { seekToPosition(it) })
        gestures.addView(playerView, android.widget.FrameLayout.LayoutParams(-1, -1))
        gestures.installHint()
        gestures.excluded = { x, y ->
            val controls = playerView.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
            val rect = android.graphics.Rect()
            controls?.isShown == true && controls.getGlobalVisibleRect(rect) && rect.contains(x.toInt(), y.toInt())
        }
        layout.addView(gestures, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(layout)
        playerView.setFullscreenButtonClickListener { setPlayerFullscreen(it) }
        playerView.findViewById<DefaultTimeBar>(androidx.media3.ui.R.id.exo_progress)?.addListener(object : TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
            override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) { if (!canceled) seekToPosition(position) }
        })
        setPlayerFullscreen(savedInstanceState?.getBoolean("fullscreen") ?: false)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { if (fullscreen) setPlayerFullscreen(false) else finish() }
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
            engine.prepare(); engine.playWhenReady = play
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun seekToPosition(positionMs: Long): Boolean {
        val engine = player ?: return false
        if (!canSeek) return false
        val target = positionMs.coerceIn(0, engine.duration)
        if (kotlin.math.abs(engine.currentPosition - target) > 20) engine.seekTo(target)
        position = target
        task?.let { memory.save(it, target) }
        return true
    }
    private fun savePosition() {
        val selected = task ?: return
        val engine = player ?: return
        position = if (engine.playbackState == Player.STATE_ENDED) 0 else engine.currentPosition
        memory.save(selected, position)
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
        savePosition()
        main.removeCallbacks(remember)
        player?.let { play = it.playWhenReady; it.pause() }
        backgroundPaused = true
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
        task?.let { selected ->
            if (selected.state == TaskState.COMPLETE) outState.putString("task", selected.id)
            else outState.putString("preview", DownloadRepository.encode(selected).toString())
        }
        outState.putBoolean("playing", if (backgroundPaused) play else player?.playWhenReady ?: play)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    fun setPlayerFullscreen(value: Boolean) {
        if (value && !fullscreen) previousOrientation = requestedOrientation
        fullscreen = value
        requestedOrientation = if (value) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else previousOrientation
        header.visibility = if (value) View.GONE else View.VISIBLE
        playerView.findViewById<View>(R.id.player_video_navigation).visibility = if (value) View.VISIBLE else View.GONE
        playerView.setFullscreenButtonState(value)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            if (value) { hide(WindowInsets.Type.systemBars()); systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            else show(WindowInsets.Type.systemBars())
        } else window.decorView.systemUiVisibility = if (value) View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY else 0
        window.decorView.requestApplyInsets()
    }
    @Deprecated("Handled by the platform back dispatcher on Android 13+")
    override fun onBackPressed() { if (fullscreen) setPlayerFullscreen(false) else finish() }
    override fun onStop() {
        main.removeCallbacks(remember)
        savePosition()
        player?.let { playerView.player = null; it.release() }; player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}
