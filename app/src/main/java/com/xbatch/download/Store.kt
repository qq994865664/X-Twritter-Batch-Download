package com.xbatch.download

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** A page and its next cursor commit together, so process death never skips a page. */
class Store(context: Context) : SQLiteOpenHelper(context, "downloads.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE task(id INTEGER PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE images(user TEXT NOT NULL, media TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'pending', uri TEXT, error TEXT, PRIMARY KEY(user,media))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    fun task(): JSONObject? = readableDatabase.rawQuery("SELECT body FROM task WHERE id=1", null).use {
        if (it.moveToFirst()) JSONObject(it.getString(0)) else null
    }
    fun saveTask(task: JSONObject) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO task VALUES(1,?)", arrayOf(task.toString()))
    }
    fun page(task: JSONObject, items: JSONArray, next: String?) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            addDiscovered(task.getString("user_id"), items)
            task.put("cursor", next ?: JSONObject.NULL).put("scan_done", next == null)
            saveTask(task)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    /** DOM discoveries may lack numeric media IDs; deduplicate against existing canonical URLs too. */
    fun addDiscovered(user: String, items: JSONArray): Int {
        val known = mutableSetOf<String>()
        readableDatabase.rawQuery("SELECT body FROM images WHERE user=?", arrayOf(user)).use {
            while (it.moveToNext()) known.add(PublicPageParser.photoIdentity(JSONObject(it.getString(0)).optString("url")))
        }
        var added = 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val identity = PublicPageParser.photoIdentity(item.optString("url"))
                if (identity.isNotBlank() && !known.add(identity)) continue
                val values = ContentValues().apply { put("user", user); put("media", item.getString("media_key")); put("body", item.toString()) }
                if (db.insertWithOnConflict("images", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L) added++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return added
    }
    fun pending(user: String): JSONObject? = readableDatabase.rawQuery(
        "SELECT body FROM images WHERE user=? AND state='pending' ORDER BY rowid LIMIT 1", arrayOf(user)
    ).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    fun mark(user: String, media: String, state: String, uri: String? = null, error: String? = null) {
        val values = ContentValues().apply { put("state", state); put("uri", uri); put("error", error) }
        writableDatabase.update("images", values, "user=? AND media=?", arrayOf(user, media))
    }
    fun retry(user: String) { writableDatabase.execSQL("UPDATE images SET state='pending', error=NULL WHERE user=? AND state='failed'", arrayOf(user)) }
    fun counts(user: String): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        readableDatabase.rawQuery("SELECT state,COUNT(*) FROM images WHERE user=? GROUP BY state", arrayOf(user)).use {
            while (it.moveToNext()) result[it.getString(0)] = it.getInt(1)
        }
        return result
    }
    fun firstError(user: String): String = readableDatabase.rawQuery(
        "SELECT error FROM images WHERE user=? AND state='failed' ORDER BY rowid LIMIT 1", arrayOf(user)
    ).use { if (it.moveToFirst()) it.getString(0) ?: "" else "" }
    fun reconcile(user: String, exists: (String) -> Boolean) {
        val missing = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT media,uri FROM images WHERE user=? AND state='done'", arrayOf(user)).use {
            while (it.moveToNext()) if (it.isNull(1) || !exists(it.getString(1))) missing.add(it.getString(0))
        }
        missing.forEach { mark(user, it, "pending") }
    }
}
