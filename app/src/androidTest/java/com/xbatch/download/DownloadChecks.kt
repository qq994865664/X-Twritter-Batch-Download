package com.xbatch.download

import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@Suppress("DEPRECATION")
class DownloadChecks : InstrumentationTestCase() {
    fun testPublicDownloadOriginalBytesAndDeduplication() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "storage-check.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val item = JSONObject().put("media_key", "3_functionalcheck").put("post_id", "123")
            .put("created_at", "2026-10-27T00:00:00Z").put("width", 32).put("height", 24).put("non_reply_verified", true)
        val transfer = Transfer(context) { false }
        val result = transfer.publish("xbatch_check", item, file)
        try {
            val bytes = context.contentResolver.openInputStream(Uri.parse(result))!!.use { it.readBytes() }
            assertTrue(file.readBytes().contentEquals(bytes))
            assertEquals(result, transfer.publish("xbatch_check", item, file))
            item.put("url", "https://pbs.twimg.com/media/not-requested?format=png&name=orig")
            assertEquals("An existing image must be reused before any network request", result, transfer.download("xbatch_check", item))
            context.contentResolver.query(Uri.parse(result), arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals("Download/xbatch_check/", it.getString(0))
                assertEquals(0, it.getInt(1))
            }
            item.put("width", 64)
            try { transfer.publish("xbatch_check", item, file); fail("A smaller image must not be saved") } catch (_: java.io.IOException) { }
        } finally { context.contentResolver.delete(Uri.parse(result), null, null); file.delete() }
    }

    fun testPagePersistsBeforeCursorAdvances() {
        val context = instrumentation.targetContext
        val store = Store(context)
        val original = store.task()
        val task = JSONObject().put("user_id", "test_42").put("username", "xbatch_check").put("cursor", "old")
        try {
            val item = JSONObject().put("media_key", "3_check")
            store.page(task, JSONArray().put(item).put(item), "next")
            assertEquals("next", store.task()!!.getString("cursor"))
            assertEquals(1, store.counts("test_42")["pending"])
            store.mark("test_42", "3_check", "done", "content://check")
            store.page(task, JSONArray().put(item), null)
            assertTrue(store.task()!!.getBoolean("scan_done"))
            assertNull(store.pending("test_42"))
        } finally {
            store.writableDatabase.delete("images", "user=?", arrayOf("test_42"))
            if (original != null) store.saveTask(original) else store.writableDatabase.delete("task", null, null)
            store.close()
        }
    }

    fun testExistingImageMatchesOldNumericAndLegacyMediaNames() {
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "id-check.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val item = JSONObject().put("post_id", "987654321").put("created_at", "2026-10-27T00:00:00Z")
            .put("media_key", "3_2029511190909747200").put("width", 32).put("height", 24)
            .put("non_reply_verified", true)
            .put("url", "https://pbs.twimg.com/media/HCpFrOHbAAAVuKe?format=png&name=orig")
        val transfer = Transfer(context) { false }
        val result = transfer.publish("xbatch_check", item, file)
        try {
            item.put("media_key", "photo_sparsehash")
            assertEquals(result, transfer.download("xbatch_check", item))
        } finally { context.contentResolver.delete(Uri.parse(result), null, null); file.delete() }
    }
}
