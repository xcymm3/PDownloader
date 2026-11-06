package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ImageView
import com.pdownloader.browser.download.*
import com.pdownloader.browser.media.*
import java.util.concurrent.Executors

// Constructed programmatically with host callbacks; not inflated by a layout editor.
@android.annotation.SuppressLint("ViewConstructor")
class DownloadsView(
    private val activity: Activity,
    private val images: MediaImages,
    private val source: (String) -> Unit,
    private val online: (DownloadTask) -> Unit,
    private val open: (DownloadTask) -> Unit,
    private val export: (DownloadTask) -> Unit,
    private val share: (DownloadTask) -> Unit,
    private val start: () -> Unit
) : LinearLayout(activity) {
    private val repository = DownloadRepository.get(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var finished = false
    private var visiblePage = false
    private var closed = false
    private var all = emptyList<DownloadTask>()
    private var displayed = emptyList<DownloadTask>()
    private var filter = 0
    private var query = ""
    private var sort = 0
    private var refreshing = false
    private val heading = activity.label("下载中", 25f)
    private val bulk = activity.row()
    private val filters = activity.row()
    private val empty = activity.label("暂无下载任务", 16f, true).apply { gravity = Gravity.CENTER }
    private val list = ListView(activity)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = displayed.size
        override fun getItem(position: Int) = displayed[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val task = getItem(position)
            val body = activity.column().apply { setPadding(activity.dp(16), activity.dp(14), activity.dp(12), activity.dp(14)) }
            val titleRow = activity.row()
            val cover = FrameLayout(activity)
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(activity.color(R.color.surface_secondary)); contentDescription = "媒体封面" }
            cover.addView(image, FrameLayout.LayoutParams(-1, -1))
            cover.addView(activity.label(if (task.variant.kind == MediaKind.IMAGE) "图片" else "▶", 18f, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
            titleRow.addView(cover, LayoutParams(activity.dp(80), activity.dp(60)).apply { marginEnd = activity.dp(12) })
            images.load(image, task.thumbnail, task.context)
            val title = activity.label(task.title, 16f).apply { maxLines = 2; typeface = Typeface.DEFAULT_BOLD }
            titleRow.addView(title, LayoutParams(0, -2, 1f))
            val menu = BrowserIcon(activity, "menu", "任务操作") { }
            menu.setOnClickListener { showMenu(menu, task) }
            titleRow.addView(menu, LayoutParams(activity.dp(48), activity.dp(48)))
            body.addView(titleRow)
            val duration = if (task.durationMs >= 0) " · ${task.durationMs / 60000}:${(task.durationMs / 1000 % 60).toString().padStart(2, '0')}" else ""
            body.addView(activity.label("${task.variant.quality} · ${if (task.variant.kind == MediaKind.HLS) "HLS 离线资源包" else extension(task.variant).uppercase()}$duration", 12f, true))
            val stateRow = activity.row()
            val details = when {
                task.variant.kind == MediaKind.HLS && task.partsTotal > 0 && task.state != TaskState.COMPLETE -> "${task.partsDone}/${task.partsTotal} 片段 · ${bytes(task.downloaded)}"
                task.total > 0 -> "${bytes(task.downloaded)} / ${bytes(task.total)}"
                else -> "${bytes(task.downloaded)} · 总大小未知"
            }
            stateRow.addView(activity.label("${task.state.label} · $details", 12f, true), LayoutParams(0, activity.dp(44), 1f))
            if (task.state != TaskState.COMPLETE && task.state != TaskState.CANCELLED) {
                val paused = task.state !in activeStates
                stateRow.addView(activity.action(if (paused) "继续" else "暂停") {
                    io.execute {
                        repository.update(task.id) { it.copy(state = if (paused) TaskState.QUEUED else TaskState.PAUSED, error = "") }
                        if (paused) main.post { start() }
                    }
                })
            } else if (task.state == TaskState.COMPLETE) stateRow.addView(activity.action(if (task.variant.kind == MediaKind.IMAGE) "查看" else "播放") { open(task) })
            body.addView(stateRow)
            val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progressTintList = android.content.res.ColorStateList.valueOf(activity.color(R.color.accent))
                isIndeterminate = task.state in activeStates && task.total <= 0 && task.partsTotal <= 0
                this.progress = when {
                    task.state == TaskState.COMPLETE -> 1000
                    task.partsTotal > 0 -> task.partsDone * 1000 / task.partsTotal
                    task.total > 0 -> (task.downloaded.toDouble() * 1000 / task.total).toInt().coerceIn(0, 1000)
                    else -> 0
                }
            }
            body.addView(progress, LayoutParams(-1, activity.dp(3)))
            if (task.error.isNotEmpty()) body.addView(activity.label(task.error, 12f).apply { setPadding(0, activity.dp(8), 0, 0) })
            return body
        }
    }
    private val listener: () -> Unit = { refresh() }
    init {
        orientation = VERTICAL
        setBackgroundColor(activity.color(R.color.surface))
        val top = activity.row().apply { setPadding(activity.dp(20), activity.dp(12), activity.dp(12), 0) }
        top.addView(heading, LayoutParams(0, activity.dp(52), 1f))
        val sortButton = activity.action("排序") { }
        sortButton.setOnClickListener {
            PopupMenu(activity, sortButton).apply {
                listOf("最近添加", "名称", "文件大小").forEachIndexed { index, name -> menu.add(0, index, index, name) }
                setOnMenuItemClickListener { sort = it.itemId; applyFilter(); true }
            }.show()
        }
        top.addView(sortButton); addView(top)
        val search = EditText(activity).apply {
            hint = "搜索下载名称"; textSize = 14f; setSingleLine(true)
            setTextColor(activity.color(R.color.ink)); setHintTextColor(activity.color(R.color.muted))
            background = activity.shape(R.color.surface_secondary, 24); setPadding(activity.dp(16), 0, activity.dp(16), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { query = s.toString(); applyFilter() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        addView(search, LayoutParams(-1, activity.dp(48)).apply { setMargins(activity.dp(16), activity.dp(8), activity.dp(16), activity.dp(8)) })
        bulk.addView(activity.action("全部暂停") { io.execute { repository.all().filter { it.state in activeStates }.forEach { task -> repository.update(task.id) { it.copy(state = TaskState.PAUSED) } } } }, LayoutParams(0, activity.dp(48), 1f))
        bulk.addView(activity.action("全部继续") { io.execute { repository.all().filter { it.state in setOf(TaskState.PAUSED, TaskState.FAILED) }.forEach { task -> repository.update(task.id) { it.copy(state = TaskState.QUEUED, error = "") } }; main.post { start() } } }, LayoutParams(0, activity.dp(48), 1f))
        addView(bulk)
        listOf("全部", "视频", "图片").forEachIndexed { index, name -> filters.addView(activity.action(name) { filter = index; applyFilter() }, LayoutParams(0, activity.dp(48), 1f)) }
        addView(filters)
        list.adapter = adapter
        addView(empty, LayoutParams(-1, 0, 1f))
        addView(list, LayoutParams(-1, 0, 1f))
        repository.listeners.add(listener)
    }
    fun show(completed: Boolean) {
        finished = completed; visiblePage = true
        heading.text = if (completed) "已下载" else "下载中"
        bulk.visibility = if (completed) View.GONE else View.VISIBLE
        filters.visibility = if (completed) View.VISIBLE else View.GONE
        refresh()
    }
    fun hide() { visiblePage = false }
    fun close() { closed = true; repository.listeners.remove(listener); io.shutdown() }
    private fun refresh() {
        if (!visiblePage || refreshing || closed) return
        refreshing = true
        main.postDelayed({
            if (closed) return@postDelayed
            io.execute {
                val tasks = repository.all()
                main.post { refreshing = false; if (!closed) { all = tasks; applyFilter() } }
            }
        }, 150)
    }
    private fun applyFilter() {
        displayed = all.filter { (it.state == TaskState.COMPLETE) == finished && it.title.contains(query, true) &&
            (!finished || filter == 0 || (filter == 2) == (it.variant.kind == MediaKind.IMAGE)) }.let {
            when (sort) { 1 -> it.sortedBy { task -> task.title.lowercase() }; 2 -> it.sortedByDescending { task -> task.downloaded }; else -> it }
        }
        empty.text = if (query.isNotBlank()) "没有匹配的下载" else if (finished) "还没有下载完成的媒体" else "暂无下载任务，去网页发现视频或图片吧"
        empty.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        adapter.notifyDataSetChanged()
    }
    private fun showMenu(anchor: View, task: DownloadTask) {
        val names = if (task.state == TaskState.COMPLETE) listOf("打开", "分享", "导出到设备", "重命名", "打开来源网页", "删除") else listOf("在线播放", "打开来源网页", "重命名", "取消并清理", "删除记录")
        PopupMenu(activity, anchor).apply {
            names.forEachIndexed { index, text -> menu.add(0, index, index, text) }
            setOnMenuItemClickListener { item ->
                when (names[item.itemId]) {
                    "打开" -> open(task)
                    "分享" -> share(task)
                    "导出到设备" -> export(task)
                    "在线播放" -> if (task.variant.kind != MediaKind.IMAGE) online(task)
                    "打开来源网页" -> source(task.context.pageUrl)
                    "重命名" -> {
                        val input = EditText(activity).apply { setText(task.title); setSingleLine(true) }
                        AlertDialog.Builder(activity).setTitle("显示名称").setMessage("修改列表名称；已开始下载的文件路径保持不变。").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                            val title = input.text.toString().trim().take(200)
                            if (title.isNotEmpty()) io.execute { repository.update(task.id) { it.copy(title = title) } }
                        }.show()
                    }
                    "取消并清理" -> AlertDialog.Builder(activity).setTitle("取消并清理临时文件？").setNegativeButton("返回", null).setPositiveButton("取消下载") { _, _ ->
                        io.execute {
                            repository.update(task.id) { it.copy(state = TaskState.CANCELLED, error = "") }
                            repository.folder(task.id).deleteRecursively()
                        }
                    }.show()
                    "删除", "删除记录" -> {
                        if (task.state in activeStates) android.widget.Toast.makeText(activity, "请先暂停任务再删除", android.widget.Toast.LENGTH_SHORT).show()
                        else AlertDialog.Builder(activity).setTitle("删除下载记录？").setMessage(if (task.state == TaskState.COMPLETE) "仅删除记录会在“已下载”保留文件入口；同时删文件会清理本地媒体。" else "删除未完成任务时会清理临时文件。")
                            .setNegativeButton("取消", null).setNeutralButton("仅删记录") { _, _ -> io.execute { repository.forget(task.id, false) } }
                            .setPositiveButton("同时删文件") { _, _ -> io.execute { repository.forget(task.id, true) } }.show()
                    }
                }
                true
            }
        }.show()
    }
}
