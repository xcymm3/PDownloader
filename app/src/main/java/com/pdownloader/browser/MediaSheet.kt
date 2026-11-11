package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import com.pdownloader.browser.media.*

/** One sheet owns both types; incremental inserts retain selection and scroll position. */
// Gesture taps explicitly call each native view's accessible performClick implementation.
@android.annotation.SuppressLint("ClickableViewAccessibility")
class MediaSheet(
    private val activity: Activity,
    private val session: ProbeSession,
    private val images: MediaImages,
    private val submit: (List<Pair<MediaGroup, MediaVariant>>) -> Unit,
    private val previewVideo: (MediaGroup, MediaVariant) -> Unit,
    private val retry: () -> Unit,
    private val recoveryTitle: String? = null,
    private val shared: Boolean = false
) {
    private val dialog = Dialog(activity)
    private val selected = linkedSetOf<String>()
    private val versions = mutableMapOf<String, String>()
    private val names = mutableMapOf<String, String>()
    private val rows = linkedMapOf<String, LinearLayout>()
    private val imageRows = linkedMapOf<String, FrameLayout>()
    private val controls = mutableMapOf<String, CheckBox>()
    private val radios = mutableMapOf<String, RadioButton>()
    private val rendered = mutableSetOf<String>()
    private val videoList = activity.column()
    private val grid = activity.column()
    private val status = activity.label("", 14f, true)
    private val videoHeading = activity.label("视频", 17f)
    private val imageHeading = activity.label("图片", 17f)
    private val scroll = ScrollView(activity)
    private val download = object : FrameLayout(activity) {
        override fun getAccessibilityClassName(): CharSequence = "android.widget.Button"
    }.apply { isClickable = true; isFocusable = true; setOnClickListener { downloadSelected() } }
    private val downloadCount = activity.label("0", 15f).apply { setTextColor(activity.color(R.color.on_accent)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var expanded = false
    private var preview: ImageGalleryDialog? = null

    init {
        session.groups.values.filter { !it.image }.forEach { selected.add(it.id) }
        val body = activity.column().apply { setBackgroundColor(activity.color(R.color.surface)); setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(12)) }
        val handle = activity.label("━", 18f, true).apply { gravity = Gravity.CENTER; contentDescription = "展开或收起资源面板" }
        var startY = 0f
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.rawY; true }
                MotionEvent.ACTION_UP -> { if (kotlin.math.abs(event.rawY - startY) > activity.dp(16)) { expanded = event.rawY < startY; resize() } else view.performClick(); true }
                else -> true
            }
        }
        handle.setOnClickListener { expanded = !expanded; resize() }
        body.addView(handle, LinearLayout.LayoutParams(-1, activity.dp(20)))
        val top = activity.row()
        val title = if (shared) "分享链接资源" else if (recoveryTitle == null) "本页资源" else "恢复下载"
        top.addView(activity.label(title, 18f), LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        top.addView(BrowserIcon(activity, if (shared) "external" else "reload", if (shared) "打开原网页" else "重新检测") {
            dialog.dismiss(); retry()
        }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        top.addView(BrowserIcon(activity, "close", "关闭资源面板") { dialog.dismiss() }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        body.addView(top)
        val content = activity.column()
        status.textSize = 12f; status.setPadding(0, activity.dp(4), 0, activity.dp(8)); content.addView(status)
        val videoBar = activity.row()
        videoBar.addView(videoHeading, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        videoBar.addView(BrowserIcon(activity, "select_all", "全选视频") { session.groups.values.filter { !it.image && it.variants.any { v -> v.blockedReason.isEmpty() } }.forEach { selected.add(it.id) }; sync() }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(48)))
        videoBar.addView(BrowserIcon(activity, "clear_selection", "取消视频选择") { selected.removeAll(session.groups.values.filter { !it.image }.map { it.id }.toSet()); sync() }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(48)))
        content.addView(videoBar); content.addView(videoList)
        val imageBar = activity.row()
        imageBar.addView(imageHeading, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        imageBar.addView(BrowserIcon(activity, "select_all", "全选图片") { session.groups.values.filter { it.image }.forEach { selected.add(it.id) }; sync() }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(48)))
        imageBar.addView(BrowserIcon(activity, "clear_selection", "取消图片选择") { selected.removeAll(session.groups.values.filter { it.image }.map { it.id }.toSet()); sync() }, LinearLayout.LayoutParams(activity.dp(44), activity.dp(48)))
        content.addView(imageBar); content.addView(grid)
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        download.background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(activity.color(R.color.accent_surface)), activity.shape(R.color.accent, 12), null)
        download.clipToOutline = true
        val downloadContent = activity.row().apply { gravity = Gravity.CENTER }
        downloadContent.addView(BrowserIcon(activity, "download", "") {}.apply {
            foregroundColor = activity.color(R.color.on_accent); background = null
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(activity.dp(24), activity.dp(48)))
        downloadContent.addView(downloadCount, LinearLayout.LayoutParams(-2, activity.dp(48)).apply { marginStart = activity.dp(8) })
        download.addView(downloadContent, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        body.addView(download, LinearLayout.LayoutParams(-1, activity.dp(48)))
        dialog.setContentView(body)
        dialog.setOnDismissListener { preview?.dismiss() }
    }

    fun show(imagesFirst: Boolean) {
        dialog.show(); resize(); refresh()
        if (imagesFirst) scroll.post { scroll.smoothScrollTo(0, grid.top) }
    }
    fun dismiss() { preview?.dismiss(); dialog.dismiss() }
    fun isShowing() = dialog.isShowing
    private fun resize() {
        val landscape = activity.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setGravity(Gravity.BOTTOM)
            setLayout(activity.resources.displayMetrics.widthPixels, (activity.resources.displayMetrics.heightPixels * if (expanded || landscape) .94 else .70).toInt())
        }
    }
    fun refresh() {
        if (!session.alive) { dismiss(); return }
        status.text = when {
            recoveryTitle != null -> "原任务：$recoveryTitle\n" + if (session.groups.isEmpty()) session.message else "请选择一个同类型资源"
            session.groups.isEmpty() -> session.message
            else -> "选择要下载的视频版本或图片"
        }
        session.groups.values.filter { !it.image }.forEach { group ->
            val row = rows.getOrPut(group.id) {
                activity.column().apply {
                    setPadding(activity.dp(10), activity.dp(8), activity.dp(10), activity.dp(8))
                    background = activity.shape(R.color.surface_secondary, 12)
                    val header = activity.row()
                    val cover = FrameLayout(activity)
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(activity.color(R.color.divider)); contentDescription = "视频封面" }
                    cover.addView(image, FrameLayout.LayoutParams(-1, -1)); images.load(image, group.thumbnail, group.context)
                    cover.addView(BrowserIcon(activity, "play", "预览视频") { chosen(group)?.let { previewVideo(group, it) } }.apply { foregroundColor = activity.color(R.color.accent) }, FrameLayout.LayoutParams(-1, -1))
                    cover.setOnClickListener { chosen(group)?.let { previewVideo(group, it) } }
                    header.addView(cover, LinearLayout.LayoutParams(activity.dp(92), activity.dp(69)))
                    val info = activity.column().apply { setPadding(activity.dp(10), 0, 0, 0) }
                    val checkbox = CheckBox(activity).apply {
                        text = group.title; textSize = 14f; maxLines = 2; setTextColor(activity.color(R.color.ink))
                        buttonTintList = ColorStateList.valueOf(activity.color(R.color.accent))
                        background = null; setPadding(0, 0, 0, 0)
                        isChecked = group.id in selected
                        setOnCheckedChangeListener { _, checked -> if (checked) selected.add(group.id) else selected.remove(group.id); sync() }
                    }
                    controls[group.id] = checkbox
                    val titleRow = activity.row()
                    titleRow.addView(checkbox, LinearLayout.LayoutParams(0, -2, 1f))
                    titleRow.addView(BrowserIcon(activity, "edit", "重命名下载文件") {
                        val input = EditText(activity).apply { setText(names[group.id] ?: group.title); isSingleLine = true }
                        AlertDialog.Builder(activity).setTitle("下载名称").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                            names[group.id] = input.text.toString().take(200).ifBlank { group.title }; checkbox.text = names[group.id]
                        }.show()
                    }, LinearLayout.LayoutParams(activity.dp(40), activity.dp(48)))
                    info.addView(titleRow)
                    header.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); addView(header)
                    if (group.durationMs >= 0) addView(activity.label("时长 ${group.durationMs / 60000}:${(group.durationMs / 1000 % 60).toString().padStart(2, '0')}", 12f, true))
                    videoList.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = activity.dp(12) })
                }
            }
            if (versions[group.id] == null) {
                group.variants.filter { it.blockedReason.isEmpty() }.maxByOrNull { it.height }?.let { versions[group.id] = it.id }
            }
            controls[group.id]?.text = names[group.id] ?: group.title
            group.variants.forEach { variant ->
                if (rendered.add(group.id + variant.id)) {
                    val radio = RadioButton(activity).apply {
                        text = activity.getString(R.string.media_quality_line, variant.quality, if (variant.kind == MediaKind.HLS) "HLS" else extension(variant).uppercase(), bytes(variant.size), if (variant.blockedReason.isNotEmpty()) "\n${variant.blockedReason}" else "")
                        textSize = 12f; minHeight = activity.dp(40); setTextColor(activity.color(R.color.ink))
                        buttonTintList = ColorStateList.valueOf(activity.color(R.color.accent))
                        isEnabled = variant.blockedReason.isEmpty()
                        setOnClickListener { versions[group.id] = variant.id; selected.add(group.id); sync() }
                    }
                    radios[group.id + variant.id] = radio; row.addView(radio)
                }
            }
        }
        val allImages = session.groups.values.filter { it.image }
        allImages.forEachIndexed { index, group ->
            if (group.id !in imageRows) {
                val lineIndex = index / 3
                while (grid.childCount <= lineIndex) grid.addView(activity.row())
                val line = grid.getChildAt(lineIndex) as LinearLayout
                val cell = FrameLayout(activity).apply { setPadding(activity.dp(3), activity.dp(3), activity.dp(3), activity.dp(3)) }
                val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(activity.color(R.color.surface_secondary)); contentDescription = group.title; setOnClickListener { showImage(group.id) } }
                cell.addView(image, FrameLayout.LayoutParams(-1, -1)); images.load(image, group.thumbnail, group.context)
                val check = CheckBox(activity).apply {
                    contentDescription = "选择图片"; tooltipText = "选择图片"
                    buttonDrawable = activity.getDrawable(R.drawable.media_selection)
                    buttonTintList = null
                    background = null; minWidth = 0; minHeight = 0
                    setPadding(activity.dp(10), 0, activity.dp(10), 0)
                    setOnCheckedChangeListener { _, value -> if (value) selected.add(group.id) else selected.remove(group.id); sync() }
                }
                controls[group.id] = check
                cell.addView(check, FrameLayout.LayoutParams(activity.dp(40), activity.dp(40), Gravity.TOP or Gravity.END))
                cell.addView(activity.label(group.variants.first().quality, 10f).apply {
                    background = activity.shape(R.color.gallery_controls, 4); gravity = Gravity.CENTER
                    setTextColor(activity.color(R.color.video_foreground)); setPadding(activity.dp(6), 0, activity.dp(6), 0)
                }, FrameLayout.LayoutParams(-2, activity.dp(18), Gravity.BOTTOM or Gravity.START).apply { setMargins(activity.dp(6), 0, activity.dp(6), activity.dp(6)) })
                imageRows[group.id] = cell
                line.addView(cell, LinearLayout.LayoutParams(0, activity.dp(116), 1f))
            }
        }
        // Keep each grid cell a third of the width, including an incomplete final row.
        for (i in 0 until grid.childCount) {
            val line = grid.getChildAt(i) as LinearLayout
            line.weightSum = 3f
            for (j in 0 until line.childCount) (line.getChildAt(j).layoutParams as LinearLayout.LayoutParams).weight = 1f
        }
        videoHeading.text = activity.getString(R.string.media_count, "视频", session.groups.values.count { !it.image })
        imageHeading.text = activity.getString(R.string.media_count, "图片", allImages.size)
        sync()
    }

    private fun chosen(group: MediaGroup) = group.variants.firstOrNull { it.id == versions[group.id] && it.blockedReason.isEmpty() }
    private fun choices(): List<Pair<MediaGroup, MediaVariant>> = session.groups.values.filter { it.id in selected }.mapNotNull { group ->
        val version = if (group.image) group.variants.firstOrNull() else chosen(group)
        version?.let { group.copy(title = names[group.id] ?: group.title) to it }
    }
    private fun sync() {
        controls.forEach { (id, control) -> if (control.isChecked != (id in selected)) control.isChecked = id in selected }
        session.groups.values.filter { !it.image }.forEach { group ->
            group.variants.forEach { variant -> radios[group.id + variant.id]?.isChecked = versions[group.id] == variant.id }
            if (group.variants.none { it.blockedReason.isEmpty() }) { selected.remove(group.id); controls[group.id]?.isEnabled = false }
        }
        val count = choices().size
        downloadCount.text = activity.getString(R.string.selection_number, count); download.contentDescription = "下载已选的 $count 项资源"
        download.isEnabled = count > 0; download.alpha = if (count > 0) 1f else .45f
        preview?.refreshSelection()
    }
    private fun downloadSelected() {
        val snapshot = choices().toList()
        if (snapshot.isNotEmpty()) { download.isEnabled = false; submit(snapshot); dialog.dismiss() }
    }

    private fun showImage(id: String) {
        preview?.dismiss()
        val pictures = session.groups.values.filter { it.image }.toList()
        if (pictures.isEmpty()) return
        lateinit var viewer: ImageGalleryDialog
        viewer = ImageGalleryDialog(activity,
            pictures.map { GalleryPicture(it.id, it.title, url = it.thumbnail, context = it.context) },
            pictures.indexOfFirst { it.id == id }, images,
            isSelected = { it in selected },
            select = { key, checked -> if (checked) selected.add(key) else selected.remove(key); sync() },
            selectedCount = { choices().size },
            share = { picture ->
                activity.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, picture.url)
                }, "分享图片链接"))
            },
            export = { picture ->
                session.groups[picture.id]?.let { group ->
                    group.variants.firstOrNull { it.blockedReason.isEmpty() }?.let { variant -> submit(listOf(group to variant)) }
                }
            },
            onDismiss = { if (preview === viewer) preview = null })
        preview = viewer; viewer.show()
    }
}
