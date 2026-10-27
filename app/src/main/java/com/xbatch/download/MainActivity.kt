package com.xbatch.download

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.*
import java.lang.ref.WeakReference

/** One process-wide check prevents duplicate requests and survives activity recreation. */
private object UpdateCheckCoordinator {
    private val main = Handler(Looper.getMainLooper())
    private const val AUTO_COOLDOWN_MS = 60_000L
    private var activity = WeakReference<MainActivity>(null)
    private var inFlight = false
    private var manualWaiting = false
    private var lastAutoCheck = Long.MIN_VALUE
    private var pending: Pair<UpdateChecker.Result, Boolean>? = null
    private var autoNotifiedVersion: String? = null

    fun attach(current: MainActivity) {
        activity = WeakReference(current)
        val result = pending
        pending = null
        if (result != null) deliver(current, result.first, result.second)
    }

    fun detach(current: MainActivity) {
        if (activity.get() === current) activity.clear()
    }

    fun request(currentVersion: String, manual: Boolean) {
        if (inFlight) {
            if (manual) manualWaiting = true
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!manual && lastAutoCheck != Long.MIN_VALUE && now - lastAutoCheck < AUTO_COOLDOWN_MS) return
        if (!manual) lastAutoCheck = now
        inFlight = true
        manualWaiting = manual
        Thread {
            val result = try { UpdateChecker.check(currentVersion) }
                catch (_: Exception) { UpdateChecker.Result.Failure("无法检查更新") }
            main.post {
                inFlight = false
                val wasManual = manualWaiting
                manualWaiting = false
                val current = activity.get()
                if (current == null) {
                    if (wasManual || result is UpdateChecker.Result.Available) pending = result to wasManual
                } else deliver(current, result, wasManual)
            }
        }.start()
    }

    fun requeue(release: UpdateChecker.Release) {
        if (autoNotifiedVersion == release.version) autoNotifiedVersion = null
        pending = UpdateChecker.Result.Available(release) to false
    }

    private fun deliver(current: MainActivity, result: UpdateChecker.Result, manual: Boolean) {
        if (result is UpdateChecker.Result.Available) {
            if (!manual && autoNotifiedVersion == result.release.version) return
            if (current.presentUpdateResult(result, manual)) autoNotifiedVersion = result.release.version
            else pending = result to manual
        } else if (manual) {
            if (!current.presentUpdateResult(result, true)) pending = result to true
        }
    }
}

class MainActivity : Activity() {
    private var folderUser: String? = null
    private var detailsExpanded = false
    private var foreground = false
    private var updateDialog: AlertDialog? = null
    private var displayedUpdate: UpdateChecker.Release? = null
    private lateinit var input: EditText
    private lateinit var state: TextView
    private lateinit var badge: TextView
    private lateinit var foundCount: TextView
    private lateinit var savedCount: TextView
    private lateinit var failedCount: TextView
    private lateinit var path: TextView
    private lateinit var start: Button
    private lateinit var paste: Button
    private lateinit var resume: Button
    private lateinit var retry: Button
    private lateinit var pause: Button
    private lateinit var actionRow: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var scrollMore: LinearLayout
    private lateinit var details: TextView
    private lateinit var detailsToggle: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 800) } }

    private val ink = Color.rgb(19, 42, 58)
    private val muted = Color.rgb(96, 117, 128)
    private val teal = Color.rgb(17, 125, 136)
    private val paleTeal = Color.rgb(230, 244, 242)
    private val background = Color.rgb(247, 248, 246)
    private val border = Color.rgb(229, 238, 236)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = true
    }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun rounded(color: Int, radius: Int, outline: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (outline != null) setStroke(dp(1), outline)
    }
    private fun layout(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun card() = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(Color.WHITE, 20, border)
    }
    private fun button(label: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 15f; minWidth = 0; minimumWidth = 0; minHeight = dp(48)
        setPadding(dp(14), dp(10), dp(14), dp(10)); elevation = 0f
        val normal = if (primary) teal else paleTeal
        val disabled = Color.rgb(239, 243, 242)
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(disabled, 13))
            addState(intArrayOf(), rounded(normal, 13))
        }
        background = RippleDrawable(ColorStateList.valueOf(if (primary) 0x33FFFFFF else 0x22117D88), states, null)
        backgroundTintList = null
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(Color.rgb(160, 174, 174), if (primary) Color.WHITE else teal)))
        setTypeface(null, Typeface.BOLD)
        setOnClickListener { action() }
    }
    private fun menuRow(title: String, subtitle: String, action: () -> Unit): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(68)
        setPadding(dp(4), dp(10), dp(4), dp(10)); isClickable = true; isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x22117D88), null, rounded(Color.WHITE, 10))
        val labels = column()
        labels.addView(text(title, 15f).apply { setTypeface(null, Typeface.BOLD) })
        labels.addView(text(subtitle, 12f, muted).apply { setPadding(0, dp(3), 0, 0) })
        addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("›", 25f, Color.rgb(145, 163, 163)).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), -2))
        setOnClickListener { action() }
    }
    private fun separator() = View(this).apply { setBackgroundColor(Color.rgb(239, 244, 242)) }
    private fun metric(value: TextView, label: String): LinearLayout = column().apply {
        gravity = Gravity.CENTER
        addView(value.apply { gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(-1, -2))
        addView(text(label, 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(3), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun setTextIfChanged(view: TextView, value: String) {
        if (view.text.toString() != value) view.text = value
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderUser = savedInstanceState?.getString("folderUser")
        detailsExpanded = savedInstanceState?.getBoolean("detailsExpanded") ?: false
        getSharedPreferences("settings", MODE_PRIVATE).edit().remove("server").remove("key").apply()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.statusBarColor = background
        @Suppress("DEPRECATION")
        window.navigationBarColor = background
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val root = column().apply { setPadding(dp(20), dp(16), dp(20), dp(32)); setBackgroundColor(this@MainActivity.background) }
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = true; addView(root) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, keyboard.bottom))
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("↓", 25f, Color.WHITE).apply {
            gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD); background = rounded(teal, 13)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val headerText = column().apply { setPadding(dp(12), 0, 0, 0) }
        headerText.addView(text("图片批量下载", 18f).apply { setTypeface(null, Typeface.BOLD) })
        headerText.addView(text("X 公开原创图片", 12f, muted).apply { setPadding(0, dp(2), 0, 0) })
        header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        root.addView(text("喜欢的图片，一次保存", 24f).apply { setTypeface(null, Typeface.BOLD) }, layout(top = 24))
        root.addView(text("输入用户名，自动查找历史原创图片并保存原图。", 14f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }, layout(top = 7, bottom = 16))

        val inputCard = card()
        inputCard.addView(text("X 用户名", 15f).apply { setTypeface(null, Typeface.BOLD) })
        inputCard.addView(text("输入 @ 后面的用户名，或粘贴用户主页链接", 12f, muted), layout(top = 4, bottom = 10))
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rounded(Color.rgb(248, 251, 250), 13, border)
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        input = EditText(this).apply {
            hint = "用户名或主页链接"; setSingleLine(true); textSize = 15f; setTextColor(ink); setHintTextColor(Color.rgb(149, 166, 168))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO; background = null; minHeight = dp(48)
            setPadding(dp(12), dp(10), dp(4), dp(10))
            setText(getSharedPreferences("settings", MODE_PRIVATE).getString("input", ""))
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { launch(false, false); true } else false }
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        paste = button("粘贴") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) input.setText(clip.getItemAt(0).coerceToText(this))
            else toast("剪贴板没有可粘贴的内容")
        }.apply { textSize = 14f }
        inputRow.addView(paste, LinearLayout.LayoutParams(-2, -2))
        inputCard.addView(inputRow)
        start = button("一键批量下载", primary = true) { launch(false, false) }
        inputCard.addView(start, layout(top = 12))
        inputCard.addView(text("无需登录 · 只保存本人非回复帖图片", 12f, muted).apply { gravity = Gravity.CENTER }, layout(top = 10))
        root.addView(inputCard)

        val statusCard = card()
        val statusHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusHeader.addView(text("下载进度", 16f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        badge = text("未开始", 12f, teal).apply { setPadding(dp(10), dp(5), dp(10), dp(5)); background = rounded(paleTeal, 20) }
        statusHeader.addView(badge)
        statusCard.addView(statusHeader)
        state = text("输入用户名后开始查找", 13f, muted).apply { setLineSpacing(dp(3).toFloat(), 1f) }
        statusCard.addView(state, layout(top = 10))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true; indeterminateTintList = ColorStateList.valueOf(teal); visibility = View.GONE
        }
        statusCard.addView(progress, LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(12) })
        val metrics = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        foundCount = text("0", 22f); savedCount = text("0", 22f); failedCount = text("0", 22f)
        metrics.addView(metric(foundCount, "已发现"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric(savedCount, "已保存"), LinearLayout.LayoutParams(0, -2, 1f))
        metrics.addView(metric(failedCount, "下载失败"), LinearLayout.LayoutParams(0, -2, 1f))
        statusCard.addView(metrics, layout(top = 18, bottom = 16))
        statusCard.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        path = text("保存到 Download/用户名/", 12f, muted).apply { setPadding(0, dp(12), 0, 0); setTextIsSelectable(true) }
        statusCard.addView(path)
        actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        pause = button("暂停下载") { DownloadService.paused.set(true) }
        resume = button("继续下载") { launch(true, false) }
        retry = button("重试失败") { launch(true, true) }
        listOf(pause, resume, retry).forEachIndexed { index, item ->
            actionRow.addView(item, LinearLayout.LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = dp(8) })
        }
        statusCard.addView(actionRow, layout(top = 12))
        detailsToggle = text("查看最近的查找记录  ›", 13f, teal).apply {
            minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
            setOnClickListener { detailsExpanded = !detailsExpanded; refresh() }
        }
        statusCard.addView(detailsToggle)
        details = text("", 12f, muted).apply { setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1f) }
        statusCard.addView(details)
        root.addView(statusCard, layout(top = 14))

        val toolsCard = card()
        toolsCard.addView(text("常用功能", 16f).apply { setTypeface(null, Typeface.BOLD) }, layout(bottom = 5))
        val openDownloads = menuRow("查看下载", "打开系统下载列表") {
            runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }.onFailure { toast("请在文件管理器中打开 Download 文件夹") }
        }
        toolsCard.addView(openDownloads)
        toolsCard.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        toolsCard.addView(menuRow("关联已有文件夹", "重装或换设备后，关联原目录避免重复") { chooseExistingFolder() })
        toolsCard.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        scrollMore = menuRow("自动下拉查找更多", "本次查找结束后，可尝试加载更多帖子") { startActivity(Intent(this, ScrollScanActivity::class.java)) }
        toolsCard.addView(scrollMore)
        toolsCard.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        toolsCard.addView(menuRow("使用说明", "了解保存方式和查找范围") { showHelp() })
        toolsCard.addView(separator(), LinearLayout.LayoutParams(-1, dp(1)))
        toolsCard.addView(menuRow("检查更新", "查看是否有新版本") { checkForUpdates(manual = true) })
        root.addView(toolsCard, layout(top = 14))
        root.addView(text("公开页面提供的内容可能不完整。旧版已保存的图片会保留。", 12f, muted).apply { setLineSpacing(dp(4).toFloat(), 1f) }, layout(top = 16))
    }

    private fun chooseExistingFolder() {
        if (DownloadService.active.get()) { toast("请先暂停或等待当前下载结束"); return }
        val username = try { PublicPageParser.username(input.text.toString()) }
            catch (e: IllegalArgumentException) { input.error = e.message; return }
        folderUser = username
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        val suggested = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$username")
        picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, suggested)
        runCatching { @Suppress("DEPRECATION") startActivityForResult(picker, 20) }
            .onFailure { toast("无法打开文件夹选择器") }
    }
    private fun showHelp() {
        AlertDialog.Builder(this).setTitle("使用说明")
            .setMessage("输入 X 用户名后，应用会查找公开的历史帖子并保存原尺寸图片。只保存本人非回复帖的照片，不包含回复、转发、引用和视频封面。查找时会向公开镜像发送你输入的用户名，无需登录。\n\n公开页面和网络情况可能影响查找范围，无法保证获取全部历史。查找失败时可稍后重新开始，已保存的图片会尽量跳过。\n\n图片保存在 Download/用户名/。重装应用或更换设备后，可先关联已有文件夹，避免重复保存。旧版本已下载的图片会保留，可能包含回复图片。")
            .setPositiveButton("知道了", null).show()
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun installedVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun checkForUpdates(manual: Boolean) {
        val version = installedVersion()
        if (version.isBlank()) {
            if (manual) toast("无法读取当前版本，请稍后重试")
            return
        }
        if (manual) toast("正在检查更新…")
        UpdateCheckCoordinator.request(version, manual)
    }

    /** Return false if a lifecycle transition prevents presenting the result. */
    internal fun presentUpdateResult(result: UpdateChecker.Result, manual: Boolean): Boolean {
        if (!foreground || isFinishing || isDestroyed) return false
        when (result) {
            is UpdateChecker.Result.Available -> {
                if (updateDialog?.isShowing == true) return false
                displayedUpdate = result.release
                val dialog = AlertDialog.Builder(this).setTitle("发现新版本")
                    .setMessage("新版本 ${result.release.version} 已发布。是否打开浏览器下载？")
                    .setNegativeButton("稍后") { _, _ -> displayedUpdate = null }
                    .setPositiveButton("更新") { _, _ ->
                        displayedUpdate = null
                        runCatching { startActivity(browserIntent(result.release.downloadUrl)) }
                            .onFailure { toast("无法打开浏览器，请先安装或设置浏览器") }
                    }.create()
                dialog.setOnCancelListener { displayedUpdate = null }
                dialog.setOnDismissListener { updateDialog = null }
                updateDialog = dialog
                dialog.show()
            }
            UpdateChecker.Result.Current -> if (manual) toast("当前已是最新版本")
            is UpdateChecker.Result.Failure -> if (manual) toast("无法检查更新，请检查网络后重试")
        }
        return true
    }

    /** Let Android route the exact APK URL to the user's default browser. */
    internal fun browserIntent(downloadUrl: String): Intent =
        Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER).apply {
            data = Uri.parse(downloadUrl)
        }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folderUser", folderUser)
        outState.putBoolean("detailsExpanded", detailsExpanded)
        super.onSaveInstanceState(outState)
    }
    @Deprecated("Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 20 || resultCode != RESULT_OK) return
        val name = folderUser ?: return
        val uri = data?.data ?: return
        runCatching { ExistingFolder.save(this, name, uri) }
            .onSuccess { toast("已关联 Download/$name。点击一键下载或重试失败，即可复用已有图片") }
            .onFailure { toast(it.message ?: "关联文件夹失败，请选择 Download/$name") }
    }
    private fun launch(continueTask: Boolean, retryOnly: Boolean) {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (!continueTask && input.text.isNullOrBlank()) { input.error = "请输入用户名"; return }
        if (!continueTask) {
            try { PublicPageParser.username(input.text.toString()) }
            catch (e: IllegalArgumentException) { input.error = e.message; return }
        }
        prefs.edit().putString("input", input.text.toString().trim()).apply()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        runCatching {
            startForegroundService(Intent(this, DownloadService::class.java).putExtra("input", input.text.toString().trim()).putExtra("resume", continueTask).putExtra("retry", retryOnly))
        }.onFailure { toast(it.message ?: "无法启动下载") }
    }
    private fun refresh() {
        val running = DownloadService.active.get()
        val store = Store(this)
        val task = store.task()
        val totals = task?.let { store.counts(it.getString("user_id")) } ?: emptyMap()
        val failed = totals["failed"] ?: 0
        val publicTask = task?.optString("source") == "public_html"
        val scanDone = task?.optBoolean("scan_done") == true
        val error = task?.optString("error").orEmpty()
        val status = when {
            running -> DownloadService.message.takeIf { it.isNotBlank() } ?: "正在准备下载…"
            task == null -> "输入用户名后开始查找"
            error.isNotBlank() -> error
            scanDone && (totals["done"] ?: 0) == 0 -> "本次查找结束，暂未找到可保存的图片"
            scanDone -> "本次查找已结束，图片已保存到下载目录"
            else -> "任务已暂停，可继续下载"
        }
        val badgeText = when {
            running -> "下载中"
            task == null -> "未开始"
            error.isNotBlank() && !scanDone -> "已暂停"
            error.isNotBlank() || failed > 0 -> "有待处理"
            scanDone -> "已完成"
            else -> "已暂停"
        }
        setTextIfChanged(state, status)
        setTextIfChanged(badge, badgeText)
        setTextIfChanged(foundCount, totals.filterKeys { it != "excluded" }.values.sum().toString())
        setTextIfChanged(savedCount, (totals["done"] ?: 0).toString())
        setTextIfChanged(failedCount, failed.toString())
        setTextIfChanged(path, getString(R.string.download_path, task?.optString("username") ?: "用户名"))
        progress.visibility = if (running) View.VISIBLE else View.GONE
        start.isEnabled = !running; input.isEnabled = !running; paste.isEnabled = !running
        setTextIfChanged(start, if (running) "正在下载…" else "一键批量下载")
        val canResume = !running && publicTask && (!scanDone || (totals["pending"] ?: 0) > 0)
        val canRetry = !running && publicTask && failed > 0
        pause.visibility = if (running) View.VISIBLE else View.GONE
        resume.visibility = if (canResume) View.VISIBLE else View.GONE
        retry.visibility = if (canRetry) View.VISIBLE else View.GONE
        actionRow.visibility = if (running || canResume || canRetry) View.VISIBLE else View.GONE
        scrollMore.isEnabled = !running && publicTask && scanDone
        scrollMore.alpha = if (scrollMore.isEnabled) 1f else 0.45f
        val notes = task?.optJSONArray("source_notes")
        val hasNotes = notes != null && notes.length() > 0
        detailsToggle.visibility = if (hasNotes) View.VISIBLE else View.GONE
        setTextIfChanged(detailsToggle, if (detailsExpanded) "收起查找记录  ﹀" else "查看最近的查找记录  ›")
        details.visibility = if (hasNotes && detailsExpanded) View.VISIBLE else View.GONE
        if (hasNotes && detailsExpanded && notes != null) setTextIfChanged(details, (maxOf(0, notes.length() - 6) until notes.length()).joinToString("\n") { notes.optString(it) })
        store.close()
    }
    override fun onResume() {
        super.onResume()
        foreground = true
        handler.post(tick)
        UpdateCheckCoordinator.attach(this)
        checkForUpdates(manual = false)
    }
    override fun onPause() {
        foreground = false
        UpdateCheckCoordinator.detach(this)
        val shown = displayedUpdate
        if (shown != null && updateDialog?.isShowing == true) {
            displayedUpdate = null
            updateDialog?.dismiss()
            UpdateCheckCoordinator.requeue(shown)
        }
        handler.removeCallbacks(tick)
        super.onPause()
    }
}
