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
}
