package com.pdownloader.browser

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Database integration tests; no simulated touches or desktop automation. */
@RunWith(AndroidJUnit4::class)
class BrowserStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: BrowserStore
    @Before fun setup() { context.deleteDatabase("browser.db"); store = BrowserStore(context) }
    @After fun teardown() { store.close(); context.deleteDatabase("browser.db") }
    @Test fun bookmarksSurviveReopeningAndDeduplicateByUrl() {
        store.save("bookmarks", "Example", "https://example.com")
        store.save("bookmarks", "New title", "https://example.com")
        store.close(); store = BrowserStore(context)
        assertEquals(1, store.list("bookmarks").size)
        assertEquals("New title", store.list("bookmarks").single().title)
        store.delete("bookmarks", store.list("bookmarks").single().id)
        assertTrue(store.list("bookmarks").isEmpty())
    }
    @Test fun clearingHistoryPreservesBookmarks() {
        store.save("bookmarks", "Bookmark", "https://example.com")
        store.save("history", "Page", "https://example.com")
        store.save("history", "Page revisited", "https://example.com")
        assertEquals(1, store.list("history").size)
        store.clearHistory()
        assertTrue(store.list("history").isEmpty())
        assertEquals(1, store.list("bookmarks").size)
    }
    @Test fun historyHasABoundedSize() {
        repeat(2005) { store.save("history", "Page $it", "https://example.com/$it") }
        val records = store.list("history")
        assertEquals(2000, records.size)
        assertTrue(records.any { it.url == "https://example.com/2004" })
    }
    @Test fun existingRecordsUpgradeAndSharePersistentSiteIcons() {
        val file = context.getDatabasePath("browser.db")
        file.parentFile?.mkdirs()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            for (table in listOf("history", "bookmarks")) {
                db.execSQL("CREATE TABLE $table (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, visited_at INTEGER NOT NULL)")
                db.execSQL("INSERT INTO $table VALUES (1, '旧记录', 'https://example.com/page', 1)")
            }
            db.version = 1
        }
        assertNull(store.list("history").single().favicon)
        val icon = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.MAGENTA)
        }
        try { store.saveIcon("https://example.com/another", icon) } finally { icon.recycle() }
        store.close()
        store = BrowserStore(context)
        assertEquals(android.graphics.Color.MAGENTA, store.list("history").single().favicon!!.getPixel(0, 0))
        store.clearHistory()
        assertEquals(android.graphics.Color.MAGENTA, store.list("bookmarks").single().favicon!!.getPixel(0, 0))
    }
}
