package com.pdownloader.browser.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.pdownloader.browser.media.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

enum class TaskState(val label: String) {
    QUEUED("排队中"), CONNECTING("连接中"), DOWNLOADING("下载中"), PAUSED("已暂停"),
    PROCESSING("整理文件"), COMPLETE("已完成"), FAILED("下载失败"), CANCELLED("已取消")
}
internal val transferStates = setOf(TaskState.CONNECTING, TaskState.DOWNLOADING, TaskState.PROCESSING)
val activeStates = transferStates + TaskState.QUEUED

enum class DownloadFailure(val action: String, val needsSource: Boolean = false) {
    NETWORK("继续下载"), LOGIN("打开原页登录", true), FORBIDDEN("打开原页", true),
    SOURCE("回原页重新检测", true), OTHER("继续下载");

    companion object {
        internal fun legacy(message: String): DownloadFailure = when {
            message.startsWith("服务器拒绝访问") -> FORBIDDEN
            message.startsWith("需要登录") -> LOGIN
            listOf("链接已过期", "重新检测", "远程文件发生变化", "分片地址返回了网页").any(message::contains) -> SOURCE
            message.startsWith("网络") -> NETWORK
            else -> OTHER
        }
    }
}

data class DownloadTask(
    val id: String,
    val title: String,
    val variant: MediaVariant,
    val context: RequestContext,
    val state: TaskState = TaskState.QUEUED,
    val downloaded: Long = 0,
    val total: Long = -1,
    val partsDone: Int = 0,
    val partsTotal: Int = 0,
    val error: String = "",
    val validator: String = "",
    val output: String = "",
    val thumbnail: String = "",
    val durationMs: Long = -1,
    val saveToPublic: Boolean = false,
    val publicUri: String = "",
    val failure: DownloadFailure? = null,
    val resetPartial: Boolean = false
)

class DownloadRepository private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "downloads.db", null, 2) {
    private val downloadsRoot = File(context.applicationContext.filesDir, "downloads")
    internal val publicDownloads = PublicDownloads(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())
    val listeners = CopyOnWriteArraySet<() -> Unit>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, dedup TEXT NOT NULL, created INTEGER NOT NULL, state TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX tasks_dedup ON tasks(dedup)")
        createLibrary(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createLibrary(db) }
    private fun createLibrary(db: SQLiteDatabase) { db.execSQL("CREATE TABLE IF NOT EXISTS retained_files (id TEXT PRIMARY KEY, created INTEGER NOT NULL, payload TEXT NOT NULL)") }

    @Synchronized fun enqueue(title: String, variant: MediaVariant, context: RequestContext, thumbnail: String = "", durationMs: Long = -1, saveToPublic: Boolean = false): Pair<DownloadTask, Boolean> {
        require(variant.blockedReason.isEmpty()) { variant.blockedReason }
        require(webUrl(variant.url)) { "无效的媒体地址" }
        val key = stableId(variant.id + context.pageUrl)
        val legacyKey = if (variant.kind == MediaKind.OTHER) stableId(variant.copy(kind = MediaKind.VIDEO).id + context.pageUrl) else key
        readableDatabase.rawQuery("SELECT payload FROM tasks WHERE dedup IN (?,?) AND state!=?", arrayOf(key, legacyKey, TaskState.CANCELLED.name)).use {
            while (it.moveToNext()) {
                val existing = decode(it.getString(0))
                if (existing.variant.kind == variant.kind) return existing to false
            }
        }
        val task = DownloadTask(UUID.randomUUID().toString(), title.take(200), variant, context, thumbnail = thumbnail, durationMs = durationMs, saveToPublic = saveToPublic)
        val values = ContentValues().apply {
            put("id", task.id); put("dedup", key); put("created", System.currentTimeMillis()); put("state", task.state.name); put("payload", encode(task).toString())
        }
        writableDatabase.insertOrThrow("tasks", null, values)
        notifyChange()
        return task to true
    }

    @Synchronized fun all(): List<DownloadTask> = readableDatabase.rawQuery("SELECT payload FROM (SELECT id,payload,created FROM tasks UNION ALL SELECT id,payload,created FROM retained_files) ORDER BY created DESC, id DESC", null).use { cursor ->
        buildList { while (cursor.moveToNext()) add(decode(cursor.getString(0))) }
    }
    @Synchronized fun get(id: String): DownloadTask? = readableDatabase.rawQuery("SELECT payload FROM tasks WHERE id=? UNION ALL SELECT payload FROM retained_files WHERE id=? LIMIT 1", arrayOf(id, id)).use {
        if (it.moveToFirst()) decode(it.getString(0)) else null
    }
    @Synchronized internal fun queuedIds(): List<String> = readableDatabase.query(
        "tasks", arrayOf("id"), "state=?", arrayOf(TaskState.QUEUED.name), null, null, "created, rowid"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    @Synchronized fun update(id: String, transform: (DownloadTask) -> DownloadTask) {
        val previous = get(id) ?: return
        val task = transform(previous)
        if (task == previous) return
        val values = ContentValues().apply { put("state", task.state.name); put("payload", encode(task).toString()) }
        if (writableDatabase.update("tasks", values, "id=?", arrayOf(id)) == 0) {
            writableDatabase.update("retained_files", ContentValues().apply { put("payload", encode(task).toString()) }, "id=?", arrayOf(id))
        }
        notifyChange()
    }

    @Synchronized internal fun recover(id: String, variant: MediaVariant, context: RequestContext): Boolean {
        val previous = get(id) ?: return false
        if (previous.state != TaskState.FAILED || previous.variant.kind != variant.kind ||
            variant.blockedReason.isNotEmpty() || !webUrl(variant.url)) return false
        val dedup = stableId(variant.id + previous.context.pageUrl)
        if (readableDatabase.rawQuery("SELECT 1 FROM tasks WHERE dedup=? AND id!=? AND state!=? LIMIT 1",
                arrayOf(dedup, id, TaskState.CANCELLED.name)).use { it.moveToFirst() }) return false
        val changedUrl = previous.variant.url != variant.url
        val task = previous.copy(
            variant = variant.copy(requestContext = null), context = context.copy(pageUrl = previous.context.pageUrl),
            state = TaskState.QUEUED, failure = null, error = "",
            downloaded = if (changedUrl) 0 else previous.downloaded, total = if (changedUrl) -1 else previous.total,
            partsDone = if (changedUrl) 0 else previous.partsDone, partsTotal = if (changedUrl) 0 else previous.partsTotal,
            validator = if (changedUrl) "" else previous.validator, resetPartial = changedUrl || previous.resetPartial
        )
        val updated = writableDatabase.update("tasks", ContentValues().apply {
            put("dedup", dedup); put("state", task.state.name); put("payload", encode(task).toString())
        }, "id=? AND state=?", arrayOf(id, TaskState.FAILED.name))
        if (updated == 1) notifyChange()
        return updated == 1
    }
    @Synchronized fun recoverInterrupted() {
        all().filter { it.state in activeStates }.forEach { task -> update(task.id) { it.copy(state = TaskState.PAUSED, error = "应用或服务已停止，点击继续下载") } }
    }
    @Synchronized fun forget(id: String, deleteFile: Boolean) {
        val task = get(id) ?: return
        require(task.state !in activeStates) { "请先暂停任务" }
        if (deleteFile || task.state != TaskState.COMPLETE) deleteFiles(task)
        writableDatabase.beginTransaction()
        try {
            if (!deleteFile && task.state == TaskState.COMPLETE) {
                val values = ContentValues().apply {
                    put("id", id); put("created", System.currentTimeMillis())
                    put("payload", encode(task.copy(context = task.context.copy(authorization = ""))).toString())
                }
                writableDatabase.insertWithOnConflict("retained_files", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            } else writableDatabase.delete("retained_files", "id=?", arrayOf(id))
            writableDatabase.delete("tasks", "id=?", arrayOf(id))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        notifyChange()
    }

    @Synchronized internal fun cancel(id: String) {
        val task = get(id) ?: return
        if (task.state == TaskState.COMPLETE) return
        update(id) { it.copy(state = TaskState.CANCELLED, error = "") }
        deleteFiles(task)
        update(id) { it.copy(publicUri = "") }
    }

    private fun deleteFiles(task: DownloadTask) {
        task.publicUri.takeIf { it.isNotBlank() }?.let { publicDownloads.delete(Uri.parse(it)) }
        folder(task.id).deleteRecursively()
    }
    fun folder(id: String): File {
        require(runCatching { UUID.fromString(id) }.isSuccess) { "无效任务标识" }
        return File(downloadsRoot, id)
    }
    internal fun playbackUri(task: DownloadTask): Uri =
        if (task.variant.kind != MediaKind.HLS && task.publicUri.isNotBlank()) Uri.parse(task.publicUri)
        else Uri.fromFile(File(folder(task.id), task.output))
    private fun notifyChange() { handler.post { listeners.forEach { it() } } }

    companion object {
        @Volatile private var instance: DownloadRepository? = null
        fun get(context: Context): DownloadRepository = instance ?: synchronized(this) {
            instance ?: DownloadRepository(context.applicationContext).also { instance = it }
        }
        fun encode(task: DownloadTask) = JSONObject().apply {
            put("id", task.id); put("title", task.title); put("url", task.variant.url); put("kind", task.variant.kind.name)
            put("quality", task.variant.quality); put("height", task.variant.height); put("mime", task.variant.mime)
            put("page", task.context.pageUrl); put("ua", task.context.userAgent); put("referer", task.context.referer)
            put("credentialOrigin", task.context.credentialOrigin); put("authorization", task.context.authorization)
            put("state", task.state.name); put("downloaded", task.downloaded); put("total", task.total)
            put("partsDone", task.partsDone); put("partsTotal", task.partsTotal); put("error", task.error)
            put("validator", task.validator); put("output", task.output)
            put("thumbnail", task.thumbnail); put("durationMs", task.durationMs)
            put("saveToPublic", task.saveToPublic); put("publicUri", task.publicUri)
            put("failure", task.failure?.name); put("resetPartial", task.resetPartial)
        }
        fun decode(json: String): DownloadTask = JSONObject(json).let {
            val url = it.getString("url")
            val mime = it.optString("mime")
            val storedKind = MediaKind.valueOf(it.getString("kind"))
            val kind = if (storedKind == MediaKind.VIDEO && specificMime(mime) != null && downloadKind(url, mime) == MediaKind.OTHER)
                MediaKind.OTHER else storedKind
            val state = TaskState.valueOf(it.getString("state"))
            val error = it.optString("error")
            val failure = if (state == TaskState.FAILED)
                DownloadFailure.entries.find { reason -> reason.name == it.optString("failure") } ?: DownloadFailure.legacy(error) else null
            DownloadTask(it.getString("id"), it.getString("title"), MediaVariant(url, kind, it.optString("quality"), it.optInt("height"), mime = mime),
                RequestContext(it.getString("page"), it.optString("ua"), it.optString("referer"), it.optString("credentialOrigin"), it.optString("authorization")),
                state, it.optLong("downloaded"), it.optLong("total", -1), it.optInt("partsDone"), it.optInt("partsTotal"), error, it.optString("validator"), it.optString("output"), it.optString("thumbnail"), it.optLong("durationMs", -1), it.optBoolean("saveToPublic"), it.optString("publicUri"), failure, it.optBoolean("resetPartial"))
        }
    }
}
