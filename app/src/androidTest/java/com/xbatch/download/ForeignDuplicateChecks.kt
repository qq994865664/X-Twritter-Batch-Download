package com.xbatch.download

import android.graphics.Bitmap
import android.net.Uri
import android.test.InstrumentationTestCase
import org.json.JSONObject
import java.io.File

/** Run create/cleanup in the probe app; run prevent in the production app. */
@Suppress("DEPRECATION")
class ForeignDuplicateChecks : InstrumentationTestCase() {
    private fun enabled() = (instrumentation as android.test.InstrumentationTestRunner).arguments.getString("foreignTest") == "true"
    private val username = "xdup_test_6419"
    private fun item() = JSONObject().put("media_key", "3_foreignfixture").put("post_id", "6419")
        .put("created_at", "2026-10-27T00:00:00Z").put("width", 32).put("height", 24).put("non_reply_verified", true)
    private fun image(): File = File(instrumentation.targetContext.cacheDir, "foreign-fixture.png").also { file ->
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    fun testCreateFixture() {
        if (!enabled()) return
        assertTrue(instrumentation.targetContext.packageName.endsWith(".probe"))
        val file = image()
        val uri = Transfer(instrumentation.targetContext) { false }.publish(username, item(), file)
        instrumentation.targetContext.getSharedPreferences("foreign-test", 0).edit().putString("uri", uri).commit()
        file.delete()
    }
    fun testPreventInvisibleDuplicate() {
        if (!enabled()) return
        assertFalse(instrumentation.targetContext.packageName.endsWith(".probe"))
        val file = image()
        try {
            var blocked = false
            try { Transfer(instrumentation.targetContext) { false }.publish(username, item(), file) }
            catch (e: java.io.IOException) { blocked = e.message.orEmpty().contains("关联") }
            assertTrue("An inaccessible duplicate must be blocked", blocked)
        } finally { file.delete() }
    }
    fun testReuseForeignWithLinkedFolder() {
        if (!enabled()) return
        val context = instrumentation.targetContext
        assertFalse(context.packageName.endsWith(".probe"))
        assertNotNull("First link the test folder using the system picker", ExistingFolder.tree(context, username))
        val value = item().put("url", "https://pbs.twimg.com/media/not-requested?format=png&name=orig")
        val result = Transfer(context) { false }.download(username, value)
        assertEquals("com.android.externalstorage.documents", Uri.parse(result).authority)
        assertTrue(context.contentResolver.openInputStream(Uri.parse(result))!!.use { it.readBytes().isNotEmpty() })
    }
    fun testCleanupFixture() {
        if (!enabled()) return
        assertTrue(instrumentation.targetContext.packageName.endsWith(".probe"))
        val context = instrumentation.targetContext
        val prefs = context.getSharedPreferences("foreign-test", 0)
        prefs.getString("uri", null)?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
        prefs.edit().remove("uri").commit()
    }
    fun testCleanupProducedCopy() {
        if (!enabled()) return
        assertFalse(instrumentation.targetContext.packageName.endsWith(".probe"))
        val resolver = instrumentation.targetContext.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(android.provider.MediaStore.Downloads._ID),
            "${android.provider.MediaStore.Downloads.RELATIVE_PATH}=? AND ${android.provider.MediaStore.Downloads.DISPLAY_NAME}=?",
            arrayOf("Download/$username/", "20261027_6419_3_foreignfixture (1).png"), null)?.use {
            while (it.moveToNext()) resolver.delete(android.content.ContentUris.withAppendedId(collection, it.getLong(0)), null, null)
        }
    }
}
