package com.xbatch.download

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Explicit user-requested download run. Keeps downloaded files and task history. */
@Suppress("DEPRECATION")
class RequestedDownloadRun : InstrumentationTestCase() {
    /** Optional experiment: public RSS supplies IDs; X post metadata verifies authors and photos. */
    fun testPublicHistorySources() {
        val args = (instrumentation as InstrumentationTestRunner).arguments
        val input = args.getString("historyUser") ?: return
        val name = PublicPageParser.username(input)
        val user = "public:" + name.lowercase(java.util.Locale.ROOT)
        val context = instrumentation.targetContext
        assertFalse(DownloadService.active.get())
        val store = Store(context)
        val transfer = Transfer(context) { false }
        val before = store.counts(user)["done"] ?: 0
        val notes = JSONArray()
        try {
            val task = store.task() ?: error("Start this user's normal download first")
            assertEquals(user, task.getString("user_id"))
            store.reconcile(user, transfer::exists)
            store.retry(user)
            val ids = linkedSetOf<String>()
            val connection = java.net.URL("https://nitter.kareem.one/$name/rss").openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 25000
            connection.instanceFollowRedirects = false
            try {
                require(connection.responseCode == 200) { "RSS HTTP ${connection.responseCode}" }
                val parser = android.util.Xml.newPullParser()
                parser.setInput(connection.inputStream, "UTF-8")
                var event = parser.eventType
                while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                    if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "link") {
                        val link = parser.nextText()
                        Regex("https://nitter\\.kareem\\.one/${Regex.escape(name)}/status/([0-9]+)(?:#.*)?", RegexOption.IGNORE_CASE)
                            .matchEntire(link)?.let { ids.add(it.groupValues[1]) }
                    }
                    event = parser.next()
                }
            } finally { connection.disconnect() }
            args.getString("extraPostIds")?.split(',')?.filter { it.matches(Regex("[0-9]+")) }?.let { ids.addAll(it) }
            for (id in ids) {
                try {
                    val url = "https://x.com/$name/status/$id"
                    val page = PublicPageParser.parse(transfer.publicHtml(url, name), name, url)
                    val exact = JSONArray()
                    for (i in 0 until page.items.length()) {
                        val item = page.items.getJSONObject(i)
                        if (item.getString("post_id") == id) exact.put(item)
                    }
                    val added = store.addDiscovered(user, exact)
                    notes.put(JSONObject().put("post_id", id).put("photos", exact.length()).put("added", added))
                } catch (e: Exception) {
                    notes.put(JSONObject().put("post_id", id).put("error", e.message))
                }
                Thread.sleep(500)
            }
            while (true) {
                val item = store.pending(user) ?: break
                try { store.mark(user, item.getString("media_key"), "done", transfer.download(name, item)) }
                catch (e: Exception) { store.mark(user, item.getString("media_key"), "failed", error = e.message) }
            }
            val files = JSONArray()
            store.readableDatabase.rawQuery("SELECT body,state,uri FROM images WHERE user=?", arrayOf(user)).use {
                while (it.moveToNext()) {
                    val item = JSONObject(it.getString(0)).put("state", it.getString(1))
                    if (!it.isNull(2)) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        runCatching { context.contentResolver.openInputStream(Uri.parse(it.getString(2)))?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) } }
                        item.put("saved_width", bounds.outWidth).put("saved_height", bounds.outHeight)
                    }
                    files.put(item)
                }
            }
            val report = JSONObject().put("username", name).put("previous_saved", before)
                .put("counts", JSONObject(store.counts(user))).put("posts", notes).put("files", files)
            File(context.filesDir, "history-download-report.json").writeText(report.toString(2))
            val sourceNotes = task.optJSONArray("source_notes") ?: JSONArray()
            sourceNotes.put("公开 RSS / 镜像测试：候选 ${ids.size} 条，已保存 ${store.counts(user)["done"] ?: 0} 张；经过 X 原帖作者和图片元数据核对")
            task.put("source_notes", sourceNotes)
            store.saveTask(task)
            android.util.Log.i("XBatchHistory", report.toString())
        } finally { store.close() }
    }

    fun testDownloadAndScroll() {
        val input = (instrumentation as InstrumentationTestRunner).arguments.getString("username") ?: return
        val name = PublicPageParser.username(input)
        val user = "public:" + name.lowercase(java.util.Locale.ROOT)
        val context = instrumentation.targetContext
        val store = Store(context)
        assertFalse("Another download is running", DownloadService.active.get())
        val before = store.counts(user)["done"] ?: 0
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                context.getSharedPreferences("settings", 0).edit().putString("input", name).apply()
                context.startForegroundService(Intent(context, DownloadService::class.java).putExtra("input", name))
            }
            instrumentation.waitForIdleSync()
            Thread.sleep(1000)
            val deadline = System.currentTimeMillis() + 1800000
            while (System.currentTimeMillis() < deadline) {
                val task = store.task()
                if (!DownloadService.active.get() && task?.optString("user_id") == user && task.optString("status") != "running") break
                Thread.sleep(250)
            }
            assertFalse("Download still running; files retained", DownloadService.active.get())
            val task = store.task() ?: error("No task")
            assertEquals(user, task.getString("user_id"))
            if (task.optBoolean("scan_done") && (instrumentation as InstrumentationTestRunner).arguments.getString("skipScroll") != "true") {
                val previousNotes = task.optJSONArray("source_notes")?.length() ?: 0
                val scroll = instrumentation.startActivitySync(Intent(context, ScrollScanActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val scrollDeadline = System.currentTimeMillis() + 330000
                while (System.currentTimeMillis() < scrollDeadline) {
                    val notes = store.task()?.optJSONArray("source_notes")
                    if ((notes?.length() ?: 0) > previousNotes && !DownloadService.active.get()) break
                    Thread.sleep(250)
                }
                instrumentation.runOnMainSync { if (!scroll.isFinishing) scroll.finish() }
            }
            val files = JSONArray()
            store.readableDatabase.rawQuery("SELECT body,state,uri,error FROM images WHERE user=?", arrayOf(user)).use {
                while (it.moveToNext()) {
                    val image = JSONObject(it.getString(0))
                    image.put("state", it.getString(1)).put("error", it.getString(3))
                    if (!it.isNull(2)) {
                        val uri = Uri.parse(it.getString(2))
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        context.contentResolver.openInputStream(uri)?.use { stream -> BitmapFactory.decodeStream(stream, null, bounds) }
                        image.put("saved_width", bounds.outWidth).put("saved_height", bounds.outHeight).put("uri", uri.toString())
                    }
                    files.put(image)
                }
            }
            val report = JSONObject().put("username", name).put("previous_saved", before)
                .put("counts", JSONObject(store.counts(user))).put("task", store.task()).put("files", files)
            File(context.filesDir, "requested-download-report.json").writeText(report.toString(2))
            android.util.Log.i("XBatchRequested", report.toString())
        } finally {
            store.close()
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
