package com.pdownloader.browser

import android.app.UiModeManager
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.EditText
import android.widget.ListView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Native lifecycle, data and configuration APIs only; never injects input. Isolated test AVD. */
@RunWith(AndroidJUnit4::class)
class HomeHistoryTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var store: BrowserStore
    @Before fun setup() {
        context.getSharedPreferences("session", 0).edit().clear().commit()
        store = BrowserStore(context)
        store.clearHistory()
        store.list("bookmarks").forEach { store.delete("bookmarks", it.id) }
    }
    @After fun close() { store.close() }
    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))

    @Test fun homeFitsFourWidthsAndSearchOpensAnActualWebView() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                activity.navigateHome()
                val home = descendants(activity.window.decorView).filterIsInstance<HomePageView>().single()
                assertTrue(home.isShown)
                assertFalse(home.submit())
                assertEquals(6, home.sites.size)
                for (width in listOf(320, 375, 414, 768)) {
                    val sample = HomePageView(activity, {}, {}, {})
                    val px = activity.dp(width)
                    sample.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(activity.dp(720), View.MeasureSpec.EXACTLY))
                    sample.layout(0, 0, px, activity.dp(720))
                    descendants(sample).forEach { child ->
                        val parent = child.parent as? ViewGroup
                        if (parent != null && child.visibility != View.GONE) {
                            assertTrue("$width dp: child extends beyond right edge", child.right <= parent.width)
                            assertTrue("$width dp: child extends beyond left edge", child.left >= 0)
                        }
                    }
                }
                home.query = "pdownloader.test/video"
                assertTrue(home.submit())
                assertTrue(descendants(activity.window.decorView).any { it is WebView && it.isShown })
                assertFalse(home.isAttachedToWindow)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                val address = descendants(activity.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == "地址栏" }
                assertTrue(address.isShown)
                assertEquals("https://pdownloader.test/video", address.text.toString())
                val web = descendants(activity.window.decorView).filterIsInstance<WebView>().single()
                activity.openHistory()
                assertFalse(web.isShown)
                activity.closeHistory()
                assertSame(web, descendants(activity.window.decorView).filterIsInstance<WebView>().single())
                assertTrue(web.isShown)
            }
        }
    }

    @Test fun independentHistorySearchDeleteClearAndRecreationPreserveBookmarks() {
        store.save("history", "B站视频回归", "https://www.bilibili.com/video/test")
        store.save("history", "Google 搜索回归", "https://www.google.com/search?q=example")
        store.save("history", "YouTube 回归", "https://www.youtube.com/watch?v=example")
        store.save("bookmarks", "需要保留的收藏", "https://example.com/bookmark")
        launch().use { scenario ->
            scenario.onActivity { it.openHistory() }
            await { scenario.check { historyList(it).adapter.count == 3 } }
            scenario.onActivity { activity ->
                val history = history(activity)
                assertTrue(history.isShown)
                assertFalse(descendants(activity.window.decorView).filterIsInstance<EditText>().single { it.contentDescription == "地址栏" }.isShown)
                history.query = "GOOGLE"
                assertEquals(1, historyList(activity).adapter.count)
                assertTrue((historyList(activity).adapter.getItem(0) as PageRecord).url.contains("google.com"))
            }
            scenario.recreate()
            await { scenario.check { history(it).isShown && historyList(it).adapter.count == 1 } }
            scenario.onActivity { activity ->
                assertEquals("GOOGLE", history(activity).query)
                val record = historyList(activity).adapter.getItem(0) as PageRecord
                history(activity).deleteRecord(record.id)
            }
            await { store.list("history").size == 2 && scenario.check { historyList(it).adapter.count == 0 } }
            scenario.onActivity { history(it).query = "" }
            await { scenario.check { historyList(it).adapter.count == 2 } }
            scenario.onActivity { history(it).clearRecords() }
            await { store.list("history").isEmpty() && scenario.check { historyList(it).adapter.count == 0 } }
            assertEquals("https://example.com/bookmark", store.list("bookmarks").single().url)
            scenario.onActivity { activity ->
                activity.closeHistory()
                assertTrue(descendants(activity.window.decorView).filterIsInstance<HomePageView>().single().isShown)
            }
        }
    }

    @Test fun lightAndDarkPagesHavePassiveScreenshots() {
        val entries = listOf("百度" to "https://www.baidu.com/", "Google" to "https://www.google.com/", "哔哩哔哩" to "https://www.bilibili.com/", "YouTube" to "https://www.youtube.com/", "X" to "https://x.com/", "Instagram" to "https://www.instagram.com/")
        entries.forEach { (title, url) -> store.save("history", "$title · 界面测试记录", url) }
        launch().use { scenario ->
            try {
                for ((theme, mode) in listOf("light" to UiModeManager.MODE_NIGHT_NO, "dark" to UiModeManager.MODE_NIGHT_YES)) {
                    if (android.os.Build.VERSION.SDK_INT >= 31) scenario.onActivity { it.getSystemService(UiModeManager::class.java).setApplicationNightMode(mode) }
                    await { scenario.check { (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == (if (mode == UiModeManager.MODE_NIGHT_YES) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO) } }
                    scenario.onActivity { it.navigateHome() }
                    capture("home-$theme")
                    scenario.onActivity { it.openHistory() }
                    await { scenario.check { historyList(it).adapter.count == 6 } }
                    capture("history-$theme")
                }
            } finally {
                if (android.os.Build.VERSION.SDK_INT >= 31) scenario.onActivity { it.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO) }
            }
        }
    }

    private fun history(activity: MainActivity) = descendants(activity.window.decorView).filterIsInstance<HistoryView>().single { it.table == "history" }
    private fun historyList(activity: MainActivity) = descendants(history(activity)).filterIsInstance<ListView>().single()
    private fun ActivityScenario<MainActivity>.check(block: (MainActivity) -> Boolean): Boolean { var result = false; onActivity { result = block(it) }; return result }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    private fun await(check: () -> Boolean) {
        val until = System.currentTimeMillis() + 20000
        while (System.currentTimeMillis() < until) { if (check()) return; Thread.sleep(100) }
        fail("等待首页或历史记录超时")
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("无法读取测试截图")
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PDownloader-home-qa")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
