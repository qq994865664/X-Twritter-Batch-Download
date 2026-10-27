package com.xbatch.download

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

/** Optional synthetic data for visual checks in the isolated preview package. */
@Suppress("DEPRECATION")
class UiPreviewFixture : InstrumentationTestCase() {
    fun testSeedDemoData() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        if (args.getString("seedUiPreview") != "true") return
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".releasecheck"))
        val store = Store(context)
        try {
            val user = "public:nasa"
            val entries = JSONArray()
            for (i in 0 until 13) entries.put(JSONObject().put("media_key", "3_preview$i")
                .put("url", "https://pbs.twimg.com/media/preview$i"))
            store.addDiscovered(user, entries)
            for (i in 0 until 13) store.mark(user, "3_preview$i", if (i == 12) "failed" else "done", error = if (i == 12) "网络错误" else null)
            store.saveTask(JSONObject().put("source", "public_html").put("user_id", user).put("username", "NASA")
                .put("scan_done", true).put("status", "done").put("error", "有 1 张下载失败，可以重试。")
                .put("source_notes", JSONArray().put("公开主页：已完成查找").put("部分来源暂不可用，可稍后重试")))
        } finally { store.close() }
    }
}
