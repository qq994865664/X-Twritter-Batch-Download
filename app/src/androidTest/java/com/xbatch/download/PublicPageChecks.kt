package com.xbatch.download

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class PublicPageChecks : InstrumentationTestCase() {
    private fun post(id: String, user: String = "Sample", type: String = "photo") = JSONObject()
        .put("__typename", "Tweet").put("rest_id", id)
        .put("core", JSONObject().put("user_results", JSONObject().put("result", JSONObject().put("core", JSONObject().put("screen_name", user)))))
        .put("media_entities2", JSONArray().put(JSONObject().put("type", type).put("id_str", id)
            .put("media_url_https", "https://pbs.twimg.com/media/Example$id.jpg").put("original_info", JSONObject().put("width", 100).put("height", 80))))

    private fun html(vararg nodes: JSONObject) = "<script id=\"__NEXT_DATA__\">" +
        JSONObject().put("props", JSONObject().put("timeline", JSONArray(nodes.toList()))).toString() + "</script>"

    fun testUsernameAndDestinationValidation() {
        assertEquals("Sample", PublicPageParser.username("https://x.com/Sample?s=21"))
        assertEquals("Sample", PublicPageParser.username("@Sample"))
        for (bad in listOf("../../a", "https://evil.test/Sample", "https://x.com/Sample/status/123")) {
            try { PublicPageParser.username(bad); fail("Invalid input accepted") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(PublicPageParser.allowedPage("https://evil.test/Sample?cursor=x", "Sample"))
        assertFalse(PublicPageParser.allowedPage("https://x.com/i/flow/login", "Sample"))
    }

    fun testOriginalsOnlyAndNoNestedQuotedImages() {
        val original = post("100")
        val quote = post("101").put("quoted_status_result", JSONObject().put("result", post("102")))
        val retweet = post("103").put("retweeted_status_result", JSONObject().put("result", post("104")))
        val page = PublicPageParser.parse(html(original, quote, retweet, post("105", "Other"), post("106", type = "video"), original), "Sample", "https://x.com/Sample")
        assertEquals(1, page.items.length())
        assertEquals("100", page.items.getJSONObject(0).getString("post_id"))
        assertTrue(page.items.getJSONObject(0).getString("url").endsWith("name=orig"))
    }

    fun testExplicitPaginationDoesNotFollowOtherUsersOrLogin() {
        val links = "<a rel='next' href='?cursor=abc&amp;mode=old'>Next</a><a rel='next' href='https://evil.test/Sample'>x</a><a rel='next' href='/Other?cursor=q'>y</a>"
        val page = PublicPageParser.parse(html(post("100")) + links, "Sample", "https://x.com/Sample")
        assertEquals(listOf("https://x.com/Sample?cursor=abc&mode=old"), page.next)
    }

    fun testUnknownOrLoginPageNotTreatedAsEmptyHistory() {
        val page = PublicPageParser.parse("<html><h1>Log in to X</h1></html>", "Sample", "https://x.com/Sample")
        assertFalse(page.recognized)
        assertEquals(0, page.items.length())
    }

    fun testCapturedPublicPageStreamingJson() {
        val source = instrumentation.context.assets.open("nasa-public-page.html").bufferedReader().use { it.readText() }
        val page = PublicPageParser.parse(source, "NASA", "https://x.com/NASA")
        assertTrue(page.recognized)
        assertEquals(5, page.posts)
        assertEquals(3, page.items.length())
        assertEquals("2114801524358406570", page.items.getJSONObject(0).getString("post_id"))
        assertEquals(2048, page.items.getJSONObject(0).getInt("width"))
    }

    fun testLegacyEmbedAndRetweetContext() {
        val tweet = JSONObject().put("id_str", "100").put("user", JSONObject().put("screen_name", "Sample"))
            .put("extended_entities", JSONObject().put("media", JSONArray().put(JSONObject().put("id_str", "99").put("type", "photo").put("media_url_https", "https://pbs.twimg.com/media/a.png"))))
        val page = PublicPageParser.parse(html(JSONObject().put("tweet", tweet)), "Sample", "https://x.com/Sample")
        assertEquals(1, page.items.length())
        val blocked = PublicPageParser.parse(html(JSONObject().put("social_context", JSONObject().put("context_type", "Retweet")).put("tweet", tweet)), "Sample", "https://x.com/Sample")
        assertEquals(0, blocked.items.length())
    }

    fun testEmptyModernMediaDoesNotHideLegacyAndRichMetadataWins() {
        val full = post("200")
        val media = full.getJSONArray("media_entities2")
        val sparse = post("200")
        sparse.getJSONArray("media_entities2").getJSONObject(0).remove("id_str")
        sparse.getJSONArray("media_entities2").getJSONObject(0).remove("original_info")
        val emptyModern = post("201").put("media_entities2", JSONArray())
            .put("legacy", JSONObject().put("extended_entities", JSONObject().put("media", JSONArray().put(
                JSONObject().put("type", "photo").put("id_str", "201").put("media_url_https", "https://pbs.twimg.com/media/has-dash.jpg")))))
        val page = PublicPageParser.parse(html(full, sparse, emptyModern), "Sample", "https://x.com/Sample")
        assertEquals(2, page.items.length())
        assertEquals("3_200", page.items.getJSONObject(0).getString("media_key"))
        assertEquals(100, page.items.getJSONObject(0).getInt("width"))
        assertEquals("3_201", page.items.getJSONObject(1).getString("media_key"))
        val noId = post("202")
        noId.getJSONArray("media_entities2").getJSONObject(0).remove("id_str")
        noId.getJSONArray("media_entities2").getJSONObject(0).put("media_url_https", "https://pbs.twimg.com/media/has-dash.jpg")
        assertEquals(1, PublicPageParser.parse(html(noId), "Sample", "https://x.com/Sample").items.length())
    }

    fun testMultiplePhotosAndVideoThumbnailAreHandledSeparately() {
        val own = post("300")
        own.getJSONArray("media_entities2").put(JSONObject().put("type", "photo").put("id_str", "301")
            .put("media_url_https", "https://pbs.twimg.com/media/SecondPhoto.jpg"))
        val video = post("302", type = "video")
        video.put("legacy", JSONObject().put("entities", JSONObject().put("media", JSONArray().put(
            JSONObject().put("type", "photo").put("id_str", "302").put("media_url_https", "https://pbs.twimg.com/media/VideoThumbnail.jpg")))))
        val page = PublicPageParser.parse(html(own, video), "Sample", "https://x.com/Sample")
        assertEquals(2, page.items.length())
        assertTrue((0 until page.items.length()).all { page.items.getJSONObject(it).getString("post_id") == "300" })
    }

    fun testReplyAcrossRichAndSparseCopiesIsExcludedInEitherOrder() {
        val sparse = post("410")
        val rich = post("410").put("edit_control", JSONObject()).put("reply_to_results", JSONObject().put("rest_id", "400"))
        for (pageHtml in listOf(html(sparse, rich), html(rich, sparse))) {
            val page = PublicPageParser.parse(pageHtml, "Sample", "https://x.com/Sample/status/410")
            assertEquals(0, page.items.length())
            assertTrue("410" in page.postIds)
            assertTrue("410" in page.replyPostIds)
        }
    }

    fun testReplyFieldsAndExplicitOriginalVerification() {
        val a = post("501").put("legacy", JSONObject().put("in_reply_to_status_id_str", "499"))
        val b = post("502").put("legacy", JSONObject().put("conversation_id_str", "500"))
        val c = post("503").put("legacy", JSONObject().put("in_reply_to_status_id_str", JSONObject.NULL).put("conversation_id_str", "503"))
        val result = PublicPageParser.parse(html(a, b, c), "Sample", "https://x.com/Sample/status/503")
        assertEquals(setOf("501", "502"), result.replyPostIds)
        assertEquals(1, result.items.length())
        assertTrue(result.items.getJSONObject(0).getBoolean("non_reply_verified"))
        val sparse = PublicPageParser.parse(html(post("504")), "Sample", "https://x.com/Sample/status/504")
        assertFalse(sparse.items.getJSONObject(0).optBoolean("non_reply_verified"))
        val rich = PublicPageParser.parse(html(post("505").put("edit_control", JSONObject())), "Sample", "https://x.com/Sample/status/505")
        assertTrue(rich.items.getJSONObject(0).getBoolean("non_reply_verified"))
        val profile = PublicPageParser.parse(html(post("505").put("edit_control", JSONObject())), "Sample", "https://x.com/Sample")
        assertFalse(profile.items.getJSONObject(0).optBoolean("non_reply_verified"))
    }

    fun testSparseDuplicateCannotOverrideRepostOrQuote() {
        val sparse = post("601")
        val full = post("601").put("edit_control", JSONObject()).put("retweeted_status_result", JSONObject().put("id", "1"))
        assertEquals(0, PublicPageParser.parse(html(sparse, full), "Sample", "https://x.com/Sample/status/601").items.length())
    }

    fun testReplyWithSameMediaDoesNotHideAnOriginalPost() {
        val sparseReply = post("701")
        val richReply = post("701").put("edit_control", JSONObject()).put("reply_to_results", JSONObject().put("rest_id", "699"))
        val original = post("702")
        original.getJSONArray("media_entities2").getJSONObject(0).put("media_url_https", "https://pbs.twimg.com/media/Example701.jpg")
        val page = PublicPageParser.parse(html(sparseReply, original, richReply), "Sample", "https://x.com/Sample")
        assertEquals(1, page.items.length())
        assertEquals("702", page.items.getJSONObject(0).getString("post_id"))
    }
}
