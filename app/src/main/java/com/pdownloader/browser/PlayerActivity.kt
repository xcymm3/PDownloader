package com.pdownloader.browser

import android.annotation.SuppressLint
import android.app.Activity
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
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : Activity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var position = 0L
    private var play = true
    private var task: com.pdownloader.browser.download.DownloadTask? = null
    private lateinit var header: LinearLayout
    private lateinit var footer: View
    private var fullscreen = false
    private var backgroundPaused = false
    val canSeek: Boolean get() = player?.let { it.isCurrentMediaItemSeekable && it.duration > 0 && it.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) } == true
    val playbackPositionMs: Long get() = player?.currentPosition ?: position

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        position = savedInstanceState?.getLong("position") ?: 0
        play = savedInstanceState?.getBoolean("playing", true) ?: true
        task = intent.getStringExtra("task")?.let { DownloadRepository.get(this).get(it) }
            ?: intent.getStringExtra("preview")?.let { runCatching { DownloadRepository.decode(it) }.getOrNull() }
        if (task == null) { finish(); return }
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
        header.addView(label(task!!.title, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(color(R.color.video_foreground)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        layout.addView(header)
        if (task!!.state != TaskState.COMPLETE) header.addView(BrowserIcon(this, "external", "打开来源网页") {
            startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(task!!.context.pageUrl))); finish()
        }.apply { foregroundColor = color(R.color.video_foreground) }, LinearLayout.LayoutParams(dp(48), dp(48)))
        playerView = (layoutInflater.inflate(R.layout.browser_player_view, layout, false) as PlayerView).apply {
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setKeepContentOnPlayerReset(true)
        }
        layout.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = label(if (task!!.state == TaskState.COMPLETE) "离线播放" else "在线播放", 12f).apply { gravity = Gravity.CENTER; setTextColor(color(R.color.video_foreground)); alpha = .65f }
        layout.addView(footer, LinearLayout.LayoutParams(-1, dp(24)))
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
        val selected = task ?: return
        val local = selected.state == TaskState.COMPLETE
        val uri = if (local) {
            val file = File(DownloadRepository.get(this).folder(selected.id), selected.output)
            if (!file.exists()) { Toast.makeText(this, "本地文件已不存在", Toast.LENGTH_LONG).show(); finish(); return }
            Uri.fromFile(file)
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
        return true
    }
    override fun onPause() { player?.let { play = it.playWhenReady; it.pause() }; backgroundPaused = true; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("position", player?.currentPosition ?: position); outState.putBoolean("fullscreen", fullscreen)
        outState.putBoolean("playing", if (backgroundPaused) play else player?.playWhenReady ?: play)
        super.onSaveInstanceState(outState)
    }
    @Suppress("DEPRECATION")
    fun setPlayerFullscreen(value: Boolean) {
        fullscreen = value
        header.visibility = if (value) View.GONE else View.VISIBLE
        footer.visibility = if (value) View.GONE else View.VISIBLE
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
        player?.let { position = it.currentPosition; playerView.player = null; it.release() }; player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}
