package com.xbatch.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.time.Instant

/** A visible, anonymous WebView; scrolling follows the website's normal loading behavior. */
class ScrollScanActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var browser: WebView
    private lateinit var label: TextView
    private lateinit var store: Store
    private lateinit var task: JSONObject
    private var finished = false
    private var started = false
    private var rounds = 0
    private var idle = 0
    private var added = 0
    private val seenPosts = mutableSetOf<String>()
    private var collector = ""
    private var startTime = 0L
    private var lastPending = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        val saved = store.task()
        if (saved == null || DownloadService.active.get() || saved.optString("source") != "public_html" || !saved.optBoolean("scan_done")) {
            Toast.makeText(this, "请先完成当前公开页面扫描", Toast.LENGTH_LONG).show(); finish(); return
        }
        task = saved
        if (Build.VERSION.SDK_INT >= 33) onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { complete("手动停止下拉") }
        val name = task.getString("username")
        collector = assets.open("scroll-collector.js").bufferedReader().use { it.readText() }.replace("__USERNAME__", JSONObject.quote(name))
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.WHITE) }
        label = TextView(this).apply { text = "正在打开 @$name 的公开主页…"; textSize = 16f; setTextColor(Color.BLACK); setPadding(24, 24, 24, 16) }
        layout.addView(label)
        layout.addView(TextView(this).apply { text = "自动向下滚动；请保持此页面打开。不会登录 X。"; setPadding(24, 0, 24, 16); setTextColor(Color.DKGRAY) })
        browser = WebView(this)
        layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        layout.addView(Button(this).apply { text = "停止下拉，下载已找到图片"; setOnClickListener { complete("手动停止下拉") } })
        setContentView(layout)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        browser.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            userAgentString = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                if (!PublicPageParser.allowedPage(request.url.toString(), name)) {
                    complete("页面要求登录或跳转，已停止下拉"); return true
                }
                return false
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (finished || started) return
                if (!PublicPageParser.allowedPage(url, name)) { complete("页面要求登录，无法继续匿名翻页"); return }
                started = true
                handler.postDelayed({ step() }, 2500)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) complete("公开网页加载失败，请检查网络后重试")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame && response.statusCode >= 400) complete("公开网页限制访问（HTTP ${response.statusCode}）")
            }
            override fun onReceivedSslError(view: WebView, sslHandler: SslErrorHandler, error: android.net.http.SslError) {
                sslHandler.cancel(); complete("网页安全连接失败，已停止下拉")
            }
        }
        startTime = System.currentTimeMillis()
        handler.postDelayed({ if (!started) complete("公开页面加载超时") }, 45000)
        // This app never offers sign-in; each session starts with an empty anonymous cookie jar.
        CookieManager.getInstance().removeAllCookies { if (!finished) browser.loadUrl("https://x.com/$name") }
    }

    private fun step() {
        if (finished) return
        if (rounds >= 100 || System.currentTimeMillis() - startTime > 300000) { complete("达到本次下拉时间/轮数上限，可再次查找"); return }
        browser.evaluateJavascript(collector) { raw ->
            if (finished) return@evaluateJavascript
            try {
                val result = JSONObject(JSONTokener(raw).nextValue() as String)
                if (!PublicPageParser.allowedPage(result.getString("url"), task.getString("username"))) { complete("页面要求登录，无法继续翻页"); return@evaluateJavascript }
                val posts = result.getJSONArray("posts")
                val before = seenPosts.size
                for (i in 0 until posts.length()) seenPosts.add(posts.getString(i))
                var newImages = 0
                // Prefer structured network data when present; it preserves authoritative media IDs.
                val responses = result.getJSONArray("responses")
                for (i in 0 until responses.length()) {
                    val body = responses.getString(i).replace("</", "<\\/")
                    val page = PublicPageParser.parse("<script>$body</script>", task.getString("username"), result.getString("url"))
                    newImages += store.addDiscovered(task.getString("user_id"), page.items)
                }
                val discovered = JSONArray()
                val candidates = result.getJSONArray("items")
                for (i in 0 until candidates.length()) {
                    val item = candidates.getJSONObject(i)
                    val url = PublicPageParser.originalUrl(item.getString("raw_url"))
                    val id = item.getString("post_id")
                    require(id.matches(Regex("[0-9]+")))
                    val key = "photo_" + Uri.parse(url).lastPathSegment.orEmpty().replace('-', '_')
                    require(key.matches(Regex("[A-Za-z0-9_]+")))
                    discovered.put(JSONObject().put("media_key", key).put("post_id", id).put("url", url)
                        .put("created_at", Instant.ofEpochMilli((id.toLong() shr 22) + 1288834974657L).toString()).put("width", 0).put("height", 0).put("needs_resolve", true))
                }
                newImages += store.addDiscovered(task.getString("user_id"), discovered)
                added += newImages
                lastPending = result.optInt("pending")
                idle = if (before == seenPosts.size && newImages == 0 && lastPending == 0) idle + 1 else 0
                rounds++
                label.text = "自动下拉第 $rounds 轮 · 已见 ${seenPosts.size} 条帖子 · 新增 $added 张图片"
                if (idle >= 6) complete("连续 6 轮未出现新帖子；已到当前匿名页面可加载范围")
                else handler.postDelayed({ step() }, 2500)
            } catch (_: Exception) { complete("无法识别动态页面内容，已保存找到的图片") }
        }
    }

    private fun complete(reason: String) {
        if (finished || !::task.isInitialized) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (::browser.isInitialized) browser.stopLoading()
        val notes = task.optJSONArray("source_notes") ?: JSONArray()
        notes.put("自动下拉：$rounds 轮，已见 ${seenPosts.size} 条帖子，新增 $added 张。$reason")
        task.put("source_notes", notes)
        store.saveTask(task)
        DownloadService.message = "$reason；新增 $added 张图片"
        android.util.Log.i("XBatchScroll", "rounds=$rounds posts=${seenPosts.size} added=$added pending=$lastPending reason=$reason")
        if ((store.counts(task.getString("user_id"))["pending"] ?: 0) > 0) {
            runCatching { startForegroundService(Intent(this, DownloadService::class.java).putExtra("resume", true).putExtra("retry", true)) }
                .onFailure { DownloadService.message = "新图片已保存到队列，返回首页点击继续下载" }
        }
        finish()
    }
    @Deprecated("Deprecated in Java")
    @SuppressLint("GestureBackNavigation") // API 33+ uses the registered OnBackInvokedCallback; this handles 29–32.
    override fun onBackPressed() { complete("手动停止下拉") }
    override fun onPause() {
        if (!finished && ::task.isInitialized) complete("离开下拉页面，已保留找到的图片")
        super.onPause()
    }
    override fun onDestroy() {
        finished = true
        handler.removeCallbacksAndMessages(null)
        if (::browser.isInitialized) { browser.stopLoading(); browser.destroy() }
        CookieManager.getInstance().removeAllCookies(null)
        if (::store.isInitialized) store.close()
        super.onDestroy()
    }
}
