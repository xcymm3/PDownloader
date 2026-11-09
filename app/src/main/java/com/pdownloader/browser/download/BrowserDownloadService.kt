package com.pdownloader.browser.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.os.IBinder
import android.webkit.CookieManager
import com.pdownloader.browser.BuildConfig
import com.pdownloader.browser.MainActivity
import com.pdownloader.browser.R
import com.pdownloader.browser.media.MediaHttp
import java.util.concurrent.Executors

class BrowserDownloadService : Service() {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var stopping = false
    private var latestStartId = 0
    private var notificationPending = false
    private lateinit var repository: DownloadRepository
    private lateinit var settings: DownloadSettings
    private val limitListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DownloadSettings.PARALLEL_DOWNLOADS) drain()
    }
    private val listener: () -> Unit = {
        if (!stopping && !notificationPending) {
            notificationPending = true
            main.postDelayed({
                notificationPending = false
                if (!stopping && latestStartId != 0) {
                    val active = inFlight.filterValues { it === this }.keys.mapNotNull(repository::get)
                        .filter { it.state in transferStates }
                    val task = active.singleOrNull()
                    val status = when {
                        task != null -> "${task.state.label} · ${com.pdownloader.browser.media.bytes(task.downloaded)} · ${task.title.take(25)}"
                        active.size > 1 -> getString(R.string.parallel_download_status, active.size)
                        else -> "准备下一项下载"
                    }
                    getSystemService(NotificationManager::class.java).notify(200, notification(status, task))
                }
            }, 500)
        }
    }
    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.get(this)
        settings = DownloadSettings(this)
        settings.preferences.registerOnSharedPreferenceChangeListener(limitListener)
        currentService = this
        repository.listeners.add(listener)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "媒体下载", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(200, notification("准备下载"))
        latestStartId = startId
        if (intent?.action == "pause-all") pauseTasks(activeTaskIds(), activeStates, "")
        drain()
        return START_NOT_STICKY
    }
    private fun drain() {
        if (stopping || latestStartId == 0) return
        val freeSlots = settings.parallelDownloads - inFlight.size
        if (freeSlots <= 0) return
        val queued = repository.queuedIds()
        for (id in queued.asSequence().filterNot { it in inFlight }.take(freeSlots)) {
            inFlight[id] = this
            transfers.execute {
                try {
                    val http = MediaHttp({ CookieManager.getInstance().getCookie(it) }, BuildConfig.DEBUG)
                    DownloadEngine(repository, http) { stopping }.run(id)
                } finally {
                    main.post {
                        inFlight.remove(id)
                        currentService?.drain()
                    }
                }
            }
        }
        if (queued.isEmpty() && inFlight.values.none { it === this } && stopSelfResult(latestStartId)) {
            latestStartId = 0
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopping = true
        repository.listeners.remove(listener)
        pauseTasks(activeTaskIds(), activeStates, "系统后台传输时限已到，请回到应用继续")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        stopping = true
        if (currentService === this) currentService = null
        settings.preferences.unregisterOnSharedPreferenceChangeListener(limitListener)
        repository.listeners.remove(listener)
        pauseTasks(inFlight.filterValues { it === this }.keys, transferStates, "下载服务已停止，点击继续")
        super.onDestroy()
    }
    private fun pauseTasks(ids: Iterable<String>, states: Set<TaskState>, error: String) {
        ids.forEach { id -> repository.update(id) { task ->
            if (task.state in states) task.copy(state = TaskState.PAUSED, error = error) else task
        } }
    }
    private fun activeTaskIds() = repository.all().filter { it.state in activeStates }.map { it.id }
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
        private val transfers = Executors.newFixedThreadPool(DownloadSettings.MAX_PARALLEL_DOWNLOADS)
        // Main-thread reservations survive service replacement until each worker closes its files.
        private val inFlight = mutableMapOf<String, BrowserDownloadService>()
        private var currentService: BrowserDownloadService? = null
    }
}
