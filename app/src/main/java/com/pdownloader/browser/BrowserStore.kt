package com.pdownloader.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class PageRecord(val id: Long, val title: String, val url: String, val visitedAt: Long)

/** All calls run on the activity's serial IO executor, never on the UI thread. */
class BrowserStore(context: Context) : SQLiteOpenHelper(context, "browser.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE bookmarks (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY, title TEXT NOT NULL, url TEXT NOT NULL UNIQUE, visited_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX history_recent ON history(visited_at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
        return readableDatabase.rawQuery("SELECT id,title,url,visited_at FROM $table ORDER BY visited_at DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(PageRecord(cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3)))
            }
        }
    }
    fun delete(table: String, id: Long) {
        require(table in setOf("bookmarks", "history"))
        writableDatabase.delete(table, "id=?", arrayOf(id.toString()))
    }
    fun clearHistory() { writableDatabase.delete("history", null, null) }
}
