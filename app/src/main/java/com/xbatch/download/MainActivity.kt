package com.xbatch.download

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.widget.*

class MainActivity : Activity() {
    private var folderUser: String? = null
    private lateinit var input: EditText
    private lateinit var state: TextView
    private lateinit var counts: TextView
    private lateinit var path: TextView
    private lateinit var start: Button
    private lateinit var resume: Button
    private lateinit var retry: Button
    private lateinit var pause: Button
    private lateinit var progress: ProgressBar
    private lateinit var scrollMore: Button
    private lateinit var details: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { refresh(); handler.postDelayed(this, 800) } }
    private val blue = Color.rgb(28, 100, 232)
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float, color: Int = Color.rgb(25, 37, 58)) = TextView(this).apply { text = value; textSize = size; setTextColor(color) }
    private fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        folderUser = savedInstanceState?.getString("folderUser")
        getSharedPreferences("settings", MODE_PRIVATE).edit().remove("server").remove("key").apply()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        val root = column().apply { setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(Color.rgb(245, 247, 251)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.addView(text("X / BATCH DOWNLOAD", 12f, blue).apply { letterSpacing = .12f })
        root.addView(text("把喜欢的图片\n一次保存。", 32f).apply { setTypeface(null, Typeface.BOLD); setPadding(0, dp(18), 0, dp(12)) })
        root.addView(text("输入用户名，自动翻页查找历史原创图片，\n找到多少，就下载多少。", 15f, Color.DKGRAY))
        val card = column().apply {
            setPadding(dp(18), dp(20), dp(18), dp(20))
            background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(20).toFloat() }
        }
        root.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(28) })
        card.addView(text("X 用户名", 14f).apply { setTypeface(null, Typeface.BOLD) })
        input = EditText(this).apply {
            hint = "@username 或 X 用户主页链接"
            setSingleLine(true); textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(getSharedPreferences("settings", MODE_PRIVATE).getString("input", ""))
        }
        card.addView(input)
        card.addView(button("粘贴用户名") {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) input.setText(clip.getItemAt(0).coerceToText(this))
        })
        start = button("一键批量下载") { launch(false, false) }.apply { setTextColor(Color.WHITE); backgroundTintList = android.content.res.ColorStateList.valueOf(blue) }
        card.addView(start, LinearLayout.LayoutParams(-1, dp(56)))
        card.addView(text("无需登录 · 无 API 费用 · 无需部署服务器\n仅下载本人非回复帖的图片", 12f, Color.GRAY).apply { setPadding(0, dp(10), 0, 0) })
        state = text("准备就绪", 16f).apply { setPadding(0, dp(24), 0, dp(10)) }
        root.addView(state)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress)
        counts = text("已发现 0 张   ·   已保存 0 张   ·   失败 0 张", 14f); root.addView(counts)
        path = text("保存到 Download/用户名/", 13f, Color.GRAY).apply { setPadding(0, dp(8), 0, dp(12)) }; root.addView(path)
        val actions = LinearLayout(this)
        pause = button("暂停") { DownloadService.paused.set(true) }
        resume = button("继续") { launch(true, false) }
        retry = button("重试失败") { launch(true, true) }
        listOf(pause, resume, retry).forEach { actions.addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        root.addView(actions)
        scrollMore = button("自动下拉查找更多") { startActivity(Intent(this, ScrollScanActivity::class.java)) }
        root.addView(scrollMore)
        root.addView(button("打开下载文件夹") {
            runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }.onFailure { toast("请在文件管理器中打开 Download 文件夹") }
        })
        root.addView(button("关联已有图片文件夹（防重复）") {
            if (DownloadService.active.get()) { toast("请先暂停或等待当前下载结束"); return@button }
            val username = try { PublicPageParser.username(input.text.toString()) }
                catch (e: IllegalArgumentException) { input.error = e.message; return@button }
            folderUser = username
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            val suggested = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/$username")
            picker.putExtra(DocumentsContract.EXTRA_INITIAL_URI, suggested)
            runCatching { @Suppress("DEPRECATION") startActivityForResult(picker, 20) }
                .onFailure { toast("无法打开文件夹选择器") }
        })
        root.addView(button("下载说明") {
            AlertDialog.Builder(this).setTitle("公开页面下载")
                .setMessage("自动读取 X 公开主页和多个公开 RSS 镜像，查找本人历史帖子，再逐条核对原帖作者和回复状态。仅保存本人非回复帖的照片，排除回复、转帖、引用和视频封面。\n\nX 或镜像可能仅提供部分历史、限流或要求登录。本 APP 不登录、不使用 API 凭证，不保证完整历史。无法核验的帖子不会下载，可稍后重新扫描。\n\n图片请求原尺寸，不压缩。手机网络需要能访问 x.com、pbs.twimg.com 和公开镜像。镜像会收到查询的用户名。\n\n旧版已经下载的图片会保留，其中可能包含回复图片；如需移除，请自行检查。")
                .setPositiveButton("知道了", null).show()
        })
        root.addView(text("公开页面可能只展示部分帖子；不保证获取全部历史图片。旧版已保存的回复图片会保留，统计可能包含这些旧文件。", 12f, Color.GRAY).apply { setPadding(0, dp(16), 0, 0) })
        details = text("", 12f, Color.GRAY).apply { setPadding(0, dp(10), 0, 0); setTextIsSelectable(true) }; root.addView(details)
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("folderUser", folderUser)
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
        counts.text = getString(R.string.download_counts, totals.filterKeys { it != "excluded" }.values.sum(), totals["done"] ?: 0, totals["failed"] ?: 0)
        path.text = getString(R.string.download_path, task?.optString("username") ?: "用户名")
        state.text = if (running) DownloadService.message else if (DownloadService.message.isNotBlank()) DownloadService.message else when {
            task == null -> "准备就绪"
            task.optString("error").isNotBlank() -> task.optString("error")
            task.optString("status") == "done" -> "本次公开扫描结束，仅覆盖公开可见范围"
            else -> "任务已保存，点击继续下载"
        }
        if (!running && task?.optString("error")?.isNotBlank() == true && state.text != task.optString("error")) state.append("\n" + task.optString("error"))
        progress.visibility = if (running) View.VISIBLE else View.GONE
        val notes = task?.optJSONArray("source_notes")
        details.text = if (notes == null) "" else (maxOf(0, notes.length() - 6) until notes.length()).joinToString("\n") { notes.optString(it) }
        start.isEnabled = !running; input.isEnabled = !running
        val publicTask = task?.optString("source") == "public_html"
        scrollMore.isEnabled = !running && publicTask && task.optBoolean("scan_done")
        pause.isEnabled = running; resume.isEnabled = !running && publicTask && (!task.optBoolean("scan_done") || (totals["pending"] ?: 0) > 0)
        retry.isEnabled = !running && publicTask && (totals["failed"] ?: 0) > 0
        store.close()
    }
    override fun onResume() { super.onResume(); handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
