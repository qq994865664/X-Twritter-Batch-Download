package com.xbatch.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
    private val backgroundColor = Color.rgb(247, 248, 246)
    private val inkColor = Color.rgb(19, 42, 58)
    private val secondaryColor = Color.rgb(96, 117, 128)
    private val accentColor = Color.rgb(17, 125, 136)
    private val borderColor = Color.rgb(229, 238, 236)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
    private fun rounded(color: Int, radius: Int, border: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (border != null) setStroke(dp(1), border)
    }
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
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val back = TextView(this).apply {
            text = "‹"; textSize = 34f; setTextColor(inkColor); gravity = android.view.Gravity.CENTER
            contentDescription = "返回并停止查找"
            setOnClickListener { complete("手动停止下拉") }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginStart = -dp(12) })
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(TextView(this).apply { text = "继续查找"; textSize = 20f; setTextColor(inkColor); setTypeface(null, Typeface.BOLD) })
        heading.addView(TextView(this).apply { text = "@$name"; textSize = 13f; setTextColor(secondaryColor); setPadding(0, dp(2), 0, 0) })
        header.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        layout.addView(header, LinearLayout.LayoutParams(-1, -2))

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.WHITE, 20, borderColor)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleSmall).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(accentColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        statusRow.addView(spinner, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
        label = TextView(this).apply { text = "正在打开公开主页…"; textSize = 15f; setTextColor(inkColor); setTypeface(null, Typeface.BOLD) }
        statusRow.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(statusRow)
        statusCard.addView(TextView(this).apply {
            text = "请保持页面打开，结束后会自动下载找到的图片。"
            textSize = 13f; setTextColor(secondaryColor); setPadding(0, dp(8), 0, 0)
        })
        layout.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        browser = WebView(this)
        val webCard = FrameLayout(this).apply {
            background = rounded(Color.WHITE, 20, borderColor)
            clipToOutline = true
            addView(browser, FrameLayout.LayoutParams(-1, -1))
        }
        layout.addView(webCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        val stop = Button(this).apply {
            text = "停止查找并下载"; textSize = 16f; setTextColor(Color.WHITE); isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            background = RippleDrawable(ColorStateList.valueOf(Color.rgb(75, 165, 172)), rounded(accentColor, 18), null)
            stateListAnimator = null
            setOnClickListener { complete("手动停止下拉") }
        }
        layout.addView(stop, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(12) })
        setContentView(layout)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.statusBarColor = backgroundColor
        @Suppress("DEPRECATION")
        window.navigationBarColor = backgroundColor
        layout.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(insets.systemWindowInsetLeft + dp(16), insets.systemWindowInsetTop + dp(8),
                insets.systemWindowInsetRight + dp(16), insets.systemWindowInsetBottom + dp(12))
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
                label.text = "已查看 ${seenPosts.size} 条帖子 · 新增 $added 张待核验"
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
