package com.pdownloader.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
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
        val root = column().apply { setBackgroundColor(color(R.color.surface)) }
        val heading = row()
        heading.addView(BrowserIcon(this, "back", "返回") { finish() }, LinearLayout.LayoutParams(dp(48), dp(52)))
        heading.addView(label("设置", 21f), LinearLayout.LayoutParams(0, dp(52), 1f))
        root.addView(heading)
        val body = column().apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }
        val scroll = ScrollView(this).apply { addView(body) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        fun section(title: String) {
            body.addView(label(title, 13f, true).apply { setPadding(dp(16), dp(16), 0, 0) }, LinearLayout.LayoutParams(-1, dp(44)))
        }
        fun option(title: String, value: String, run: () -> Unit) = action("$title    $value", run).apply {
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            body.addView(this, LinearLayout.LayoutParams(-1, dp(52)))
        }
        fun toggle(title: String, checked: Boolean, set: (Boolean) -> Unit) {
            body.addView(Switch(this).apply {
                text = title
                textSize = 14f
                setTextColor(color(R.color.ink))
                setPadding(dp(16), 0, dp(16), 0)
                isChecked = checked
                setOnCheckedChangeListener { _, value -> set(value) }
            }, LinearLayout.LayoutParams(-1, dp(52)))
        }
        section("外观")
        option("主题", themeName(), ::chooseTheme)
        section("下载")
        val locations = arrayOf("公共下载目录", "应用私有目录")
        val location = option("下载到", locations[if (settings.saveToPublic) 0 else 1]) {}
        location.setOnClickListener {
            AlertDialog.Builder(this).setTitle("下载到")
                .setSingleChoiceItems(locations, if (settings.saveToPublic) 0 else 1) { dialog, index ->
                    settings.saveToPublic = index == 0
                    location.text = "下载到    ${locations[index]}"
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        val parallel = option("同时下载数", settings.parallelDownloads.toString()) {}
        parallel.setOnClickListener {
            chooseDownloadLimit(settings) { parallel.text = "同时下载数    $it" }
        }
        toggle("下载完成通知", settings.notifyCompletion) { settings.notifyCompletion = it }
        toggle("按流量计费网络下添加任务前提醒", settings.warnOnMetered) { settings.warnOnMetered = it }
        section("隐私和网站")
        option("网站权限", "", ::showSitePermissions)
        section("版本更新")
        option("检查更新", "当前版本 ${BuildConfig.VERSION_NAME}", updates::check)

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
}

internal fun Activity.chooseDownloadLimit(settings: DownloadSettings, onChanged: (Int) -> Unit) {
    val options = (1..DownloadSettings.MAX_PARALLEL_DOWNLOADS).map { getString(R.string.parallel_download_limit, it) }
    AlertDialog.Builder(this).setTitle(R.string.parallel_download_title)
        .setSingleChoiceItems(options.toTypedArray(), settings.parallelDownloads - 1) { dialog, index ->
            settings.parallelDownloads = index + 1
            onChanged(index + 1)
            dialog.dismiss()
        }.setNegativeButton(android.R.string.cancel, null).show()
}
