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

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        position = savedInstanceState?.getLong("position") ?: 0
        task = intent.getStringExtra("task")?.let { DownloadRepository.get(this).get(it) }
            ?: intent.getStringExtra("preview")?.let { runCatching { DownloadRepository.decode(it) }.getOrNull() }
        if (task == null) { finish(); return }
        val layout = column().apply { setBackgroundColor(color(R.color.video_background)); fitsSystemWindows = true }
        header = row().apply { contentDescription = "播放器标题栏" }
        header.addView(action("返回") { finish() }, LinearLayout.LayoutParams(-2, dp(52)))
        header.addView(label(task!!.title, 15f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setTextColor(color(R.color.video_foreground)) }, LinearLayout.LayoutParams(0, dp(52), 1f))
        layout.addView(header)
        playerView = PlayerView(this).apply { setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING); setKeepContentOnPlayerReset(true) }
        layout.addView(playerView, LinearLayout.LayoutParams(-1, 0, 1f))
        footer = action(if (task!!.state == TaskState.COMPLETE) "离线播放 · 文件已保存在应用内" else "返回来源网页") {
            if (task!!.state != TaskState.COMPLETE) {
                startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(task!!.context.pageUrl)))
                finish()
            }
        }.apply { gravity = Gravity.CENTER }
        layout.addView(footer, LinearLayout.LayoutParams(-1, dp(52)))
        setContentView(layout)
        playerView.setFullscreenButtonClickListener { setPlayerFullscreen(it) }
        setPlayerFullscreen(savedInstanceState?.getBoolean("fullscreen") ?: false)
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(0) { if (fullscreen) setPlayerFullscreen(false) else finish() }
    }
    override fun onStart() {
        super.onStart()
        val selected = task ?: return
        val local = selected.state == TaskState.COMPLETE
        val uri = if (local) {
            val file = File(DownloadRepository.get(this).folder(selected.id), selected.output)
            if (!file.exists()) { Toast.makeText(this, "本地文件已不存在", Toast.LENGTH_LONG).show(); finish(); return }
            Uri.fromFile(file)
        } else Uri.parse(selected.variant.url)
        val http = MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG)
        val factory = DefaultDataSource.Factory(this, DataSource.Factory { BrowserMediaSource(http, selected.context) })
        player = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().also { engine ->
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
    override fun onPause() { player?.let { play = it.playWhenReady; it.pause() }; super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putLong("position", player?.currentPosition ?: position); outState.putBoolean("fullscreen", fullscreen); super.onSaveInstanceState(outState) }
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
    }
    @Deprecated("Handled by the platform back dispatcher on Android 13+")
    override fun onBackPressed() { if (fullscreen) setPlayerFullscreen(false) else finish() }
    override fun onStop() {
        player?.let { position = it.currentPosition; playerView.player = null; it.release() }; player = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }
}
