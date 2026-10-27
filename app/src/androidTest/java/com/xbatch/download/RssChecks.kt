package com.xbatch.download

import android.test.InstrumentationTestCase

@Suppress("DEPRECATION")
class RssChecks : InstrumentationTestCase() {
    private fun item(author: String, path: String, title: String = "Image") =
        "<item><title>$title</title><dc:creator>@$author</dc:creator><link>https://${PublicRss.HOST}/$path</link></item>"
    private fun rss(body: String) = "<rss xmlns:dc='http://purl.org/dc/elements/1.1/'><channel>$body</channel></rss>"

    fun testOriginalLinksOnlyAndDuplicateRemoval() {
        val own = item("Sample", "Sample/status/123#m")
        val page = PublicRss.parse(rss(own + own + item("Other", "Other/status/456") +
            item("Other", "Sample/status/789") + item("Sample", "Sample/status/111", "RT by @Sample: repost") +
            item("Sample", "Sample/status/222", "R to @Sample: reply")), "Sample")
        assertEquals(6, page.entries)
        assertEquals(listOf("https://x.com/Sample/status/123", "https://x.com/Sample/status/222"), page.posts)
    }

    fun testEmptyFeedAndInvalidDocuments() {
        assertEquals(0, PublicRss.parse(rss(""), "Sample").entries)
        for (xml in listOf("<html>Verify</html>", "<!DOCTYPE rss [<!ENTITY test SYSTEM 'file:///etc/passwd'>]><rss><channel/></rss>")) {
            var rejected = false
            try { PublicRss.parse(xml, "Sample") } catch (_: Exception) { rejected = true }
            assertTrue("Invalid XML must be rejected", rejected)
        }
    }

    fun testCursorEncodingAndHostRestriction() {
        assertEquals("nitter.mie.foundation", PublicRss.HOSTS.first())
        val source = PublicRss.url("Sample", "a+/=&?")
        assertTrue(PublicRss.allowed(source, "Sample"))
        assertEquals("a+/=&?", android.net.Uri.parse(source).getQueryParameter("cursor"))
        assertFalse(PublicRss.allowed(source, "Other"))
        assertFalse(PublicRss.allowed("https://${PublicRss.HOST}.evil.test/Sample/rss", "Sample"))
        assertFalse(PublicRss.allowed("https://${PublicRss.HOST}/Sample/rss?redirect=evil", "Sample"))
        for (host in PublicRss.HOSTS) for (tab in PublicRss.TABS) assertTrue(PublicRss.allowed(PublicRss.url("Sample", "a+/=&?", host, tab), "Sample"))
        assertFalse(PublicRss.allowed("https://${PublicRss.HOST}/search/rss?f=media&q=from:Other", "Sample"))
        val link = "<item><title>Image</title><link>http://nitter.meowing.monster/Sample/status/456#m</link></item>"
        assertEquals(listOf("https://x.com/Sample/status/456"), PublicRss.parse(rss(link), "Sample").posts)
    }
}
