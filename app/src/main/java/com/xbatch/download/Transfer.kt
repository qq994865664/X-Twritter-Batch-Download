package com.xbatch.download

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL

interface TimelineTransport {
    fun check()
    fun publicDocument(source: String, username: String): Transfer.Document
}

class ReplyExcludedException : java.io.IOException("已排除回复帖图片")

open class Transfer(private val context: Context, private val cancelled: () -> Boolean) : TimelineTransport {
    override fun check() { if (cancelled()) throw InterruptedIOException("已暂停") }
    open fun publicHtml(source: String, username: String): String {
        return publicDocument(source, username).text
    }
    data class Document(val text: String, val cursor: String?)
    override fun publicDocument(source: String, username: String): Document {
        check()
        require(PublicPageParser.allowedPage(source, username) || PublicPageParser.allowedPost(source, username) || PublicRss.allowed(source, username))
        val connection = URL(source).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 25000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.setRequestProperty("Accept", "application/rss+xml,text/html,application/xhtml+xml")
        try {
            val code = connection.responseCode
            if (code in 300..399) throw java.io.IOException("公开页面重定向或要求登录，已跳过")
            if (code == 429) throw java.io.IOException("公开页面限制访问频率，请稍后再试")
            if (code == 404) throw java.io.IOException("公开页面不存在或不可见")
            if (code !in 200..299) throw java.io.IOException("公开页面不可用（HTTP $code）")
            val text = StringBuilder()
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    check()
                    val count = reader.read(buffer)
                    if (count < 0) break
                    text.append(buffer, 0, count)
                    if (text.length > 8 * 1024 * 1024) throw java.io.IOException("公开页面过大，已停止解析")
                }
            }
            check()
            return Document(text.toString(), connection.getHeaderField("Min-Id")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 4096 })
        } finally { connection.disconnect() }
    }

    fun exists(uri: String): Boolean = runCatching {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize != 0L } ?: false
    }.getOrDefault(false)

    private fun filename(item: JSONObject, extension: String): String {
        val media = item.getString("media_key")
        require(media.matches(Regex("[A-Za-z0-9_]+")))
        val post = item.getString("post_id")
        require(post.matches(Regex("[0-9]+")))
        val date = item.optString("created_at").take(10).replace("-", "").ifBlank { "image" }
        require(date == "image" || date.matches(Regex("[0-9]{8}")))
        return "${date}_${post}_${media}.$extension"
    }

    private fun existing(username: String, item: JSONObject): String? {
        val post = item.getString("post_id")
        val media = item.getString("media_key")
        require(post.matches(Regex("[0-9]+")) && media.matches(Regex("[A-Za-z0-9_]+")))
        val mediaPath = Uri.parse(item.optString("url")).lastPathSegment
        val legacyMedia = mediaPath?.let { "photo_$it" }
        // Older versions sometimes recorded a numeric media ID while later public metadata
        // only provides the media path. X's usual path encodes that ID in its first 8 bytes.
        // Use it solely to match an existing file in this same post and user directory.
        val encodedId = mediaPath?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11,}")) }?.let { path ->
            runCatching {
                val bytes = android.util.Base64.decode(path, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                if (bytes.size < 8) null else java.nio.ByteBuffer.wrap(bytes, 0, 8).long.takeIf { it > 0 }?.let { "3_$it" }
            }.getOrNull()
        }
        val fallback = item.optString("url").takeIf { it.isNotBlank() }?.let(PublicPageParser::fallbackMediaKey)
        val alternatives = listOfNotNull(media, legacyMedia?.takeIf { it.matches(Regex("[A-Za-z0-9_]+")) }, encodedId, fallback).distinct().joinToString("|") { Regex.escape(it) }
        val pattern = Regex("^(?:[0-9]{8}|image)_${Regex.escape(post)}_(?:$alternatives)(?: \\([0-9]+\\))?\\.(?:jpg|jpeg|png|webp)$", RegexOption.IGNORE_CASE)
        val resolver = context.contentResolver
        val matches = mutableListOf<Uri>()
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.query(collection, arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.IS_PENDING}=0",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$username/"), null)?.use {
            while (it.moveToNext()) if (pattern.matches(it.getString(1) ?: "")) matches.add(android.content.ContentUris.withAppendedId(collection, it.getLong(0)))
        }
        matches.addAll(ExistingFolder.find(context, username, pattern::matches))
        if (matches.isEmpty()) return null
        for (uri in matches.distinct()) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val valid = runCatching { resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }; bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth >= item.optInt("width") && bounds.outHeight >= item.optInt("height") }.getOrDefault(false)
            if (valid) return uri.toString()
        }
        throw java.io.IOException("已有同名图片无法读取或尺寸不足，请检查文件；不会创建重复文件")
    }

    fun download(username: String, supplied: JSONObject): String {
        check()
        require(username.matches(Regex("[A-Za-z0-9_]{1,15}"))) { "用户名不合法" }
        val item = if (supplied.optBoolean("needs_resolve") || !supplied.optBoolean("non_reply_verified")) {
            // Recheck DOM candidates and old pending tasks against a complete exact-post response.
            val postUrl = "https://x.com/$username/status/${supplied.getString("post_id")}"
            val page = PublicPageParser.parse(publicHtml(postUrl, username), username, postUrl)
            if (supplied.getString("post_id") in page.replyPostIds) throw ReplyExcludedException()
            (0 until page.items.length()).map { page.items.getJSONObject(it) }.firstOrNull {
                it.getString("post_id") == supplied.getString("post_id") &&
                    PublicPageParser.photoIdentity(it.getString("url")) == PublicPageParser.photoIdentity(supplied.getString("url")) &&
                    it.optBoolean("non_reply_verified")
            } ?: throw java.io.IOException("无法确认图片属于非回复的本人原创帖子，已保留待重试")
        } else supplied
        val source = Uri.parse(item.getString("url"))
        require(source.scheme == "https" && source.host == "pbs.twimg.com" && source.path?.startsWith("/media/") == true && source.getQueryParameter("name") == "orig") { "拒绝非原尺寸图片地址" }
        existing(username, item)?.let { return it }
        val temp = File(context.cacheDir, "transfer.part")
        val connection = URL(source.toString()).openConnection() as HttpURLConnection
        connection.connectTimeout = 20000; connection.readTimeout = 30000
        connection.instanceFollowRedirects = false
        try {
            if (connection.responseCode != 200) throw java.io.IOException("原尺寸图片请求失败（HTTP ${connection.responseCode}）")
            if (!connection.contentType.orEmpty().startsWith("image/")) throw java.io.IOException("服务器返回的内容不是图片")
            val expected = connection.contentLengthLong
            var total = 0L
            connection.inputStream.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    check()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 100L * 1024 * 1024) throw java.io.IOException("单张图片超过 100MB 限制")
                    output.write(buffer, 0, count)
                }
            } }
            if (expected >= 0 && total != expected) throw java.io.IOException("图片传输不完整")
            return publish(username, item, temp)
        } finally { connection.disconnect(); temp.delete() }
    }

    /** Publish verified bytes without recompression; also used by the on-device storage check. */
    fun publish(username: String, item: JSONObject, temp: File): String {
            require(username.matches(Regex("[A-Za-z0-9_]{1,15}")))
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw java.io.IOException("图片无法解码")
            if (bounds.outWidth < item.optInt("width") || bounds.outHeight < item.optInt("height")) throw java.io.IOException("返回图片小于媒体记录尺寸，未保存降级图片")
            val extension = when (bounds.outMimeType) {
                "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"
                else -> throw java.io.IOException("不支持的图片格式")
            }
            val name = filename(item, extension)
            val resolver = context.contentResolver
            val path = "${Environment.DIRECTORY_DOWNLOADS}/$username/"
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            existing(username, item)?.let { return it }
            check()
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.RELATIVE_PATH, path)
                put(MediaStore.Downloads.MIME_TYPE, bounds.outMimeType)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val target = resolver.insert(collection, values) ?: throw java.io.IOException("无法创建 Download 文件")
            try {
                val actualName = resolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (actualName != name) throw java.io.IOException("发现不可访问的同名文件，已避免重复保存。请关联 Download/$username 文件夹后重试")
                val output = resolver.openOutputStream(target) ?: throw java.io.IOException("无法写入 Download 文件")
                output.use { out -> temp.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) { check(); val count = input.read(buffer); if (count < 0) break; out.write(buffer, 0, count) }
                } }
                resolver.update(target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                // Some Android versions resolve filename collisions only when pending is cleared.
                val publishedName = resolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (publishedName != name) throw java.io.IOException("发现不可访问的同名文件，已避免重复保存。请关联 Download/$username 文件夹后重试")
                return target.toString()
            } catch (e: Exception) { resolver.delete(target, null, null); throw e }
    }
}
