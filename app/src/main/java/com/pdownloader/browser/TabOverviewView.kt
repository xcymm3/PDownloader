package com.pdownloader.browser

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

data class TabCard(val id: String, val title: String, val url: String, val preview: Bitmap?, val selected: Boolean)

/** Hallmark · pre-emit critique: P4 H4 E4 S5 R5 V4. Two-column native tab overview. */
@android.annotation.SuppressLint("ViewConstructor")
class TabOverviewView(private val activity: Activity, back: () -> Unit, add: () -> Unit,
    private val select: (String) -> Unit, private val close: (String) -> Unit) : LinearLayout(activity) {
    private var cards = emptyList<TabCard>()
    private val grid = GridView(activity)
    private val heading = activity.label("标签页", 20f)
    private val adapter = object : BaseAdapter() {
        override fun getCount() = cards.size
        override fun getItem(position: Int) = cards[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val tab = getItem(position)
            val card = activity.column().apply {
                background = activity.shape(if (tab.selected) R.color.accent_surface else R.color.home_tile, 16).apply {
                    if (tab.selected) setStroke(activity.dp(2), activity.color(R.color.accent))
                }
                clipToOutline = true; setPadding(activity.dp(4), activity.dp(4), activity.dp(4), activity.dp(4))
                isSelected = tab.selected
            }
            val header = activity.row()
            header.addView(activity.label(tab.title, 13f).apply {
                setPadding(activity.dp(8), 0, 0, 0); setSingleLine(true); ellipsize = android.text.TextUtils.TruncateAt.END
                isClickable = true; isFocusable = true; setOnClickListener { select(tab.id) }
            }, LayoutParams(0, activity.dp(44), 1f))
            header.addView(BrowserIcon(activity, "close", "关闭标签页：${tab.title}") { close(tab.id) }, LayoutParams(activity.dp(40), activity.dp(44)))
            card.addView(header)
            val preview = FrameLayout(activity).apply {
                contentDescription = "${if (tab.selected) "当前标签，" else ""}${tab.title}，${tab.url.ifEmpty { "新标签页" }}"
                isClickable = true; isFocusable = true; setOnClickListener { select(tab.id) }
                background = activity.shape(R.color.surface, 12); clipToOutline = true
            }
            if (tab.preview != null) preview.addView(ImageView(activity).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(tab.preview) }, FrameLayout.LayoutParams(-1, -1))
            else preview.addView(activity.label(if (tab.url.isEmpty()) "PDownloader" else android.net.Uri.parse(tab.url).host.orEmpty(), 16f, true).apply {
                gravity = Gravity.CENTER; setPadding(activity.dp(8), 0, activity.dp(8), 0); maxLines = 2
            }, FrameLayout.LayoutParams(-1, -1))
            card.addView(preview, LayoutParams(-1, activity.dp(198)))
            return card
        }
    }
    init {
        orientation = VERTICAL; setBackgroundColor(activity.color(R.color.surface))
        val header = activity.row()
        header.addView(BrowserIcon(activity, "back", "返回浏览页", back), LayoutParams(activity.dp(48), activity.dp(56)))
        header.addView(heading, LayoutParams(0, activity.dp(56), 1f))
        header.addView(BrowserIcon(activity, "plus", "新建标签页", add), LayoutParams(activity.dp(48), activity.dp(56)))
        addView(header)
        grid.apply { numColumns = 2; horizontalSpacing = activity.dp(12); verticalSpacing = activity.dp(12)
            setPadding(activity.dp(16), activity.dp(12), activity.dp(16), activity.dp(16)); clipToPadding = false
            stretchMode = GridView.STRETCH_COLUMN_WIDTH; adapter = this@TabOverviewView.adapter; contentDescription = "标签页卡片网格"
        }
        addView(grid, LayoutParams(-1, 0, 1f))
    }
    fun submit(cards: List<TabCard>) { this.cards = cards; heading.text = activity.getString(R.string.tabs_count, cards.size); adapter.notifyDataSetChanged() }
}
