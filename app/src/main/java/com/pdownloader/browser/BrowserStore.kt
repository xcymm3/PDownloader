package com.pdownloader.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.util.Locale

data class PageRecord(val id: Long, val title: String, val url: String, val visitedAt: Long, val favicon: Bitmap? = null)

/** All calls run on the activity's serial IO executor, never on the UI thread. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_recent ON history(visited_at DESC)")
        createIcons(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createIcons(db)
    }

    private fun createIcons(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE site_icons (site TEXT PRIMARY KEY, bitmap BLOB NOT NULL, updated_at INTEGER NOT NULL)")
    }

    fun saveIcon(url: String, bitmap: Bitmap) {
        val site = siteKey(url) ?: return
        if (bitmap.isRecycled) return
        val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
        val bytes = ByteArrayOutputStream().use { stream ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
            stream.toByteArray()
        }
        if (scaled !== bitmap) scaled.recycle()
        val values = ContentValues().apply {
            put("site", site)
            put("bitmap", bytes)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("site_icons", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        writableDatabase.execSQL("DELETE FROM site_icons WHERE site NOT IN (SELECT site FROM site_icons ORDER BY updated_at DESC, rowid DESC LIMIT 512)")
    }

    fun save(table: String, title: String, url: String) {
        require(table in setOf("bookmarks", "history"))
        val values = ContentValues().apply {
            put("title", title.ifBlank { url }); put("url", url); put("visited_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        if (table == "history") writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT 2000)"
        )
    }

    fun list(table: String): List<PageRecord> {
        require(table in setOf("bookmarks", "history"))
        val records = readableDatabase.rawQuery("SELECT id,title,url,visited_at FROM $table ORDER BY visited_at DESC, id DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PageRecord(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }
        val sites = records.mapNotNull { siteKey(it.url) }.toSet()
        if (sites.isEmpty()) return records
        val icons = mutableMapOf<String, Bitmap>()
        readableDatabase.rawQuery("SELECT site,bitmap FROM site_icons", null).use { cursor ->
            while (cursor.moveToNext()) {
                val site = cursor.getString(0)
                if (site in sites) {
                    val bytes = cursor.getBlob(1)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { icons[site] = it }
                }
            }
        }
        return records.map { record -> record.copy(favicon = icons[siteKey(record.url)]) }
    }

    internal fun suggest(query: String): List<AddressSuggestion> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val contains = "%$escaped%"
        val starts = "$escaped%"
        return readableDatabase.rawQuery(
            """
            SELECT title,url,bookmarked FROM (
                SELECT title,url,visited_at,1 AS bookmarked FROM bookmarks
                UNION ALL
                SELECT title,url,visited_at,0 AS bookmarked FROM history
            )
            WHERE title LIKE ? ESCAPE '\' OR url LIKE ? ESCAPE '\'
            ORDER BY (title LIKE ? ESCAPE '\' OR url LIKE ? ESCAPE '\') DESC, bookmarked DESC, visited_at DESC
            LIMIT 16
            """.trimIndent(), arrayOf(contains, contains, starts, starts)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(AddressSuggestion(
                    cursor.getString(0), cursor.getString(1), if (cursor.getInt(2) == 1) "收藏" else "历史"
                ))
            }.distinctBy { it.url }.take(MAX_ADDRESS_SUGGESTIONS)
        }
    }
    fun delete(table: String, id: Long) {
        require(table in setOf("bookmarks", "history"))
        writableDatabase.delete(table, "id=?", arrayOf(id.toString()))
        pruneIcons()
    }
    fun clearHistory() {
        writableDatabase.delete("history", null, null)
        pruneIcons()
    }

    private fun pruneIcons() {
        val db = writableDatabase
        val sites = db.rawQuery("SELECT url FROM history UNION SELECT url FROM bookmarks", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) siteKey(cursor.getString(0))?.let(::add) }
        }
        val unused = db.rawQuery("SELECT site FROM site_icons", null).use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0).takeIf { it !in sites }?.let(::add) }
        }
        if (unused.isNotEmpty()) db.delete("site_icons", "site IN (${unused.joinToString(",") { "?" }})", unused.toTypedArray())
    }

    private fun siteKey(url: String): String? {
        val uri = Uri.parse(url)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        return "$host:${uri.port}"
    }
}
