package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.Executor

/** Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4. An independent native page, not a dialog. */
@android.annotation.SuppressLint("ViewConstructor")
open class HistoryView(
    private val activity: Activity,
    private val store: BrowserStore,
    private val io: Executor,
    back: () -> Unit,
    private val open: (String) -> Unit,
    val table: String = "history"
) : LinearLayout(activity) {
    private val bookmarks = table == "bookmarks"
    private var all = emptyList<PageRecord>()
    private var displayed = emptyList<PageRecord>()
    private var dayHeaders = emptyMap<Long, String>()
    private val clockFormat = DateTimeFormatter.ofPattern("HH:mm")
    private var request = 0
    private var closed = false
    private var active = false
    private val search = EditText(activity)
    private val status = activity.label("正在读取…", 13f, true)
    private val empty = activity.label(if (bookmarks) "还没有收藏的网页" else "还没有浏览记录", 15f, true).apply { gravity = Gravity.CENTER }
    private val list = ListView(activity)
    private val clear = BrowserIcon(activity, "delete", "清空全部历史记录") { confirmClear() }
    private val adapter = object : BaseAdapter() {
        override fun getCount() = displayed.size
        override fun getItem(position: Int) = displayed[position]
        override fun getItemId(position: Int) = getItem(position).id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val record = getItem(position)
            val wrapper = activity.column().apply { setPadding(0, 0, 0, activity.dp(8)) }
            dayHeaders[record.id]?.let { heading ->
                wrapper.addView(activity.label(heading, 13f, true).apply {
                    setPadding(activity.dp(4), 0, 0, 0)
                    typeface = Typeface.DEFAULT_BOLD
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                }, LayoutParams(-1, activity.dp(34)))
            }
            val row = activity.row().apply {
                background = RippleDrawable(ColorStateList.valueOf(activity.color(R.color.accent_surface)), activity.shape(R.color.history_card, 20), null)
                setPadding(activity.dp(8), activity.dp(12), activity.dp(4), activity.dp(12))
                isClickable = true; isFocusable = true
                contentDescription = "${record.title}，${record.url}"
                setOnClickListener {
                    if (isSelected) return@setOnClickListener
                    isSelected = true
                    background = activity.shape(R.color.accent_surface, 20)
                    animate().scaleX(.98f).scaleY(.98f).setDuration(110).withEndAction {
                        if (active && !closed && isAttachedToWindow) open(record.url)
                    }.start()
                }
            }
            val avatar = FrameLayout(activity).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            val siteIcon = record.favicon
            val face = if (siteIcon != null) ImageView(activity).apply { setImageBitmap(siteIcon); scaleType = ImageView.ScaleType.FIT_CENTER }
            else activity.label(Uri.parse(record.url).host?.removePrefix("www.")?.firstOrNull()?.uppercase() ?: "•", 15f).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                background = activity.shape(R.color.home_search, 10)
            }
            avatar.addView(face, FrameLayout.LayoutParams(activity.dp(28), activity.dp(28), Gravity.CENTER))
            row.addView(avatar, LayoutParams(activity.dp(44), activity.dp(48)))
            val text = activity.column().apply {
                addView(activity.label(record.title, 16f).apply {
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(activity.label(record.url, 12f, true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, activity.dp(4), 0, 0) })
                val time = Instant.ofEpochMilli(record.visitedAt).atZone(ZoneId.systemDefault()).format(clockFormat)
                addView(activity.label(time, 11f, true).apply { setPadding(0, activity.dp(3), 0, 0) })
            }
            row.addView(text, LayoutParams(0, -2, 1f))
            row.addView(BrowserIcon(activity, "delete", "删除 ${record.title}") { confirmDelete(record) }, LayoutParams(activity.dp(48), activity.dp(48)))
            wrapper.addView(row, LayoutParams(-1, -2))
            return wrapper
        }
    }
    var query: String
        get() = search.text.toString()
        set(value) { search.setText(value) }

    init {
        tag = table
        orientation = VERTICAL; setBackgroundColor(activity.color(R.color.surface))
        val header = activity.row().apply { setPadding(activity.dp(4), activity.dp(4), activity.dp(8), activity.dp(4)) }
        header.addView(BrowserIcon(activity, "back", "返回浏览页", back), LayoutParams(activity.dp(48), activity.dp(48)))
        header.addView(activity.label(if (bookmarks) "收藏夹" else "历史记录", 21f), LayoutParams(0, activity.dp(52), 1f))
        clear.visibility = if (bookmarks) View.GONE else View.VISIBLE
        header.addView(clear, LayoutParams(activity.dp(48), activity.dp(48))); addView(header)
        val field = activity.row().apply { background = activity.shape(R.color.home_search, 28) }
        field.addView(BrowserIcon(activity, "search", "") {}.apply { isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO; background = null }, LayoutParams(activity.dp(44), activity.dp(52)))
        search.apply {
            contentDescription = if (bookmarks) "搜索收藏夹" else "搜索历史记录"; hint = "搜索标题或网址"; textSize = 15f; isSingleLine = true
            setTextColor(activity.color(R.color.ink)); setHintTextColor(activity.color(R.color.muted)); background = null; setPadding(0, 0, 0, 0)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        field.addView(search, LayoutParams(0, activity.dp(52), 1f))
        field.addView(BrowserIcon(activity, "close", "清除历史搜索条件") { query = "" }, LayoutParams(activity.dp(44), activity.dp(52)))
        addView(field, LayoutParams(-1, activity.dp(52)).apply { setMargins(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(8)) })
        status.setPadding(activity.dp(20), 0, activity.dp(20), 0)
        addView(status, LayoutParams(-1, activity.dp(36)))
        val body = FrameLayout(activity)
        list.apply {
            divider = null; dividerHeight = 0; adapter = this@HistoryView.adapter
            setPadding(activity.dp(16), activity.dp(4), activity.dp(16), activity.dp(16)); clipToPadding = false
            contentDescription = if (bookmarks) "收藏列表" else "浏览历史列表"
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1)); body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        addView(body, LayoutParams(-1, 0, 1f))
        isFocusableInTouchMode = true; requestFocus()
        clear.isEnabled = false
    }

    fun show() { active = true; visibility = View.VISIBLE; refresh() }
    fun hide() { active = false; ++request; visibility = View.GONE }
    fun close() { closed = true; ++request }
    fun refresh() = read { store.list(table) }
    fun deleteRecord(id: Long) = read { store.delete(table, id); store.list(table) }
    // Called only after confirmation in the UI; also usable by native integration checks.
    fun clearRecords() { check(!bookmarks); read { store.clearHistory(); store.list(table) } }

    private fun confirmDelete(record: PageRecord) {
        AlertDialog.Builder(activity).setTitle(if (bookmarks) "删除这条收藏？" else "删除这条历史记录？")
            .setMessage(record.title).setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteRecord(record.id) }.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(activity).setTitle("清空全部浏览历史？").setMessage("此操作无法撤销，收藏夹将保留。")
            .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> clearRecords() }.show()
    }
    private fun read(operation: () -> List<PageRecord>) {
        val token = ++request
        status.text = "正在读取…"
        io.execute {
            val result = runCatching(operation)
            activity.runOnUiThread {
                if (closed || !active || token != request || activity.isDestroyed) return@runOnUiThread
                result.onSuccess { all = it; filter() }.onFailure {
                    status.text = "读取失败，点击重试"
                    status.setOnClickListener { refresh() }; empty.text = "暂时无法读取历史记录"
                    list.visibility = View.GONE; empty.visibility = View.VISIBLE
                }
            }
        }
    }
    private fun filter() {
        val text = query.trim()
        displayed = all.filter { text.isEmpty() || it.title.contains(text, true) || it.url.contains(text, true) }
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        dayHeaders = buildMap {
            var previous: LocalDate? = null
            for (record in displayed) {
                val day = Instant.ofEpochMilli(record.visitedAt).atZone(zone).toLocalDate()
                if (day != previous) put(record.id, historyDayLabel(day, today))
                previous = day
            }
        }
        adapter.notifyDataSetChanged()
        list.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (all.isEmpty()) { if (bookmarks) "还没有收藏的网页" else "还没有浏览记录" } else "未找到匹配的记录"
        status.text = if (text.isEmpty()) "共 ${all.size} 条记录" else "找到 ${displayed.size} 条记录"
        status.setOnClickListener(null); clear.isEnabled = all.isNotEmpty()
    }
}

internal fun historyDayLabel(day: LocalDate, today: LocalDate): String {
    val date = "${day.year}年${day.monthValue}月${day.dayOfMonth}日"
    val prefix = when {
        day == today -> "今天"
        day == today.minusDays(1) -> "昨天"
        day > today -> return date
        day >= today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) -> "本周"
        day > today.minusDays(7) -> "最近一周"
        else -> "一周前"
    }
    return "$prefix - $date"
}
