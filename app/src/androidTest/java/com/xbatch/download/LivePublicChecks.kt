package com.xbatch.download

import android.graphics.BitmapFactory
import android.net.Uri
import android.test.InstrumentationTestCase

/** Opt-in live smoke check: run this class explicitly; requires access to X and its image CDN. */
@Suppress("DEPRECATION")
class LivePublicChecks : InstrumentationTestCase() {
    fun testOneTapServiceWithoutSettings() {
        val context = instrumentation.targetContext
        val store = Store(context)
        val previous = store.task()
        assertTrue("Run this probe on an emulator without existing NASA downloads", store.counts("public:nasa").isEmpty())
        val activity = instrumentation.startActivitySync(android.content.Intent(context, MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                context.startForegroundService(android.content.Intent(context, DownloadService::class.java).putExtra("input", "NASA"))
            }
            val deadline = System.currentTimeMillis() + 90000
            var task: org.json.JSONObject? = null
            while (System.currentTimeMillis() < deadline) {
                task = store.task()
                if (task?.optString("user_id") == "public:nasa" && task.optString("status") == "done" && !DownloadService.active.get()) break
                Thread.sleep(100)
            }
            assertTrue("Scan did not finish: ${DownloadService.message}", task?.optBoolean("scan_done") == true)
            assertTrue("No images saved: $task", (store.counts("public:nasa")["done"] ?: 0) > 0)
            assertEquals(0, store.counts("public:nasa")["failed"] ?: 0)
            android.util.Log.i("XBatchProbe", "One-tap result=${store.counts("public:nasa")}; notes=${task?.optJSONArray("source_notes")}")
            val scroll = instrumentation.startActivitySync(android.content.Intent(context, ScrollScanActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            val scrollDeadline = System.currentTimeMillis() + 90000
            while (System.currentTimeMillis() < scrollDeadline) {
                if (store.task()?.optJSONArray("source_notes")?.toString()?.contains("自动下拉：") == true && !DownloadService.active.get()) break
                Thread.sleep(200)
            }
            val notes = store.task()?.optJSONArray("source_notes")?.toString().orEmpty()
            android.util.Log.i("XBatchProbe", "After scroll result=${store.counts("public:nasa")}; notes=$notes")
            store.readableDatabase.rawQuery("SELECT body,error FROM images WHERE user='public:nasa' AND state='failed'", null).use {
                while (it.moveToNext()) android.util.Log.i("XBatchProbe", "Scroll image failed: ${it.getString(0)} ; ${it.getString(1)}")
            }
            instrumentation.runOnMainSync { if (!scroll.isFinishing) scroll.finish() }
            assertTrue("Scroll session did not record its stop reason", notes.contains("自动下拉："))
            assertEquals("Scroll should not enqueue duplicate WebP thumbnails", 0, store.counts("public:nasa")["failed"] ?: 0)
        } finally {
            DownloadService.paused.set(true)
            val deadline = System.currentTimeMillis() + 30000
            while (DownloadService.active.get() && System.currentTimeMillis() < deadline) Thread.sleep(100)
            store.readableDatabase.rawQuery("SELECT uri FROM images WHERE user='public:nasa' AND uri IS NOT NULL", null).use {
                while (it.moveToNext()) context.contentResolver.delete(Uri.parse(it.getString(0)), null, null)
            }
            store.writableDatabase.delete("images", "user=?", arrayOf("public:nasa"))
            if (previous != null) store.saveTask(previous) else store.writableDatabase.delete("task", null, null)
            store.close()
            instrumentation.runOnMainSync { activity.finish() }
            DownloadService.message = ""
        }
    }

    fun testPublicProfileAndOriginalImage() {
        val context = instrumentation.targetContext
        val transfer = Transfer(context) { false }
        val html = transfer.publicHtml("https://x.com/NASA", "NASA")
        val page = PublicPageParser.parse(html, "NASA", "https://x.com/NASA")
        assertTrue("Public page did not expose photos on this network", page.items.length() > 0)
        val photo = page.items.getJSONObject(0)
        val saved = Uri.parse(transfer.download("xbatch_probe", photo))
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(saved)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
            assertTrue(bounds.outWidth >= photo.getInt("width"))
            assertTrue(bounds.outHeight >= photo.getInt("height"))
            android.util.Log.i("XBatchProbe", "Public posts=${page.posts}; photos=${page.items.length()}; saved=${bounds.outWidth}x${bounds.outHeight}")
        } finally { context.contentResolver.delete(saved, null, null) }
    }
}
