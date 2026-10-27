package com.xbatch.download

import android.content.Intent
import android.test.InstrumentationTestCase
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Suppress("DEPRECATION")
class ScrollChecks : InstrumentationTestCase() {
    fun testDomFilterAndNewPostsAfterScrolling() {
        val context = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view: WebView
        val ready = CountDownLatch(1)
        val post = "<div><article><a href='/Sample/status/100'>post</a><a href='/Sample/status/100/photo/1'><img src='https://pbs.twimg.com/media/one.jpg'></a></article></div>"
        val quote = "<div><article><a href='/Sample/status/200'>post</a><a href='/Other/status/300/photo/1'><img src='https://pbs.twimg.com/media/quote.jpg'></a></article></div>"
        val html = "<html><body>$post$quote<div style='height:4000px'></div><script>window.addEventListener('scroll',function(){ if(window.added) return; window.added=true; document.body.insertAdjacentHTML('beforeend',${JSONObject.quote(post.replace("100", "101").replace("one.jpg", "two.jpg"))}); });</script></body></html>"
        try {
            instrumentation.runOnMainSync {
                view = WebView(activity)
                view.settings.javaScriptEnabled = true
                view.settings.blockNetworkLoads = true
                activity.setContentView(view)
                view.webViewClient = object : WebViewClient() { override fun onPageFinished(v: WebView, url: String) { ready.countDown() } }
                view.loadDataWithBaseURL("https://x.com/Sample", html, "text/html", "UTF-8", null)
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            val script = context.assets.open("scroll-collector.js").bufferedReader().use { it.readText() }.replace("__USERNAME__", JSONObject.quote("Sample"))
            fun collect(): JSONObject {
                val done = CountDownLatch(1)
                var output = ""
                instrumentation.runOnMainSync { view.evaluateJavascript(script) { output = it; done.countDown() } }
                assertTrue(done.await(10, TimeUnit.SECONDS))
                return JSONObject(JSONTokener(output).nextValue() as String)
            }
            val first = collect()
            assertEquals(1, first.getJSONArray("items").length())
            Thread.sleep(500)
            val second = collect()
            assertEquals(2, second.getJSONArray("items").length())
            assertTrue(second.getJSONArray("posts").toString().contains("101"))
        } finally { instrumentation.runOnMainSync { runCatching { view.destroy() }; activity.finish() } }
    }

    fun testSamePhotoFromDomAndStructuredDataDeduplicates() {
        val store = Store(instrumentation.targetContext)
        try {
            val a = JSONObject().put("media_key", "3_123").put("url", "https://pbs.twimg.com/media/abc?format=jpg&name=orig")
            val b = JSONObject(a.toString()).put("media_key", "photo_abc").put("url", "https://pbs.twimg.com/media/abc?format=webp&name=orig")
            assertEquals(1, store.addDiscovered("scroll_check", JSONArray().put(a)))
            assertEquals(0, store.addDiscovered("scroll_check", JSONArray().put(b)))
        } finally { store.writableDatabase.delete("images", "user=?", arrayOf("scroll_check")); store.close() }
    }
}
