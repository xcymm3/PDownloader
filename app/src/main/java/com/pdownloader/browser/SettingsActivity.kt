package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import android.view.Gravity
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.pdownloader.browser.download.DownloadSettings
import com.pdownloader.browser.update.AppUpdateController

private enum class BrowserTheme(val label: String, val night: Int?) {
    SYSTEM("跟随系统", null), LIGHT("浅色", Configuration.UI_MODE_NIGHT_NO), DARK("深色", Configuration.UI_MODE_NIGHT_YES)
}

abstract class ThemedActivity : Activity() {
    private lateinit var appliedTheme: BrowserTheme

    private fun themePreference(context: Context) = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)

    private fun selectedTheme(context: Context): BrowserTheme = BrowserTheme.entries.firstOrNull {
        it.name == themePreference(context).getString("theme", BrowserTheme.SYSTEM.name)
    } ?: BrowserTheme.SYSTEM

    override fun attachBaseContext(newBase: Context) {
        appliedTheme = selectedTheme(newBase)
        val night = appliedTheme.night
        val themed = if (night == null) newBase else newBase.createConfigurationContext(Configuration().apply {
            uiMode = (newBase.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
        super.attachBaseContext(themed)
    }

    override fun onResume() {
        super.onResume()
        if (appliedTheme != selectedTheme(this)) recreate()
    }

    internal fun themeName() = selectedTheme(this).label

    internal fun chooseTheme() {
        AlertDialog.Builder(this).setTitle("主题")
            .setSingleChoiceItems(BrowserTheme.entries.map { it.label }.toTypedArray(), selectedTheme(this).ordinal) { dialog, index ->
                val theme = BrowserTheme.entries[index]
                dialog.dismiss()
                if (theme != appliedTheme) {
                    themePreference(this).edit().putString("theme", theme.name).apply()
                    recreate()
                }
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}

class SettingsActivity : ThemedActivity() {
    private val updates by lazy { AppUpdateController(this) }

    override fun onResume() {
        super.onResume()
        updates.resume(automatic = false)
    }

    override fun onPause() {
        updates.pause()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = DownloadSettings(this)
        val playback = PlaybackSettings(this)
        val root = column().apply { setBackgroundColor(color(R.color.surface_secondary)) }
        val heading = row().apply { setPadding(dp(8), dp(8), dp(16), dp(8)) }
        heading.addView(BrowserIcon(this, "back", "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(52)))
        heading.addView(label("设置", 21f), LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(heading)
        val body = column().apply { setPadding(dp(16), dp(4), dp(16), dp(32)) }
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        var group = column()
        fun section(title: String) {
            body.addView(label(title, 13f).apply {
                setTextColor(color(R.color.accent))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(8), dp(20), 0, dp(8))
            }, LinearLayout.LayoutParams(-1, -2))
            group = column().apply { background = shape(R.color.surface, 18); clipToOutline = true }
            body.addView(group, LinearLayout.LayoutParams(-1, -2))
        }
        fun option(title: String, value: String, glyph: String, run: () -> Unit): SettingsOption {
            val entry = SettingsOption(this, title, value, glyph, run)
            group.addView(entry, LinearLayout.LayoutParams(-1, -2))
            return entry
        }
        fun toggle(title: String, checked: Boolean, set: (Boolean) -> Unit) {
            group.addView(Switch(this).apply {
                text = title
                textSize = 15f
                setTextColor(color(R.color.ink))
                setPadding(dp(16), 0, dp(16), 0)
                isChecked = checked
                setOnCheckedChangeListener { _, value -> set(value) }
                minHeight = dp(64)
            }, LinearLayout.LayoutParams(-1, -2))
        }
        section("外观")
        option("主题", themeName(), "settings", ::chooseTheme)
        section("播放")
        toggle("播放器手势", playback.gesturesEnabled) { playback.gesturesEnabled = it }
        section("下载")
        val locations = arrayOf("公共下载目录", "应用私有目录")
        val location = option("下载到", locations[if (settings.saveToPublic) 0 else 1], "download") {}
        location.setOnClickListener {
            AlertDialog.Builder(this).setTitle("下载到")
                .setSingleChoiceItems(locations, if (settings.saveToPublic) 0 else 1) { dialog, index ->
                    settings.saveToPublic = index == 0
                    location.value.text = locations[index]
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        val parallel = row().apply { setPadding(dp(16), dp(4), dp(16), dp(4)) }
        parallel.addView(label("同时下载数", 16f), LinearLayout.LayoutParams(0, dp(56), 1f))
        parallel.addView(downloadLimitSelector(settings), LinearLayout.LayoutParams(dp(80), dp(48)))
        group.addView(parallel)
        toggle("下载完成通知", settings.notifyCompletion) { settings.notifyCompletion = it }
        toggle("按流量计费网络下添加任务前提醒", settings.warnOnMetered) { settings.warnOnMetered = it }
        section("隐私和网站")
        option("网站权限", "摄像头、麦克风和定位", "globe", ::showSitePermissions)
        option("清除 Cookie", "清除网站登录状态", "delete", ::confirmClearCookies)
        section("版本更新")
        option("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", "reload", updates::check)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val light = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        setContentView(root)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun showSitePermissions() {
        val store = SitePermissionStore(this)
        val grants = store.grants().sortedWith(compareBy({ it.first }, { it.second.ordinal }))
        val dialog = AlertDialog.Builder(this).setTitle("网站权限").setNegativeButton("关闭", null)
        if (grants.isEmpty()) dialog.setMessage("暂无已授权的网站。")
        else dialog.setItems(grants.map { (site, capability) -> "$site\n${capability.label} · 已允许" }.toTypedArray()) { _, index ->
            val (site, capability) = grants[index]
            AlertDialog.Builder(this).setTitle("撤销${capability.label}权限？").setMessage(site)
                .setNegativeButton("取消", null).setPositiveButton("撤销") { _, _ -> store.revoke(site, capability); showSitePermissions() }
                .show()
        }
        dialog.show()
    }

    private fun confirmClearCookies() {
        AlertDialog.Builder(this).setTitle("清除所有网站的 Cookie？")
            .setMessage("网站可能需要重新登录。浏览历史、收藏夹和下载文件会保留。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies { removed ->
                    cookies.flush()
                    if (!isDestroyed) Toast.makeText(this, if (removed) "已清除 Cookie" else "没有可清除的 Cookie", Toast.LENGTH_SHORT).show()
                }
            }.show()
    }
}

internal fun Activity.downloadLimitSelector(settings: DownloadSettings): Spinner = Spinner(this).apply {
    contentDescription = "同时下载数"
    background = shape(R.color.surface_secondary, 12)
    adapter = object : ArrayAdapter<String>(this@downloadLimitSelector, android.R.layout.simple_spinner_dropdown_item,
        (1..DownloadSettings.MAX_PARALLEL_DOWNLOADS).map(Int::toString)) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also { style(it); (it as TextView).text = "${position + 1} ▾" }
        override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getDropDownView(position, convertView, parent).also { style(it) }
        private fun style(view: View) {
            (view as TextView).apply { setTextColor(color(R.color.ink)); textSize = 16f; gravity = Gravity.CENTER; minHeight = dp(48) }
        }
    }
    setSelection(settings.parallelDownloads - 1)
    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            settings.parallelDownloads = position + 1
        }
    }
}

private class SettingsOption(
    context: Context, title: String, summary: String, glyph: String, action: () -> Unit
) : LinearLayout(context) {
    val value = context.label(summary, 13f, true).apply { visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE }
    init {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = context.dp(76)
        setPadding(context.dp(12), context.dp(12), context.dp(12), context.dp(12))
        isClickable = true; isFocusable = true
        foreground = RippleDrawable(ColorStateList.valueOf(context.color(R.color.accent_surface)), null, context.shape(R.color.surface))
        setOnClickListener { action() }
        addView(BrowserIcon(context, glyph, "").apply {
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            foregroundColor = context.color(R.color.accent); background = null
        }, LayoutParams(context.dp(44), context.dp(48)))
        val words = context.column()
        words.addView(context.label(title, 16f))
        words.addView(value, LayoutParams(-1, -2).apply { topMargin = context.dp(5) })
        addView(words, LayoutParams(0, -2, 1f))
        addView(BrowserIcon(context, "forward", "").apply {
            isClickable = false; isFocusable = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            foregroundColor = context.color(R.color.muted); background = null; glyphSizeDp = 16
        }, LayoutParams(context.dp(28), context.dp(32)))
    }
}
