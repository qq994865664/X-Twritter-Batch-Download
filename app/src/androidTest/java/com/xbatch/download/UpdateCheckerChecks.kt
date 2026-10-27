package com.xbatch.download

import android.test.InstrumentationTestCase
import org.json.JSONArray
import org.json.JSONObject

@Suppress("DEPRECATION")
class UpdateCheckerChecks : InstrumentationTestCase() {
    private fun release(tag: String, url: String? = null, name: String? = null): JSONObject {
        val version = tag.removePrefix("v")
        val file = name ?: "XBatchDownload-$version-release.apk"
        val address = url ?: "https://github.com/qq994865664/X-Twritter-Batch-Download/releases/download/$tag/$file"
        val asset = JSONObject().put("name", file).put("state", "uploaded").put("browser_download_url", address)
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false).put("assets", JSONArray().put(asset))
    }
    private fun result(document: JSONObject, current: String = "0.7.0") = UpdateChecker.parse(document.toString(), current)

    fun testNumericVersionsAndValidAsset() {
        val available = result(release("v0.10.1"), "0.9.9")
        assertTrue(available is UpdateChecker.Result.Available)
        assertEquals("0.10.1", (available as UpdateChecker.Result.Available).release.version)
        assertTrue(available.release.downloadUrl.endsWith("/v0.10.1/XBatchDownload-0.10.1-release.apk"))
        assertTrue(result(release("v0.7.0")) is UpdateChecker.Result.Current)
        assertTrue(result(release("v0.6.99")) is UpdateChecker.Result.Current)
        assertTrue(result(release("1.0.0")) is UpdateChecker.Result.Available)
    }

    fun testUnsafeAssetsCannotTriggerAnUpdate() {
        val expected = "https://github.com/qq994865664/X-Twritter-Batch-Download/releases/download/v1.0.0/XBatchDownload-1.0.0-release.apk"
        val unsafe = listOf(
            expected.replace("https://", "http://"),
            expected.replace("github.com/", "github.com.evil.test/"),
            expected.replace("github.com/", "github.com@evil.test/"),
            expected.replace("github.com/", "github.com:443/"),
            expected.replace("/qq994865664/", "/other/"),
            expected.replace("/v1.0.0/", "/v9.0.0/"),
            expected.replace("XBatchDownload-1.0.0", "XBatchDownload-2.0.0"),
            expected + "?download=1", expected + "#fragment",
            expected.replace("/releases/download/", "/releases%2Fdownload/")
        )
        for (address in unsafe) assertTrue("Unsafe link accepted: $address", result(release("v1.0.0", address)) is UpdateChecker.Result.Failure)
        assertTrue(result(release("v1.0.0", name = "other.apk")) is UpdateChecker.Result.Failure)
        val notUploaded = release("v1.0.0")
        notUploaded.getJSONArray("assets").getJSONObject(0).put("state", "new")
        assertTrue(result(notUploaded) is UpdateChecker.Result.Failure)
        assertTrue(result(release("v1.0.0").put("assets", JSONArray())) is UpdateChecker.Result.Failure)
    }

    fun testDraftPrereleaseInvalidVersionsAndMalformedJson() {
        for (doc in listOf(release("v1.0.0").put("draft", true), release("v1.0.0").put("prerelease", true),
                release("v1.0.0-beta"), release("v01.0.0"), release("v9999999999.0.0"))) {
            assertTrue(result(doc) is UpdateChecker.Result.Failure)
        }
        assertTrue(UpdateChecker.parse("not json", "0.7.0") is UpdateChecker.Result.Failure)
        assertTrue(UpdateChecker.parse(release("v1.0.0").toString(), "debug") is UpdateChecker.Result.Failure)
    }
}
