package com.xbatch.download

import android.graphics.Bitmap
import android.graphics.Canvas
import android.test.InstrumentationTestCase
import java.io.File

/** Renders the installed launcher drawable for visual review of the isolated test build. */
@Suppress("DEPRECATION")
class IconPreviewFixture : InstrumentationTestCase() {
    fun testRenderIcon() {
        val args = (instrumentation as android.test.InstrumentationTestRunner).arguments
        if (args.getString("renderIcon") != "true") return
        val context = instrumentation.targetContext
        assertTrue(context.packageName.endsWith(".releasecheck"))
        val drawable = context.packageManager.getApplicationIcon(context.packageName)
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, bitmap.width, bitmap.height)
        drawable.draw(Canvas(bitmap))
        File(context.filesDir, "icon-preview.png").outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }
}
