package com.xbatch.download

import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks only this project's public GitHub release; never executes or downloads an APK. */
object UpdateChecker {
    private const val OWNER = "qq994865664"
    private const val REPOSITORY = "X-Twritter-Batch-Download"
    private const val ENDPOINT = "https://api.github.com/repos/$OWNER/$REPOSITORY/releases/latest"
    private val VERSION = Regex("^v?(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})$")

    data class Release(val version: String, val downloadUrl: String)
    sealed class Result {
        data class Available(val release: Release) : Result()
        data object Current : Result()
        data class Failure(val reason: String) : Result()
    }

    private fun parts(version: String): List<Int>? {
        val match = VERSION.matchEntire(version) ?: return null
        return match.groupValues.drop(1).map { it.toInt() }
    }

    private fun newer(remote: List<Int>, current: List<Int>): Boolean {
        for (index in 0..2) {
            if (remote[index] != current[index]) return remote[index] > current[index]
        }
        return false
    }

    /** Parse a response without trusting release text or links from an arbitrary host. */
    fun parse(json: String, currentVersion: String): Result {
        return try {
            val current = parts(currentVersion) ?: return Result.Failure("无法识别当前版本")
            val root = JSONObject(json)
            if (root.optBoolean("draft") || root.optBoolean("prerelease")) return Result.Failure("暂无可用的正式版本")
            val tag = root.optString("tag_name")
            val remote = parts(tag) ?: return Result.Failure("无法识别发布版本")
            val version = remote.joinToString(".")
            val name = "XBatchDownload-$version-release.apk"
            val expectedPath = "/$OWNER/$REPOSITORY/releases/download/$tag/$name"
            val assets = root.optJSONArray("assets") ?: return Result.Failure("发布版本没有可用的安装包")
            var validUrl: String? = null
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                if (item.optString("name") != name || item.optString("state") != "uploaded") continue
                val raw = item.optString("browser_download_url")
                val uri = Uri.parse(raw)
                if (uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null && uri.port == -1 &&
                    uri.encodedPath == expectedPath && uri.encodedQuery == null && uri.encodedFragment == null) {
                    validUrl = raw
                    break
                }
            }
            if (validUrl == null) return Result.Failure("发布版本没有可信的安装包")
            if (newer(remote, current)) Result.Available(Release(version, validUrl)) else Result.Current
        } catch (_: Exception) {
            Result.Failure("无法读取更新信息，请稍后重试")
        }
    }

    /** Call from a background thread; result contains no exception or server response text. */
    fun check(currentVersion: String): Result {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(ENDPOINT).openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "XBatchDownload-Android")
            val code = connection.responseCode
            if (code == 403 || code == 429) return Result.Failure("更新检查暂时受限，请稍后重试")
            if (code !in 200..299) return Result.Failure("暂时无法获取更新，请稍后重试")
            val body = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                while (true) {
                    val count = reader.read(buffer)
                    if (count < 0) break
                    body.append(buffer, 0, count)
                    if (body.length > 1024 * 1024) return Result.Failure("更新信息过大，请稍后重试")
                }
            }
            parse(body.toString(), currentVersion)
        } catch (_: Exception) {
            Result.Failure("检查更新失败，请检查网络后重试")
        } finally {
            connection?.disconnect()
        }
    }
}
