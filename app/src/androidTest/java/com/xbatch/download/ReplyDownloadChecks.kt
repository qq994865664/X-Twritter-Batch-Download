package com.xbatch.download

import android.graphics.Bitmap
import android.net.Uri
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

@Suppress("DEPRECATION")
class ReplyDownloadChecks : InstrumentationTestCase() {
    private fun tweet(id: String, rich: Boolean, reply: Boolean): JSONObject {
        val user = JSONObject().put("core", JSONObject().put("screen_name", "Sample"))
        return JSONObject().put("__typename", "Tweet").put("rest_id", id)
            .put("core", JSONObject().put("user_results", JSONObject().put("result", user)))
            .put("media_entities2", JSONArray().put(JSONObject().put("type", "photo").put("id_str", id)
                .put("media_url_https", "https://pbs.twimg.com/media/fixture$id.png")))
            .apply { if (rich) put("edit_control", JSONObject()); if (reply) put("reply_to_results", JSONObject().put("rest_id", "700")) }
    }
    private fun html(vararg tweets: JSONObject) = "<script>" + JSONObject().put("nodes", JSONArray(tweets.toList())).toString() + "</script>"
    private fun input(id: String) = JSONObject().put("post_id", id).put("media_key", "3_$id")
        .put("url", "https://pbs.twimg.com/media/fixture$id?format=png&name=orig")
        .put("created_at", "2026-10-27T00:00:00Z").put("width", 32).put("height", 24)

    fun testOldPendingAndDomReplyAreExcludedBeforeDownload() {
        val context = instrumentation.targetContext
        val transfer = object : Transfer(context, { false }) {
            override fun publicHtml(source: String, username: String) = html(tweet("800", false, false), tweet("800", true, true))
        }
        for (dom in listOf(false, true)) {
            var excluded = false
            try { transfer.download("Sample", input("800").put("needs_resolve", dom)) }
            catch (_: ReplyExcludedException) { excluded = true }
            assertTrue("Reply must be excluded before downloading", excluded)
        }
    }

    fun testSparseOnlyCannotCertifyOriginal() {
        val context = instrumentation.targetContext
        val transfer = object : Transfer(context, { false }) {
            override fun publicHtml(source: String, username: String) = html(tweet("801", false, false))
        }
        var rejected = false
        try { transfer.download("Sample", input("801")) }
        catch (e: IOException) { rejected = e !is ReplyExcludedException }
        assertTrue("Unknown reply state must not be downloaded", rejected)
    }

    fun testOldPendingOriginalIsReverifiedAndReusesFile() {
        val context = instrumentation.targetContext
        val transfer = object : Transfer(context, { false }) {
            override fun publicHtml(source: String, username: String) = html(tweet("802", true, false))
        }
        val file = File(context.cacheDir, "reply-check.png")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val prior = transfer.publish("Sample", input("802"), file)
        try { assertEquals(prior, transfer.download("Sample", input("802"))) }
        finally { context.contentResolver.delete(Uri.parse(prior), null, null); file.delete() }
    }
}
