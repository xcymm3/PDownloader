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
    private val retry: () -> Unit
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
    private val download = activity.action("下载（0）") { downloadSelected() }
    private var expanded = false
    private var preview: Dialog? = null
    private var previewIndex = 0
    private var previewRender: (() -> Unit)? = null

    init {
        session.groups.values.filter { !it.image }.forEach { selected.add(it.id) }
        val body = activity.column().apply { setBackgroundColor(activity.color(R.color.surface)); setPadding(activity.dp(16), 0, activity.dp(16), activity.dp(12)) }
        val handle = activity.label("━", 20f, true).apply { gravity = Gravity.CENTER; contentDescription = "展开或收起资源面板" }
        var startY = 0f
        handle.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startY = event.rawY; true }
                MotionEvent.ACTION_UP -> { if (kotlin.math.abs(event.rawY - startY) > activity.dp(16)) { expanded = event.rawY < startY; resize() } else view.performClick(); true }
                else -> true
            }
        }
        handle.setOnClickListener { expanded = !expanded; resize() }
        body.addView(handle, LinearLayout.LayoutParams(-1, activity.dp(28)))
        val top = activity.row()
        top.addView(activity.label("本页资源", 20f), LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        top.addView(activity.action("重新检测") { dialog.dismiss(); retry() })
        top.addView(BrowserIcon(activity, "close", "关闭资源面板") { dialog.dismiss() }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
        body.addView(top)
        val content = activity.column()
        status.setPadding(0, activity.dp(8), 0, activity.dp(16)); content.addView(status)
        val videoBar = activity.row()
        videoBar.addView(videoHeading, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        videoBar.addView(activity.action("全选") { session.groups.values.filter { !it.image && it.variants.any { v -> v.blockedReason.isEmpty() } }.forEach { selected.add(it.id) }; sync() })
        videoBar.addView(activity.action("清空") { selected.removeAll(session.groups.values.filter { !it.image }.map { it.id }.toSet()); sync() })
        content.addView(videoBar); content.addView(videoList)
        val imageBar = activity.row()
        imageBar.addView(imageHeading, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        imageBar.addView(activity.action("全选") { session.groups.values.filter { it.image }.forEach { selected.add(it.id) }; sync() })
        imageBar.addView(activity.action("清空") { selected.removeAll(session.groups.values.filter { it.image }.map { it.id }.toSet()); sync() })
        content.addView(imageBar); content.addView(grid)
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        download.background = activity.shape(R.color.accent, 12)
        download.setTextColor(activity.color(R.color.on_accent))
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
        status.text = if (session.groups.isEmpty()) session.message else "选择需要的版本后下载。HLS 将保存为离线资源包。"
        session.groups.values.filter { !it.image }.forEach { group ->
            val row = rows.getOrPut(group.id) {
                activity.column().apply {
                    setPadding(activity.dp(12), activity.dp(12), activity.dp(12), activity.dp(12))
                    background = activity.shape(R.color.surface_secondary, 12)
                    val header = activity.row()
                    val cover = FrameLayout(activity)
                    val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(activity.color(R.color.divider)); contentDescription = "视频封面" }
                    cover.addView(image, FrameLayout.LayoutParams(-1, -1)); images.load(image, group.thumbnail, group.context)
                    cover.addView(activity.label("▶", 24f).apply { gravity = Gravity.CENTER; setTextColor(activity.color(R.color.accent)) }, FrameLayout.LayoutParams(-1, -1))
                    cover.setOnClickListener { chosen(group)?.let { previewVideo(group, it) } }
                    header.addView(cover, LinearLayout.LayoutParams(activity.dp(92), activity.dp(69)))
                    val info = activity.column().apply { setPadding(activity.dp(10), 0, 0, 0) }
                    val checkbox = CheckBox(activity).apply {
                        text = group.title; textSize = 14f; maxLines = 2; setTextColor(activity.color(R.color.ink))
                        buttonTintList = ColorStateList.valueOf(activity.color(R.color.accent))
                        isChecked = group.id in selected
                        setOnCheckedChangeListener { _, checked -> if (checked) selected.add(group.id) else selected.remove(group.id); sync() }
                    }
                    controls[group.id] = checkbox
                    info.addView(checkbox)
                    val actions = activity.row()
                    actions.addView(activity.action("重命名") {
                        val input = EditText(activity).apply { setText(names[group.id] ?: group.title); isSingleLine = true }
                        AlertDialog.Builder(activity).setTitle("下载名称").setView(input).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
                            names[group.id] = input.text.toString().take(200).ifBlank { group.title }; checkbox.text = names[group.id]
                        }.show()
                    }, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
                    actions.addView(activity.action("在线播放") { chosen(group)?.let { previewVideo(group, it) } }, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
                    info.addView(actions)
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
                        textSize = 13f; minHeight = activity.dp(48); setTextColor(activity.color(R.color.ink))
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
                    contentDescription = "选择图片"; buttonTintList = ColorStateList.valueOf(activity.color(R.color.accent))
                    background = activity.shape(R.color.surface, 24)
                    setOnCheckedChangeListener { _, value -> if (value) selected.add(group.id) else selected.remove(group.id); sync() }
                }
                controls[group.id] = check
                cell.addView(check, FrameLayout.LayoutParams(activity.dp(48), activity.dp(48), Gravity.TOP or Gravity.END))
                cell.addView(activity.label(group.variants.first().quality, 10f).apply { background = activity.shape(R.color.surface); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, activity.dp(22), Gravity.BOTTOM))
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
        download.text = activity.getString(R.string.media_count, "下载", count); download.isEnabled = count > 0; download.alpha = if (count > 0) 1f else .45f
        previewRender?.invoke()
    }
    private fun downloadSelected() {
        val snapshot = choices().toList()
        if (snapshot.isNotEmpty()) { download.isEnabled = false; submit(snapshot); dialog.dismiss() }
    }

    private fun showImage(id: String) {
        preview?.dismiss()
        val pictures = session.groups.values.filter { it.image }.toList()
        previewIndex = pictures.indexOfFirst { it.id == id }.coerceAtLeast(0)
        val viewer = Dialog(activity)
        preview = viewer
        val layout = activity.column().apply { setBackgroundColor(activity.color(R.color.surface)) }
        val top = activity.row()
        top.addView(activity.action("返回") { viewer.dismiss() })
        val page = activity.label("", 16f)
        top.addView(page, LinearLayout.LayoutParams(0, activity.dp(60), 1f))
        val check = CheckBox(activity).apply { text = "选择"; setTextColor(activity.color(R.color.ink)); buttonTintList = ColorStateList.valueOf(activity.color(R.color.accent)) }
        top.addView(check); layout.addView(top)
        val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "图片预览" }
        layout.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = activity.row()
        bottom.addView(activity.action("上一张") { if (previewIndex > 0) { previewIndex--; previewRender?.invoke() } }, LinearLayout.LayoutParams(0, activity.dp(60), 1f))
        bottom.addView(activity.action("下载所选") { downloadSelected() }, LinearLayout.LayoutParams(0, activity.dp(60), 1f))
        bottom.addView(activity.action("下一张") { if (previewIndex < pictures.lastIndex) { previewIndex++; previewRender?.invoke() } }, LinearLayout.LayoutParams(0, activity.dp(60), 1f))
        layout.addView(bottom)
        previewRender = {
            val item = pictures[previewIndex]
            page.text = activity.getString(R.string.image_page, previewIndex + 1, pictures.size, choices().size)
            check.setOnCheckedChangeListener(null); check.isChecked = item.id in selected
            check.setOnCheckedChangeListener { _, value -> if (value) selected.add(item.id) else selected.remove(item.id); sync() }
            if (image.tag != item.thumbnail) { image.setImageDrawable(null); images.load(image, item.thumbnail, item.context) }
        }
        var start = 0f
        image.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { start = event.x; true }
                MotionEvent.ACTION_UP -> {
                    val delta = event.x - start
                    if (kotlin.math.abs(delta) > activity.dp(40)) { previewIndex = (previewIndex + if (delta < 0) 1 else -1).coerceIn(0, pictures.lastIndex); previewRender?.invoke() } else view.performClick()
                    true
                }
                else -> true
            }
        }
        viewer.setContentView(layout); viewer.setOnDismissListener { previewRender = null; preview = null }
        viewer.show(); viewer.window?.setLayout(-1, (activity.resources.displayMetrics.heightPixels * .92).toInt())
        previewRender?.invoke()
    }
}
