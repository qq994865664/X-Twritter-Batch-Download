package com.xbatch.download

import android.test.InstrumentationTestCase
import android.test.InstrumentationTestRunner

/** Opt in with -e liveUpdate true; requires GitHub API access on the test device. */
@Suppress("DEPRECATION")
class LiveUpdateChecks : InstrumentationTestCase() {
    fun testLatestReleaseOnGitHub() {
        val arguments = (instrumentation as InstrumentationTestRunner).arguments
        if (arguments.getString("liveUpdate") != "true") return

        val result = UpdateChecker.check("0.0.0")
        assertTrue("Expected a newer published release with an APK; result: $result", result is UpdateChecker.Result.Available)
        val release = (result as UpdateChecker.Result.Available).release
        assertTrue(release.version.matches(Regex("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")))
        assertTrue(release.downloadUrl.startsWith("https://github.com/qq994865664/X-Twritter-Batch-Download/releases/download/"))
        assertTrue(release.downloadUrl.endsWith(".apk"))
        assertTrue("The published version should be up to date", UpdateChecker.check(release.version) is UpdateChecker.Result.Current)
    }
}
