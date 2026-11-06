package com.pdownloader.browser

import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.GridView
import android.widget.ListView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** State/lifecycle calls and passive screenshots only, on the isolated validation AVD. */
@RunWith(AndroidJUnit4::class)
class BrowserRefreshTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun launch() = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse("https://pdownloader.test/video")))

    @Test fun tabsHaveTwoColumnsRealPreviewAndCorrectSelectionAfterClose() {
        launch().use { scenario ->
            scenario.onActivity { activity ->
                activity.openNewTab("https://pdownloader.test/video?tab=2")
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { it.showTabs() }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                val grid = descendants(activity.window.decorView).filterIsInstance<TabOverviewView>().single().let { descendants(it).filterIsInstance<GridView>().single() }
                assertEquals(2, grid.numColumns)
                assertTrue(grid.adapter.count >= 2)
                val cards = (0 until grid.adapter.count).map { grid.adapter.getItem(it) as TabCard }
                assertEquals(1, cards.count { it.selected })
                assertNotNull(cards.single { it.selected }.preview)
                val selected = cards.single { it.selected }
                activity.closeTab(selected.id)
                assertEquals(cards.size - 1, grid.adapter.count)
                val next = grid.adapter.getItem(0) as TabCard
                activity.selectTab(next.id)
                assertTrue(descendants(activity.window.decorView).any { it.isShown && (if (next.url.isEmpty()) it is HomePageView else it is WebView) })
                activity.showTabs()
            }
            capture("tabs-grid")
        }
    }

    @Test fun bookmarksAreIndependentAndDeletingThemKeepsHistory() {
        BrowserStore(context).use { store ->
            store.save("bookmarks", "收藏夹界面回归", "https://example.com/bookmarked")
            store.save("history", "需要保留的历史", "https://example.com/history-keep")
            launch().use { scenario ->
                scenario.onActivity { it.openBookmarks() }
                await { var ready = false; scenario.onActivity { a -> ready = records(a).isShown && list(a).adapter.count > 0 }; ready }
                scenario.onActivity { a -> records(a).query = "收藏夹界面回归"; assertEquals(1, list(a).adapter.count) }
                scenario.recreate()
                await { var ready = false; scenario.onActivity { a -> ready = records(a).isShown && list(a).adapter.count == 1 }; ready }
                capture("bookmarks-page")
                scenario.onActivity { a ->
                    assertEquals("收藏夹界面回归", records(a).query)
                    records(a).deleteRecord((list(a).adapter.getItem(0) as PageRecord).id)
                }
                await { store.list("bookmarks").none { it.url == "https://example.com/bookmarked" } }
                assertTrue(store.list("history").any { it.url == "https://example.com/history-keep" })
                scenario.onActivity { a -> a.closeBookmarks(); assertTrue(descendants(a.window.decorView).any { it is WebView && it.isShown }) }
            }
        }
    }

    @Test fun browserMenuOwnsNavigationAndHasNoResourceOrFixtureRows() {
        launch().use { scenario ->
            scenario.onActivity { a ->
                assertFalse(descendants(a.window.decorView).any { it.contentDescription == "刷新网页" && it.isShown })
                a.showMenu()
                val menu = a.menuContent!!
                val texts = descendants(menu).filterIsInstance<TextView>().map { it.text.toString() }.toList()
                assertFalse(texts.contains("收藏当前网页"))
                assertFalse(texts.any { it.contains("本页视频") || it.contains("重新检测") || it.contains("下载功能测试") })
                assertTrue(texts.contains("收藏夹"))
                listOf("后退", "前进", "刷新网页", "收藏当前网页").forEach { label -> assertTrue(descendants(menu).any { it.contentDescription == label }) }
            }
            capture("browser-menu")
            scenario.onActivity { it.selectSection(0) }
        }
    }

    @Test fun switchingDownloadPagesKeepsSeparateTitlesAndDoesNotRebindUnchangedMedia() {
        launch().use { scenario ->
            scenario.onActivity { a -> a.selectSection(2) }
            instrumentation.waitForIdleSync()
            scenario.onActivity { a ->
                val pages = descendants(a.window.decorView).filterIsInstance<DownloadsView>().toList()
                assertEquals(2, pages.size)
                val done = pages.single { it.completedPage }
                val grid = descendants(done).filterIsInstance<GridView>().single()
                grid.measure(View.MeasureSpec.makeMeasureSpec(a.dp(360), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(a.dp(500), View.MeasureSpec.EXACTLY))
                grid.layout(0, 0, a.dp(360), a.dp(500))
                assertEquals(2, grid.numColumns)
                assertEquals(DownloadCategory.VIDEO, done.selectedCategory)
                var rebounds = 0
                grid.adapter.registerDataSetObserver(object : android.database.DataSetObserver() { override fun onChanged() { rebounds++ } })
                repeat(10) {
                    a.selectSection(1)
                    assertTrue(pages.single { !it.completedPage }.isShown)
                    assertFalse(done.isShown)
                    a.selectSection(2)
                    assertTrue(done.isShown)
                }
                assertEquals(0, rebounds)
                val categoryNames = descendants(done).filterIsInstance<TextView>().filter { it.contentDescription?.startsWith("筛选：") == true }.map { it.text.toString() }.toList()
                assertEquals(2, categoryNames.size)
                assertFalse(categoryNames.any { it.contains("全部") })
            }
        }
    }
    private fun records(a: MainActivity) = descendants(a.window.decorView).filterIsInstance<HistoryView>().single { it.table == "bookmarks" }
    private fun list(a: MainActivity) = descendants(records(a)).filterIsInstance<ListView>().single()
    private fun descendants(v: View): Sequence<View> = sequence { yield(v); if (v is ViewGroup) for (i in 0 until v.childCount) yieldAll(descendants(v.getChildAt(i))) }
    private fun await(check: () -> Boolean) { val end = System.currentTimeMillis() + 20000; while (System.currentTimeMillis() < end) { if (check()) return; Thread.sleep(100) }; fail("等待浏览器状态超时") }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync(); Thread.sleep(500)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name-${System.currentTimeMillis()}.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PDownloader-030-qa")
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
}
