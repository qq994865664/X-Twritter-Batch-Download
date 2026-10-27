package com.xbatch.download

import android.net.Uri
import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

@Suppress("DEPRECATION")
class TimelineFallbackChecks : InstrumentationTestCase() {
    fun testIndependentStreamsFallbackAndPostRetry() {
        val calls = mutableMapOf<String, Int>()
        fun feed(vararg ids: String): String = "<rss xmlns:dc='http://purl.org/dc/elements/1.1/'><channel>" +
            ids.joinToString("") { "<item><title>Image</title><dc:creator>@Sample</dc:creator><link>https://nitter.meowing.monster/Sample/status/$it</link></item>" } + "</channel></rss>"
        fun post(id: String): String {
            val user = JSONObject().put("core", JSONObject().put("screen_name", "Sample"))
            val tweet = JSONObject().put("__typename", "Tweet").put("rest_id", id)
                .put("core", JSONObject().put("user_results", JSONObject().put("result", user)))
                .put("media_entities2", JSONArray().put(JSONObject().put("type", "photo").put("id_str", id).put("media_url_https", "https://pbs.twimg.com/media/Example$id.jpg")))
            return "<script>" + JSONObject().put("tweet", tweet).toString() + "</script>"
        }
        val mock = object : TimelineTransport {
            override fun check() = Unit
            override fun publicDocument(source: String, username: String): Transfer.Document {
                calls[source] = (calls[source] ?: 0) + 1
                if (PublicPageParser.allowedPost(source, username)) {
                    if (source.endsWith("/101") && calls[source] == 1) throw IOException("temporary failure")
                    return Transfer.Document(post(Uri.parse(source).lastPathSegment!!), null)
                }
                if (!PublicRss.allowed(source, username)) throw IOException("not available")
                val uri = Uri.parse(source)
                if (uri.host == PublicRss.HOST) throw IOException("HTTP 503")
                val tab = if (uri.pathSegments.first() == "search") "search" else uri.pathSegments.getOrNull(1)?.takeIf { it != "rss" } ?: ""
                if (uri.host != "nitter.meowing.monster" || tab != "media") return Transfer.Document(feed(), null)
                return when (uri.getQueryParameter("cursor")) {
                    null -> Transfer.Document(feed("100"), "second")
                    "second" -> Transfer.Document(feed("100"), "third")
                    "third" -> Transfer.Document(feed("101"), "fourth")
                    else -> Transfer.Document(feed(), null)
                }
            }
        }
        val timeline = PublicTimeline(mock) { }
        val task = timeline.newTask("Sample")
        val initialQueue = JSONObject(task.getString("cursor")).getJSONArray("queue")
        for (index in 0 until PublicRss.TABS.size) assertEquals("nitter.mie.foundation", Uri.parse(initialQueue.getString(index)).host)
        val ids = mutableSetOf<String>()
        repeat(80) {
            if (task.optBoolean("scan_done")) return@repeat
            val result = timeline.page(task)
            val items = result.getJSONArray("items")
            for (i in 0 until items.length()) ids.add(items.getJSONObject(i).getString("post_id"))
            if (result.isNull("next_cursor")) task.put("scan_done", true).put("cursor", JSONObject.NULL)
            else task.put("cursor", result.getString("next_cursor"))
        }
        assertTrue(task.getBoolean("scan_done"))
        assertEquals(setOf("100", "101"), ids)
        assertEquals(2, calls["https://x.com/Sample/status/101"])
        assertTrue(calls.keys.any { it.startsWith("https://nitter.meowing.monster/Sample/media/rss") && Uri.parse(it).getQueryParameter("cursor") == "third" })
        assertTrue(task.getJSONArray("failed_sources").length() >= 1) // unavailable primary was reported
    }

    fun testOldReplyOnlyQueuesAreSkippedWithoutNetwork() {
        val mock = object : TimelineTransport {
            override fun check() = Unit
            override fun publicDocument(source: String, username: String): Transfer.Document = throw AssertionError("Reply feeds must not be requested")
        }
        val timeline = PublicTimeline(mock) { }
        val task = timeline.newTask("Sample")
        val queue = JSONArray().put(PublicRss.url("Sample", host = PublicRss.HOST, tab = "with_replies"))
            .put(PublicRss.url("Sample", host = PublicRss.HOST, tab = "search_tweets"))
        task.put("cursor", JSONObject().put("queue", queue).put("visited", JSONArray()).toString())
        val first = timeline.page(task)
        assertEquals(0, first.getJSONArray("items").length())
        task.put("cursor", first.getString("next_cursor"))
        val second = timeline.page(task)
        assertTrue(second.isNull("next_cursor"))
    }
}
