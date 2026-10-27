package com.xbatch.download

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.test.InstrumentationTestCase
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

/** Exercises the actual update dialog without launching an external browser. */
@Suppress("DEPRECATION")
class UpdateDialogChecks : InstrumentationTestCase() {
    private fun all(root: View): List<View> = listOf(root) + if (root is ViewGroup)
        (0 until root.childCount).flatMap { all(root.getChildAt(it)) } else emptyList()

    fun testUpdateDialogRoutesApkToBrowser() {
        val apk = "https://github.com/qq994865664/X-Twritter-Batch-Download/releases/download/v99.1.0/XBatchDownload-99.1.0-release.apk"
        val release = UpdateChecker.Release("99.1.0", apk)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        // The blocking filter matches the outer intent; its selector is checked separately.
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            addDataScheme("https")
        }
        val monitor = Instrumentation.ActivityMonitor(filter, Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true)
        try {
            instrumentation.waitForIdleSync()
            val intent = activity.browserIntent(apk)
            assertEquals(Uri.parse(apk), intent.data)
            assertEquals(Intent.ACTION_MAIN, intent.action)
            assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
            assertTrue("The blocking filter must match before clicking update",
                filter.match(instrumentation.targetContext.contentResolver, intent, true, "UpdateDialogChecks") >= 0)
            assertNotNull(intent.selector)
            assertEquals(Intent.ACTION_MAIN, intent.selector!!.action)
            assertTrue(intent.selector!!.hasCategory(Intent.CATEGORY_APP_BROWSER))
            instrumentation.addMonitor(monitor)
            instrumentation.runOnMainSync {
                assertTrue("Update should be presented in foreground", activity.presentUpdateResult(UpdateChecker.Result.Available(release), false))
                val field = MainActivity::class.java.getDeclaredField("updateDialog").apply { isAccessible = true }
                val dialog = field.get(activity) as AlertDialog
                assertTrue(dialog.isShowing)
                val labels = all(dialog.window!!.decorView).filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.any { it.contains("发现新版本") })
                assertTrue(labels.any { it.contains("99.1.0") })
                assertEquals("稍后", dialog.getButton(AlertDialog.BUTTON_NEGATIVE).text.toString())
                assertEquals("更新", dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
                assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick())
            }
            // AlertDialog dispatches the button listener through a Handler.
            instrumentation.waitForIdleSync()
            assertEquals("Clicking update should launch the browser intent", 1, monitor.hits)
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
