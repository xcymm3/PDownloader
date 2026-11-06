package com.pdownloader.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.webkit.CookieManager
import com.pdownloader.browser.BuildConfig
import com.pdownloader.browser.MainActivity
import com.pdownloader.browser.R
import com.pdownloader.browser.media.MediaHttp
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class BrowserDownloadService : Service() {
    private val executor = transfers
    private val running = AtomicBoolean(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var stopping = false
    @Volatile private var activeId: String? = null
    private var notificationPending = false
    private lateinit var repository: DownloadRepository
    private val listener: () -> Unit = {
        if (!stopping && !notificationPending) {
            notificationPending = true
            main.postDelayed({
                notificationPending = false
                if (!stopping && running.get()) {
                    val task = repository.all().firstOrNull { it.state in setOf(TaskState.DOWNLOADING, TaskState.PROCESSING) }
                    getSystemService(NotificationManager::class.java).notify(200, notification(task?.let { "${it.state.label} · ${com.pdownloader.browser.media.bytes(it.downloaded)} · ${it.title.take(25)}" } ?: "准备下一项下载", task))
                }
            }, 500)
        }
    }
    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.get(this)
        repository.listeners.add(listener)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "媒体下载", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(200, notification("准备下载"))
        if (intent?.action == "pause-all") repository.all().filter { it.state in activeStates }.forEach { task -> repository.update(task.id) { it.copy(state = TaskState.PAUSED) } }
        drain()
        return START_NOT_STICKY
    }
    private fun drain() {
        if (stopping || !running.compareAndSet(false, true)) return
        executor.execute {
            try {
                val engine = DownloadEngine(repository, MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG)) { stopping }
                while (!stopping) {
                    val task = repository.all().lastOrNull { it.state == TaskState.QUEUED } ?: break
                    getSystemService(NotificationManager::class.java).notify(200, notification("正在下载：${task.title.take(40)}"))
                    activeId = task.id
                    try { engine.run(task.id) } finally { activeId = null }
                }
            } finally {
                main.post {
                    running.set(false)
                    if (!stopping && repository.all().any { it.state == TaskState.QUEUED }) drain()
                    else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                }
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        repository.listeners.remove(listener)
        repository.all().filter { it.state in activeStates }.forEach { task -> repository.update(task.id) { it.copy(state = TaskState.PAUSED, error = "系统后台传输时限已到，请回到应用继续") } }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        stopping = true
        repository.listeners.remove(listener)
        // Only pause this service's transfer. A freshly enqueued task may belong to the next start.
        activeId?.let { id -> repository.update(id) {
            if (it.state in activeStates && it.state != TaskState.QUEUED) it.copy(state = TaskState.PAUSED, error = "下载服务已停止，点击继续") else it
        } }
        super.onDestroy()
    }
    private fun notification(text: String, task: DownloadTask? = null): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("section", 1), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, BrowserDownloadService::class.java).setAction("pause-all"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setContentTitle("PDownloader").setContentText(text)
            .setSmallIcon(R.drawable.ic_download_notification).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause), "暂停全部", pause).build())
        if (task != null) {
            val total = if (task.partsTotal > 0) task.partsTotal.toLong() else task.total
            val done = if (task.partsTotal > 0) task.partsDone.toLong() else task.downloaded
            notification.setProgress(1000, if (total > 0) (done.toDouble() / total * 1000).toInt().coerceIn(0, 1000) else 0, total <= 0)
        }
        return notification.build()
    }
    companion object {
        private const val CHANNEL = "media-downloads"
        private val transfers = Executors.newSingleThreadExecutor()
    }
}
