package com.xbatch.download

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Optional read-only check against the specified public account. */
@Suppress("DEPRECATION")
class LiveMirrorChecks : InstrumentationTestCase() {
    fun testPublicMirrorFeeds() {
        val raw = (instrumentation as android.test.InstrumentationTestRunner).arguments.getString("mirrorUser") ?: return
        val name = PublicPageParser.username(raw)
        val transfer = Transfer(instrumentation.targetContext) { false }
        val results = JSONArray()
        var valid = 0
        for (host in PublicRss.HOSTS) for (tab in listOf("", "media", "search")) {
            val url = PublicRss.url(name, host = host, tab = tab)
            val row = JSONObject().put("host", host).put("tab", tab)
            try {
                val document = transfer.publicDocument(url, name)
                val parsed = PublicRss.parse(document.text, name)
                row.put("entries", parsed.entries).put("own_posts", parsed.posts.size).put("cursor", document.cursor != null)
                valid++
            } catch (e: Exception) { row.put("error", e.message ?: "failed") }
            results.put(row)
            Thread.sleep(300)
        }
        File(instrumentation.targetContext.filesDir, "mirror-check.json").writeText(results.toString(2))
        assertTrue("No public RSS mirror was available", valid > 0)
    }
}
