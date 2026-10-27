package com.xbatch.download

import android.content.Intent
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** Checks the actions and task states exposed by the home screen. */
@Suppress("DEPRECATION")
class MainUiChecks : InstrumentationTestCase() {
    private fun all(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else emptyList()

    fun testEmptyInputIsRejectedAndToolsRemainAvailable() {
        val context = instrumentation.targetContext
        assertFalse(DownloadService.active.get())
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val views = all(activity.findViewById(android.R.id.content))
                val input = views.filterIsInstance<EditText>().single()
                val start = views.filterIsInstance<Button>().single { it.text.toString() == "一键批量下载" }
                input.setText("")
                assertTrue(start.performClick())
                assertNotNull("Empty username should show an error", input.error)
                assertFalse(DownloadService.active.get())
                for (label in listOf("查看下载", "关联已有文件夹", "使用说明")) {
                    assertTrue("Missing tool: $label", views.filterIsInstance<TextView>().any { it.text.toString() == label })
                }
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    fun testPausedTaskExposesResumeAndRetry() {
        val context = instrumentation.targetContext
        val store = Store(context)
        val prior = store.task()
        val user = "ui_state_check"
        assertFalse(DownloadService.active.get())
        store.addDiscovered(user, JSONArray().put(JSONObject().put("media_key", "3_ui_check").put("url", "https://pbs.twimg.com/media/ui_check.jpg")))
        store.mark(user, "3_ui_check", "failed", error = "网络暂不可用")
        store.saveTask(JSONObject().put("source", "public_html").put("user_id", user).put("username", "Sample")
            .put("scan_done", false).put("status", "paused").put("error", "网络暂不可用").put("source_notes", JSONArray()))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val views = all(activity.findViewById(android.R.id.content))
                for (label in listOf("继续下载", "重试失败")) {
                    val button = views.filterIsInstance<Button>().single { it.text.toString() == label }
                    assertEquals("Action should be visible: $label", View.VISIBLE, button.visibility)
                    assertTrue(button.isEnabled)
                }
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "网络暂不可用" })
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            store.writableDatabase.delete("images", "user=?", arrayOf(user))
            if (prior == null) store.writableDatabase.delete("task", null, null) else store.saveTask(prior)
            store.close()
        }
    }
}
