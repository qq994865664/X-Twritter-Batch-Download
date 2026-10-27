package com.xbatch.download

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class DownloadService : Service() {
    companion object {
        val active = AtomicBoolean(false)
        val paused = AtomicBoolean(false)
        @Volatile var message = ""
        const val CHANNEL = "downloads"
    }
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "图片下载", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val pause = PendingIntent.getService(this, 1, Intent(this, DownloadService::class.java).setAction("pause"), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setContentTitle("X 图片批量下载").setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "暂停", pause).build()).build()
    }
    private fun status(text: String) {
        message = text
        if (!paused.get()) getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "pause") { paused.set(true); message = "正在暂停…"; return START_NOT_STICKY }
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        paused.set(false)
        startForeground(1, notification("正在准备下载"))
        val raw = intent?.getStringExtra("input") ?: ""
        val resume = intent?.getBooleanExtra("resume", false) ?: false
        val retryOnly = intent?.getBooleanExtra("retry", false) ?: false
        worker = Thread { runDownload(raw, resume, retryOnly) }.also { it.start() }
        return START_NOT_STICKY
    }
    private fun runDownload(raw: String, resume: Boolean, retryOnly: Boolean) {
        val store = Store(this)
        val transfer = Transfer(this) { paused.get() }
        val publicTimeline = PublicTimeline(transfer)
        var task: JSONObject? = null
        val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xbatch:download")
        try {
            wake.acquire(30 * 60 * 1000L)
            task = if (resume || retryOnly) store.task() ?: error("没有可继续的任务") else {
                status("正在准备公开页面扫描…")
                publicTimeline.newTask(raw).also { store.saveTask(it) }
            }
            require(task.optString("source") == "public_html") { "旧版 API 任务无法继续，请输入用户名重新开始；已保存文件会保留" }
            val user = task.getString("user_id")
            val username = task.getString("username")
            task.put("status", "running").put("error", "")
            store.saveTask(task)
            store.retry(user)
            store.reconcile(user, transfer::exists)
            while (true) {
                transfer.check()
                if (!wake.isHeld) wake.acquire(30 * 60 * 1000L)
                val item = store.pending(user)
                if (item != null) {
                    status("@$username · 正在下载原尺寸图片")
                    try {
                        val uri = transfer.download(username, item)
                        store.mark(user, item.getString("media_key"), "done", uri)
                    } catch (e: Exception) {
                        if (paused.get()) throw e
                        if (e is ReplyExcludedException) store.mark(user, item.getString("media_key"), "excluded")
                        else store.mark(user, item.getString("media_key"), "failed", error = e.message ?: "下载失败")
                    }
                    continue
                }
                if (retryOnly || task.optBoolean("scan_done")) break
                status("@$username · 正在查找公开可见的图片…")
                val page = publicTimeline.page(task)
                store.page(task, page.getJSONArray("items"), if (page.isNull("next_cursor")) null else page.getString("next_cursor"))
            }
            val failed = store.counts(user)["failed"] ?: 0
            task.put("status", if (task.optBoolean("scan_done")) "done" else "paused")
            val sourcesFailed = task.optJSONArray("failed_sources")?.length() ?: 0
            val warnings = mutableListOf<String>()
            if (failed > 0) warnings.add("有 $failed 张下载失败，可点击重试。${store.firstError(user)}")
            if (sourcesFailed > 0) warnings.add("有 $sourcesFailed 个来源或帖子暂不可用，历史可能不完整；稍后重新点击一键下载可再查找。")
            task.put("error", warnings.joinToString("\n"))
            val done = store.counts(user)["done"] ?: 0
            status(if (task.optBoolean("scan_done")) "本次公开扫描结束，已保存 $done 张，失败 $failed 张。仅覆盖公开页面可见范围。" else "重试结束，可继续公开页面扫描")
        } catch (e: Exception) {
            val detail = if (paused.get()) "已暂停，可继续下载" else (e.message ?: "下载中断")
            task?.put("status", "paused")?.put("error", detail)
            message = detail
        } finally {
            task?.let { store.saveTask(it) }
            store.close()
            if (wake.isHeld) wake.release()
            active.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        paused.set(true)
        message = "系统后台时间已用完，请打开 APP 继续"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() { paused.set(true); super.onDestroy() }
}
