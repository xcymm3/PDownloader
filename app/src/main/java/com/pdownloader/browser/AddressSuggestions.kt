package com.pdownloader.browser

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView

internal data class AddressSuggestion(val title: String, val url: String, val source: String, val tabId: String? = null)

internal class AddressSuggestions(
    private val activity: Activity,
    private val lookup: (String, (List<AddressSuggestion>) -> Unit) -> Unit,
    private val select: (AddressSuggestion) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    fun bind(field: EditText): ScrollView {
        val rows = activity.column()
        val panel = ScrollView(activity).apply {
            addView(rows)
            background = activity.shape(R.color.surface_secondary, 16)
            elevation = activity.dp(6).toFloat()
            visibility = View.GONE
            isFocusable = false
        }
        var generation = 0
        var queued: Runnable? = null
        fun refresh() {
            val token = ++generation
            queued?.let(main::removeCallbacks)
            panel.visibility = View.GONE
            rows.removeAllViews()
            val query = field.text.toString().trim().take(250)
            if (!field.hasFocus() || query.isEmpty()) return
            queued = Runnable {
                if (!field.isShown || !field.hasFocus() || generation != token) return@Runnable
                lookup(query) { matches ->
                    if (generation == token && field.isShown && field.hasFocus()) {
                        for (match in matches) {
                            val entry = activity.column().apply {
                                setPadding(activity.dp(14), activity.dp(6), activity.dp(14), activity.dp(6))
                                minimumHeight = activity.dp(48)
                                isFocusable = true
                                val style = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                                foreground = style.getDrawable(0)
                                style.recycle()
                                setOnClickListener { select(match) }
                                addView(activity.label("${match.title.ifBlank { match.url }}  ·  ${match.source}", 14f, true).apply {
                                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                                })
                                addView(activity.label(match.url, 12f).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                            }
                            rows.addView(entry, LinearLayout.LayoutParams(-1, -2))
                        }
                        panel.scrollTo(0, 0)
                        panel.visibility = if (matches.isEmpty()) View.GONE else View.VISIBLE
                        val height = activity.dp(52 * matches.size.coerceAtMost(4))
                        panel.layoutParams = panel.layoutParams.apply { this.height = height }
                    }
                }
            }.also { main.postDelayed(it, 100) }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { refresh() }
        })
        field.setOnFocusChangeListener { _, _ -> refresh() }
        return panel
    }
}
