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
import android.widget.GridView
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.ImageView
import com.pdownloader.browser.download.*
import com.pdownloader.browser.media.*
import java.util.concurrent.Executors

enum class DownloadCategory(val title: String) { ALL("全部"), VIDEO("视频"), IMAGE("图片") }

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
    private val start: () -> Unit,
    val completedPage: Boolean = false
) : LinearLayout(activity) {
    private val repository = DownloadRepository.get(activity)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var finished = completedPage
    private var visiblePage = false
    private var closed = false
    private var all = emptyList<DownloadTask>()
    private var displayed = emptyList<DownloadTask>()
    var selectedCategory = DownloadCategory.VIDEO
        private set
    private val filterButtons = linkedMapOf<DownloadCategory, TextView>()
    private var query = ""
    private var sort = 0
    private var refreshing = false
    private val heading = activity.label(if (completedPage) "已下载" else "下载中", 25f)
    private val bulk = activity.row()
    private val downloadSettings = DownloadSettings(activity)
    private val filters = activity.row()
    private val empty = activity.label("暂无下载任务", 16f, true).apply { gravity = Gravity.CENTER }
    private val list = ListView(activity)
    private val grid = GridView(activity)
    private var dirty = true
    private var reloadPending = false
    private data class CoverHolder(val image: ImageView, val title: TextView, val duration: TextView, var task: DownloadTask? = null)
    private val gridAdapter = object : BaseAdapter() {
        override fun getCount() = displayed.size
        override fun getItem(position: Int) = displayed[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val card: LinearLayout
            val holder: CoverHolder
            if (convertView == null) {
                card = activity.column().apply { background = activity.shape(R.color.home_tile, 16); clipToOutline = true }
                val frame = FrameLayout(activity)
                val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(activity.color(R.color.surface_secondary)); contentDescription = "媒体封面" }
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                val duration = activity.label("", 11f).apply {
                    setTextColor(activity.color(R.color.video_foreground)); background = activity.shape(R.color.gallery_controls, 6)
                    setPadding(activity.dp(6), activity.dp(2), activity.dp(6), activity.dp(2))
                }
                frame.addView(duration, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, activity.dp(8), activity.dp(8)) })
                card.addView(frame, LayoutParams(-1, activity.dp(146)))
                val title = activity.label("", 14f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(activity.dp(12), activity.dp(8), activity.dp(12), activity.dp(8)) }
                card.addView(title, LayoutParams(-1, activity.dp(56)))
                holder = CoverHolder(image, title, duration); card.tag = holder
                card.isClickable = true; card.isFocusable = true
                card.setOnClickListener { holder.task?.let(open) }
                card.setOnLongClickListener { holder.task?.let { showMenu(card, it) }; true }
            } else { card = convertView as LinearLayout; holder = card.tag as CoverHolder }
            val task = getItem(position)
            if (holder.task != task) {
                holder.task = task; holder.title.text = task.title
                card.contentDescription = "${task.title}，点击打开，长按更多操作"
                holder.duration.visibility = if (task.variant.kind == MediaKind.IMAGE) View.GONE else View.VISIBLE
                holder.duration.text = if (task.durationMs >= 0) formatMediaTime(task.durationMs) else "--:--"
                holder.image.setImageResource(if (task.variant.kind == MediaKind.IMAGE) R.drawable.media_image_placeholder else R.drawable.media_video_placeholder)
                images.loadDownloaded(holder.image, task, repository.folder(task.id)) { duration ->
                    if (holder.task?.id == task.id && duration > 0) holder.duration.text = formatMediaTime(duration)
                }
            }
            return card
        }
    }
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
            cover.addView(BrowserIcon(activity, if (task.variant.kind == MediaKind.IMAGE) "image" else "play", if (task.variant.kind == MediaKind.IMAGE) "查看图片" else "播放视频") {
                if (task.state == TaskState.COMPLETE) open(task) else if (task.variant.kind != MediaKind.IMAGE) online(task)
            }.apply { foregroundColor = activity.color(R.color.accent) }, FrameLayout.LayoutParams(-1, -1))
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
                stateRow.addView(BrowserIcon(activity, if (paused) "play" else "pause", if (paused) "继续下载" else "暂停下载") {
                    io.execute {
                        repository.update(task.id) { current ->
                            when {
                                paused && current.state in setOf(TaskState.PAUSED, TaskState.FAILED) -> current.copy(state = TaskState.QUEUED, error = "")
                                !paused && current.state in activeStates -> current.copy(state = TaskState.PAUSED, error = "")
                                else -> current
                            }
                        }
                        if (paused) main.post { start() }
                    }
                }, LayoutParams(activity.dp(48), activity.dp(48)))
            } else if (task.state == TaskState.COMPLETE) stateRow.addView(BrowserIcon(activity, if (task.variant.kind == MediaKind.IMAGE) "image" else "play", if (task.variant.kind == MediaKind.IMAGE) "查看图片" else "播放视频") { open(task) }, LayoutParams(activity.dp(48), activity.dp(48)))
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
    private val listener: () -> Unit = { dirty = true; refresh() }
    init {
        orientation = VERTICAL
        setBackgroundColor(activity.color(R.color.surface))
        val top = activity.row().apply { setPadding(activity.dp(20), activity.dp(12), activity.dp(12), 0) }
        top.addView(heading, LayoutParams(0, activity.dp(52), 1f))
        val sortButton = BrowserIcon(activity, "sort", "下载排序") { }
        sortButton.setOnClickListener {
            PopupMenu(activity, sortButton).apply {
                listOf("最近添加", "名称", "文件大小").forEachIndexed { index, name -> menu.add(0, index, index, name) }
                setOnMenuItemClickListener { sort = it.itemId; applyFilter(); true }
            }.show()
        }
        top.addView(sortButton, LayoutParams(activity.dp(48), activity.dp(48))); addView(top)
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
        if (!completedPage) {
            val setting = activity.action(parallelDownloadLabel()) {}
            setting.setOnClickListener { showParallelDownloadOptions(setting) }
            bulk.addView(setting, LayoutParams(0, activity.dp(48), 1f).apply { marginStart = activity.dp(16) })
        }
        bulk.addView(BrowserIcon(activity, "pause", "暂停全部下载") {
            io.execute { updateTasks(activeStates, TaskState.PAUSED) }
        }, LayoutParams(activity.dp(48), activity.dp(48)))
        bulk.addView(BrowserIcon(activity, "play", "继续全部下载") {
            io.execute {
                updateTasks(setOf(TaskState.PAUSED, TaskState.FAILED), TaskState.QUEUED)
                main.post { start() }
            }
        }, LayoutParams(activity.dp(48), activity.dp(48)))
        addView(bulk)
        filters.setPadding(activity.dp(12), activity.dp(4), activity.dp(12), activity.dp(4))
        listOf(DownloadCategory.VIDEO, DownloadCategory.IMAGE).forEach { category ->
            val button = activity.action(category.title) { setCategory(category) }
            filterButtons[category] = button
            filters.addView(button, LayoutParams(0, activity.dp(40), 1f).apply { marginStart = activity.dp(4); marginEnd = activity.dp(4) })
        }
        updateCategoryButtons()
        addView(filters)
        list.adapter = adapter
        grid.apply { numColumns = 2; horizontalSpacing = activity.dp(12); verticalSpacing = activity.dp(12)
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(16)); clipToPadding = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH; adapter = gridAdapter; contentDescription = "已下载媒体网格"
        }
        addView(empty, LayoutParams(-1, 0, 1f))
        addView(list, LayoutParams(-1, 0, 1f))
        addView(grid, LayoutParams(-1, 0, 1f))
        bulk.visibility = if (completedPage) View.GONE else View.VISIBLE
        filters.visibility = if (completedPage) View.VISIBLE else View.GONE
        list.visibility = if (completedPage) View.GONE else View.VISIBLE
        grid.visibility = if (completedPage) View.VISIBLE else View.GONE
        empty.visibility = View.GONE
        repository.listeners.add(listener)
        // Warm both pages before first navigation; they keep independent data and scroll positions.
        refresh(force = true)
    }
    fun show(completed: Boolean) {
        val changedMode = finished != completed
        finished = completed; visiblePage = true
        if (changedMode) heading.text = if (completed) "已下载" else "下载中"
        bulk.visibility = if (completed) View.GONE else View.VISIBLE
        filters.visibility = if (completed) View.VISIBLE else View.GONE
        applyFilter()
        if (dirty) refresh(force = true)
    }
    fun hide() { visiblePage = false }
    private fun parallelDownloadLabel() = activity.getString(R.string.parallel_download_limit, downloadSettings.parallelDownloads)

    private fun showParallelDownloadOptions(setting: TextView) {
        val options = (1..DownloadSettings.MAX_PARALLEL_DOWNLOADS).map { activity.getString(R.string.parallel_download_limit, it) }
        AlertDialog.Builder(activity)
            .setTitle(R.string.parallel_download_title)
            .setSingleChoiceItems(options.toTypedArray(), downloadSettings.parallelDownloads - 1) { dialog, index ->
                downloadSettings.parallelDownloads = index + 1
                setting.text = parallelDownloadLabel()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateTasks(from: Set<TaskState>, to: TaskState) {
        repository.all().filter { it.state in from }.forEach { task ->
            repository.update(task.id) { current ->
                if (current.state in from) current.copy(state = to, error = "") else current
            }
        }
    }
    fun setCategory(category: DownloadCategory) { selectedCategory = if (category == DownloadCategory.ALL) DownloadCategory.VIDEO else category; updateCategoryButtons(); applyFilter() }
    private fun updateCategoryButtons() {
        filterButtons.forEach { (category, button) ->
            val selected = category == selectedCategory
            button.isSelected = selected
            button.text = if (selected) "✓ ${category.title}" else category.title
            button.contentDescription = "筛选：${category.title}，${if (selected) "已选中" else "未选中"}"
            button.setTextColor(activity.color(if (selected) R.color.accent else R.color.muted))
            button.background = activity.shape(if (selected) R.color.accent_surface else R.color.surface_secondary, 16)
            button.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
    fun close() { closed = true; repository.listeners.remove(listener); io.shutdown() }
    private fun refresh(force: Boolean = false) {
        if (closed || (!visiblePage && !force)) return
        if (refreshing) { reloadPending = true; return }
        refreshing = true; dirty = false
        io.execute {
            val tasks = runCatching { repository.all() }
            main.post {
                refreshing = false
                if (!closed) {
                    tasks.onSuccess { if (all != it) { all = it; applyFilter() } }.onFailure { dirty = true }
                    if (reloadPending) { reloadPending = false; refresh(force = true) }
                }
            }
        }
    }
    private fun applyFilter() {
        val next = all.filter { (it.state == TaskState.COMPLETE) == finished && it.title.contains(query, true) &&
            (!finished || selectedCategory == DownloadCategory.ALL || (selectedCategory == DownloadCategory.IMAGE) == (it.variant.kind == MediaKind.IMAGE)) }.let {
            when (sort) { 1 -> it.sortedBy { task -> task.title.lowercase() }; 2 -> it.sortedByDescending { task -> task.downloaded }; else -> it }
        }
        val changed = next != displayed
        displayed = next
        empty.text = if (query.isNotBlank()) "没有匹配的下载" else if (finished) "还没有下载完成的媒体" else "暂无下载任务，去网页发现视频或图片吧"
        empty.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (displayed.isEmpty() || finished) View.GONE else View.VISIBLE
        grid.visibility = if (displayed.isEmpty() || !finished) View.GONE else View.VISIBLE
        if (changed) { adapter.notifyDataSetChanged(); gridAdapter.notifyDataSetChanged() }
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
