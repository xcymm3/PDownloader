package com.pdownloader.browser

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.*

internal data class MenuAction(val icon: String, val title: String, val enabled: Boolean = true, val run: () -> Unit)

/** Native popup, with navigation actions above labelled icon rows. */
internal class BrowserMenu(private val activity: Activity, shortcuts: List<MenuAction>, rows: List<MenuAction>) {
    private val popup: PopupWindow
    val content: View
    init {
        val body = activity.column().apply { background = activity.shape(R.color.menu_surface, 20); clipToOutline = true; setPadding(0, activity.dp(4), 0, activity.dp(8)) }
        val top = activity.row().apply { setPadding(activity.dp(4), 0, activity.dp(4), 0) }
        shortcuts.forEach { action ->
            top.addView(BrowserIcon(activity, action.icon, action.title) { dismiss(); action.run() }.apply { isEnabled = action.enabled }, LinearLayout.LayoutParams(0, activity.dp(48), 1f))
        }
        body.addView(top)
        body.addView(View(activity).apply { setBackgroundColor(activity.color(R.color.divider)) }, LinearLayout.LayoutParams(-1, activity.dp(1)))
        rows.forEach { action ->
            val row = activity.row().apply {
                isClickable = true; isFocusable = true; contentDescription = action.title; isEnabled = action.enabled
                setPadding(activity.dp(8), 0, activity.dp(12), 0)
                setOnClickListener { dismiss(); action.run() }
            }
            row.addView(BrowserIcon(activity, action.icon, action.title) { dismiss(); action.run() }.apply { isEnabled = action.enabled }, LinearLayout.LayoutParams(activity.dp(48), activity.dp(48)))
            row.addView(activity.label(action.title, 16f), LinearLayout.LayoutParams(0, activity.dp(48), 1f)); body.addView(row)
        }
        val scroll = ScrollView(activity).apply { addView(body) }
        content = body
        popup = PopupWindow(scroll, minOf(activity.dp(204), activity.resources.displayMetrics.widthPixels - activity.dp(16)), -2, true).apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT)); elevation = activity.dp(12).toFloat(); isOutsideTouchable = true
        }
    }
    fun show(anchor: View) { popup.showAsDropDown(anchor, 0, -anchor.height, android.view.Gravity.END) }
    fun dismiss() { popup.dismiss() }
}
