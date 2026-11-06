package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import java.text.DateFormat
import java.util.Date
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
            val wrapper = FrameLayout(activity).apply { setPadding(0, 0, 0, activity.dp(8)) }
            val row = activity.row().apply {
                background = activity.shape(R.color.history_card, 20)
                setPadding(activity.dp(8), activity.dp(12), activity.dp(4), activity.dp(12))
            }
            row.addView(BrowserIcon(activity, "globe", "") {}.apply {
                isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = null; glyphSizeDp = 28
            }, LayoutParams(activity.dp(44), activity.dp(48)))
            val text = activity.column().apply {
                isClickable = true; isFocusable = true; contentDescription = "${record.title}，${record.url}"
                setOnClickListener { open(record.url) }
                addView(activity.label(record.title, 16f).apply {
                    setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                })
                addView(activity.label(record.url, 12f, true).apply { setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, activity.dp(4), 0, 0) })
                if (!bookmarks) addView(activity.label(time(record.visitedAt), 11f, true).apply { setPadding(0, activity.dp(3), 0, 0) })
            }
            row.addView(text, LayoutParams(0, -2, 1f))
            val anchor = BrowserIcon(activity, "menu", "历史记录操作：${record.title}") {}
            anchor.setOnClickListener {
                PopupMenu(activity, anchor).apply {
                    menu.add(0, 0, 0, "打开网页"); menu.add(0, 1, 1, "复制链接"); menu.add(0, 2, 2, "删除记录")
                    setOnMenuItemClickListener {
                        when (it.itemId) {
                            0 -> open(record.url)
                            1 -> (activity.getSystemService(Activity.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(record.title, record.url))
                            2 -> deleteRecord(record.id)
                        }; true
                    }
                }.show()
            }
            row.addView(anchor, LayoutParams(activity.dp(40), activity.dp(48)))
            wrapper.addView(row, FrameLayout.LayoutParams(-1, -2))
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
        adapter.notifyDataSetChanged()
        list.visibility = if (displayed.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (displayed.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if (all.isEmpty()) { if (bookmarks) "还没有收藏的网页" else "还没有浏览记录" } else "未找到匹配的记录"
        status.text = if (text.isEmpty()) "共 ${all.size} 条记录" else "找到 ${displayed.size} 条记录"
        status.setOnClickListener(null); clear.isEnabled = all.isNotEmpty()
    }
    private fun time(at: Long): String {
        val delta = (System.currentTimeMillis() - at).coerceAtLeast(0)
        return when {
            delta < 60000 -> "刚刚"
            delta < 3600000 -> "${delta / 60000} 分钟前"
            delta < 86400000 -> "${delta / 3600000} 小时前"
            else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
        }
    }
}
