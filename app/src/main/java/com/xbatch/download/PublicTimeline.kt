package com.xbatch.download

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.time.Instant
import java.util.Locale

/** Read only public HTML. Never execute embedded scripts or request login/API credentials. */
object PublicPageParser {
    data class Page(val items: JSONArray, val posts: Int, val recognized: Boolean, val next: List<String>, val postIds: Set<String>, val replyPostIds: Set<String>)

    fun username(input: String): String {
        var value = input.trim()
        if (value.contains("://") || value.startsWith("x.com/", true) || value.startsWith("twitter.com/", true) || value.startsWith("www.x.com/", true) || value.startsWith("www.twitter.com/", true)) {
            val uri = Uri.parse(if (value.contains("://")) value else "https://$value")
            require(uri.scheme == "https" && uri.host?.lowercase(Locale.ROOT) in setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com") && uri.userInfo == null) { "请输入 X 用户名或 HTTPS 用户主页链接" }
            require(uri.pathSegments.size == 1) { "请粘贴用户主页，不是单条帖子链接" }
            value = uri.pathSegments[0]
        }
        value = value.removePrefix("@")
        require(value.matches(Regex("[A-Za-z0-9_]{1,15}"))) { "用户名只能包含 1–15 位字母、数字和下划线" }
        require(value.lowercase(Locale.ROOT) !in setOf("home", "explore", "search", "settings", "login", "signup", "i")) { "请输入个人主页的用户名" }
        return value
    }

    fun originalUrl(raw: String): String {
        val uri = Uri.parse(raw)
        require(uri.scheme == "https" && uri.host == "pbs.twimg.com" && uri.path.orEmpty().startsWith("/media/"))
        var path = uri.path!!
        var format = uri.getQueryParameter("format")
        if (format == null) { format = path.substringAfterLast('.', ""); path = path.substringBeforeLast('.') }
        require(format.lowercase(Locale.ROOT) in setOf("jpg", "jpeg", "png", "webp"))
        return Uri.Builder().scheme("https").authority("pbs.twimg.com").path(path)
            .appendQueryParameter("format", format.lowercase(Locale.ROOT)).appendQueryParameter("name", "orig").build().toString()
    }

    fun photoIdentity(raw: String): String = Uri.parse(raw).path.orEmpty().substringBeforeLast('.')
    fun fallbackMediaKey(raw: String): String = "photo_" + java.security.MessageDigest.getInstance("SHA-256")
        .digest(photoIdentity(raw).toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it.toInt() and 255) }

    fun preferPhoto(current: JSONObject?, candidate: JSONObject): JSONObject {
        if (current == null) return candidate
        fun score(item: JSONObject): Long {
            val stable = if (item.optString("media_key").matches(Regex("3_[0-9]+"))) 1_000_000_000_000L else 0L
            return stable + item.optInt("width").toLong().coerceAtLeast(0) * item.optInt("height").toLong().coerceAtLeast(0)
        }
        return if (score(candidate) > score(current)) candidate else current
    }

    fun allowedPost(url: String, username: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.host == "x.com" && uri.userInfo == null && uri.port == -1 &&
            uri.pathSegments.size == 3 && uri.pathSegments[0].equals(username, true) &&
            uri.pathSegments[1] == "status" && uri.pathSegments[2].matches(Regex("[0-9]+"))
    }

    fun allowedPage(url: String, username: String): Boolean {
        val uri = Uri.parse(url)
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443)) return false
        if (uri.host == "syndication.twitter.com") return uri.path == "/srv/timeline-profile/screen-name/$username"
        return uri.host in setOf("x.com", "twitter.com", "www.x.com", "www.twitter.com") &&
            uri.pathSegments.firstOrNull()?.equals(username, true) == true &&
            (uri.pathSegments.size == 1 || (uri.pathSegments.size == 2 && uri.pathSegments[1] == "media"))
    }

    fun parse(html: String, username: String, source: String): Page {
        val roots = mutableListOf<Any>()
        val scripts = Regex("<script\\b[^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        for (script in scripts.findAll(html)) {
            val body = script.groupValues[1].trim()
            if (body.startsWith("{")) runCatching { roots.add(JSONObject(body)) }
            // X's server-rendered pages stream GraphQL result JSON as escaped string values.
            // Decode JSON string literals only; the surrounding JavaScript is never evaluated.
            for (value in Regex("\\bvalue\\s*:\\s*(\"(?:\\\\.|[^\"\\\\])*\")").findAll(body)) {
                runCatching {
                    val decoded = JSONTokener(value.groupValues[1]).nextValue() as String
                    if (decoded.startsWith("{")) roots.add(JSONObject(decoded))
                }
            }
            val state = Regex("(?:window\\.)?__INITIAL_STATE__\\s*=\\s*").find(body)
            if (state != null) runCatching { roots.add(JSONTokener(body.substring(state.range.last + 1)).nextValue()) }
        }
        val photos = linkedMapOf<String, JSONObject>()
        val postIds = mutableSetOf<String>()
        val replyPostIds = mutableSetOf<String>()
        val repostOrQuoteIds = mutableSetOf<String>()
        val verifiedOriginals = mutableSetOf<String>()
        val sourcePost = if (allowedPost(source, username)) Uri.parse(source).lastPathSegment else null
        var recognized = false

        fun present(obj: JSONObject, key: String): Boolean {
            if (!obj.has(key) || obj.isNull(key)) return false
            return when (val value = obj.opt(key)) {
                is String -> value.isNotBlank() && value != "0" && value != "null"
                is Number -> value.toLong() != 0L
                is Boolean -> value
                is JSONObject -> value.length() > 0
                is JSONArray -> value.length() > 0
                else -> false
            }
        }
        fun isReply(obj: JSONObject, id: String): Boolean {
            val fields = listOf("reply_to_results", "reply_to_result", "reply_to_user_results", "in_reply_to_status_id",
                "in_reply_to_status_id_str", "in_reply_to_user_id", "in_reply_to_user_id_str", "in_reply_to_screen_name",
                "inReplyToStatusId", "inReplyToUserId", "is_reply", "isReply")
            if (fields.any { present(obj, it) }) return true
            for (key in listOf("conversation_id", "conversation_id_str", "conversationId")) {
                if (present(obj, key) && obj.optString(key) != id) return true
            }
            return false
        }

        fun parseTweet(tweet: JSONObject) {
            val legacy = tweet.optJSONObject("legacy") ?: tweet
            val user = tweet.optJSONObject("core")?.optJSONObject("user_results")?.optJSONObject("result") ?: tweet.optJSONObject("user")
            val handle = user?.optJSONObject("core")?.optString("screen_name")?.takeIf { it.isNotBlank() }
                ?: user?.optJSONObject("legacy")?.optString("screen_name")?.takeIf { it.isNotBlank() }
                ?: user?.optString("screen_name")
            if (!username.equals(handle, true)) return
            recognized = true
            val id = tweet.optString("rest_id").ifBlank { legacy.optString("id_str").ifBlank { tweet.optString("id_str") } }
            if (!id.matches(Regex("[0-9]+"))) return
            postIds.add(id)
            if (isReply(tweet, id) || isReply(legacy, id) || tweet.optJSONObject("details")?.let { isReply(it, id) } == true) {
                replyPostIds.add(id)
                return
            }
            fun references(obj: JSONObject): Boolean = obj.keys().asSequence().any {
                (it.startsWith("retweeted_") || it.startsWith("quoted_")) && !obj.isNull(it) && obj.opt(it) != false
            }
            if (references(tweet) || references(legacy) || legacy.optBoolean("is_quote_status") || tweet.optJSONObject("details")?.optBoolean("is_quote_status") == true) {
                repostOrQuoteIds.add(id)
                return
            }
            // A complete exact-post response can certify that omitted reply fields mean no parent.
            if (id == sourcePost && (tweet.optJSONObject("edit_control") != null || legacy.has("in_reply_to_status_id") ||
                    legacy.has("in_reply_to_status_id_str") || tweet.has("in_reply_to_status_id") || tweet.has("in_reply_to_status_id_str"))) {
                verifiedOriginals.add(id)
            }
            val richArrays = listOfNotNull(tweet.optJSONArray("media_entities2"), legacy.optJSONObject("extended_entities")?.optJSONArray("media"),
                tweet.optJSONObject("extended_entities")?.optJSONArray("media"))
            // Legacy entities can label video thumbnails as photos; use only when no richer media list exists.
            val arrays = if (richArrays.any { it.length() > 0 }) richArrays else
                richArrays + listOfNotNull(legacy.optJSONObject("entities")?.optJSONArray("media"))
            for (media in arrays) for (i in 0 until media.length()) {
                val image = media.optJSONObject(i) ?: continue
                if (image.optString("type") != "photo") continue
                val url = runCatching { originalUrl(image.optString("media_url_https")) }.getOrNull() ?: continue
                val key = image.optString("media_key").ifBlank { image.optString("id_str").takeIf { it.matches(Regex("[0-9]+")) }?.let { "3_$it" }
                    ?: fallbackMediaKey(url) }
                if (!key.matches(Regex("[A-Za-z0-9_]+"))) continue
                val size = image.optJSONObject("original_info")
                val ms = tweet.optJSONObject("details")?.optLong("created_at_ms") ?: 0L
                // Snowflake timestamps provide a deterministic UTC date for legacy embed payloads.
                val created = runCatching { Instant.ofEpochMilli(if (ms > 0) ms else (id.toLong() shr 22) + 1288834974657L).toString() }.getOrDefault("")
                val item = JSONObject().put("media_key", key).put("post_id", id).put("index", i + 1)
                    .put("url", url).put("created_at", created).put("width", size?.optInt("width") ?: 0).put("height", size?.optInt("height") ?: 0)
                // Keep separate posts distinct until reply/repost filtering has completed.
                val identity = "$id:${photoIdentity(url)}"
                photos[identity] = preferPhoto(photos[identity], item)
            }
        }

        fun visit(node: Any?, depth: Int = 0) {
            if (depth > 80) return
            when (node) {
                is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i), depth + 1)
                is JSONObject -> {
                    val type = node.optString("__typename")
                    val context = node.optJSONObject("social_context") ?: node.optJSONObject("socialContext")
                    val contextType = context?.optString("context_type").orEmpty() + context?.optString("contextType").orEmpty()
                    if (contextType.contains("retweet", true) || contextType.contains("repost", true) || node.has("promotedMetadata")) return
                    if (type == "Tweet" || (node.has("id_str") && node.has("user") && (node.has("entities") || node.has("extended_entities")))) {
                        parseTweet(node)
                        return // Do not descend into quoted/reposted tweets or attached user objects.
                    }
                    if (node.has("timeline") || node.has("instructions")) recognized = true
                    if (type == "User" || node.has("screen_name")) {
                        val handle = node.optJSONObject("core")?.optString("screen_name") ?: node.optJSONObject("legacy")?.optString("screen_name") ?: node.optString("screen_name")
                        if (username.equals(handle, true)) recognized = true
                    }
                    node.keys().forEach { key ->
                        if (!key.startsWith("quoted_") && !key.startsWith("retweeted_")) visit(node.opt(key), depth + 1)
                    }
                }
            }
        }
        roots.forEach { visit(it) }

        // Follow only explicit HTML pagination for this profile. Never invent GraphQL cursors.
        val next = mutableListOf<String>()
        for (tag in Regex("<(?:a|link)\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html)) {
            val attrs = Regex("([\\w-]+)\\s*=\\s*([\"'])(.*?)\\2", RegexOption.DOT_MATCHES_ALL).findAll(tag.value)
                .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[3].replace("&amp;", "&") }
            val href = attrs["href"] ?: continue
            val isNext = attrs["rel"]?.split(" ")?.contains("next") == true || Regex("[?&](?:max_id|cursor)=").containsMatchIn(href)
            if (!isNext) continue
            val url = runCatching { java.net.URI(source).resolve(href).toString() }.getOrNull() ?: continue
            if (allowedPage(url, username) && url != source) next.add(url)
        }
        val accepted = photos.values.filter { it.getString("post_id") !in replyPostIds && it.getString("post_id") !in repostOrQuoteIds }
        accepted.forEach { if (it.getString("post_id") in verifiedOriginals) it.put("non_reply_verified", true) }
        return Page(JSONArray(accepted), postIds.size, recognized, next.distinct(), postIds.toSet(), replyPostIds.toSet())
    }
}

class PublicTimeline(private val transfer: TimelineTransport, private val sleep: (Long) -> Unit = Thread::sleep) {
    fun newTask(input: String): JSONObject {
        val name = PublicPageParser.username(input)
        val queue = JSONArray()
        // Try the preferred mirror before the remaining discovery sources.
        for (tab in PublicRss.TABS) queue.put(PublicRss.url(name, host = PublicRss.HOST, tab = tab))
        queue.put("https://x.com/$name").put("https://x.com/$name/media")
            .put("https://syndication.twitter.com/srv/timeline-profile/screen-name/$name")
        for (host in PublicRss.HOSTS.drop(1)) for (tab in PublicRss.TABS) queue.put(PublicRss.url(name, host = host, tab = tab))
        return JSONObject().put("source", "public_html").put("user_id", "public:" + name.lowercase(Locale.ROOT))
            .put("username", name).put("scan_done", false).put("cursor", JSONObject().put("queue", queue).put("visited", JSONArray()).toString())
            .put("source_notes", JSONArray()).put("failed_sources", JSONArray()).put("pages_read", 0)
    }

    fun page(task: JSONObject): JSONObject {
        val cursor = JSONObject(task.getString("cursor"))
        val queue = cursor.getJSONArray("queue")
        val visited = cursor.getJSONArray("visited")
        val source = queue.remove(0) as String
        val name = task.getString("username")
        require(PublicPageParser.allowedPage(source, name) || PublicPageParser.allowedPost(source, name) || PublicRss.allowed(source, name)) { "扫描地址无效，请重新开始" }
        transfer.check()
        // Old saved queues may still contain reply-only feeds; skip them without requesting them.
        if (PublicRss.allowed(source, name)) {
            val uri = Uri.parse(source)
            if ("with_replies" in uri.pathSegments || (uri.pathSegments.firstOrNull() == "search" && uri.getQueryParameter("f") == "tweets")) {
                visited.put(source)
                return JSONObject().put("items", JSONArray()).put("next_cursor", if (queue.length() == 0) JSONObject.NULL else cursor.toString())
            }
        }
        var items = JSONArray()
        try {
            // Keep requests sequential and paced; cursor/queue are committed together with discoveries.
            if (visited.length() > 0) {
                repeat(if (PublicRss.allowed(source, name)) 15 else 5) { sleep(100); transfer.check() }
            }
            val document = transfer.publicDocument(source, name)
            val known = (0 until visited.length()).map { visited.getString(it) }.toSet() + source + (0 until queue.length()).map { queue.getString(it) }
            if (PublicRss.allowed(source, name)) {
                val page = PublicRss.parse(document.text, name)
                val newPosts = page.posts.filterNot { it in known }
                newPosts.forEach { queue.put(it) }
                val sourceUri = Uri.parse(source)
                val tab = if (sourceUri.pathSegments.firstOrNull() == "search") {
                    if (sourceUri.getQueryParameter("f") == "tweets") "search_tweets" else "search"
                } else sourceUri.pathSegments.getOrNull(1)?.takeIf { it != "rss" } ?: ""
                val stream = "${sourceUri.host}/$tab"
                val streams = cursor.optJSONObject("rss_streams") ?: JSONObject().also { cursor.put("rss_streams", it) }
                val state = streams.optJSONObject(stream) ?: JSONObject().also { streams.put(stream, it) }
                val signature = page.posts.sorted().joinToString(",")
                val repeats = if (signature.isNotEmpty() && signature == state.optString("signature")) state.optInt("repeats") + 1 else 0
                state.put("signature", signature).put("repeats", repeats)
                // Streams and mirrors paginate independently; overlap with another stream is not an end marker.
                val repeated = repeats >= 2
                val next = document.cursor?.let { PublicRss.url(name, it, sourceUri.host!!, tab) }
                val continuing = page.entries > 0 && !repeated && next != null && next !in known
                if (continuing) queue.put(next)
                val ending = when {
                    page.entries == 0 -> "RSS 已返回空页"
                    repeated -> "该 RSS 连续返回相同帖子，停止翻页"
                    next == null -> "RSS 未提供下一页"
                    next in known -> "RSS 分页游标重复，停止翻页"
                    else -> "将自动继续翻页"
                }
                task.getJSONArray("source_notes").put("${sourceUri.host}/${if (tab.isBlank()) "主页" else tab}：${page.entries} 条记录，${newPosts.size} 条待核对本人帖子；$ending")
            } else {
                val page = PublicPageParser.parse(document.text, name, source)
                if (!page.recognized) throw IOException("未取得可识别的帖子数据，页面可能要求登录或已改变")
                val isPost = PublicPageParser.allowedPost(source, name)
                val postId = Uri.parse(source).lastPathSegment
                if (isPost && postId !in page.postIds) throw IOException("未能核对指定帖子及作者")
                val unique = linkedMapOf<String, JSONObject>()
                for (i in 0 until page.items.length()) {
                    val item = page.items.getJSONObject(i)
                    if (!isPost || item.getString("post_id") == postId) {
                        val identity = PublicPageParser.photoIdentity(item.getString("url"))
                        unique[identity] = PublicPageParser.preferPhoto(unique[identity], item)
                    }
                }
                items = JSONArray(unique.values.toList())
                task.getJSONArray("source_notes").put("${Uri.parse(source).host}${Uri.parse(source).path}：识别 ${page.posts} 条帖子、${items.length()} 张图片")
                if (!isPost) page.next.filterNot { it in known }.forEach { queue.put(it) }
            }
            task.put("pages_read", task.optInt("pages_read") + 1)
        } catch (e: Exception) {
            transfer.check()
            val reason = when (e) {
                is javax.net.ssl.SSLException -> "安全连接失败，请检查手机网络能否访问 X"
                is java.net.SocketTimeoutException -> "连接超时，请检查网络后重试"
                is java.net.UnknownHostException -> "无法解析 X 域名，请检查手机网络"
                else -> e.message ?: "访问失败"
            }
            task.getJSONArray("source_notes").put("${Uri.parse(source).host}${Uri.parse(source).path}：$reason")
            if (PublicPageParser.allowedPost(source, name)) {
                val retries = cursor.optJSONObject("post_retries") ?: JSONObject().also { cursor.put("post_retries", it) }
                val attempt = retries.optInt(source) + 1
                retries.put(source, attempt)
                if (attempt <= 2) queue.put(source) else {
                    val failures = task.optJSONArray("failed_sources") ?: JSONArray().also { task.put("failed_sources", it) }
                    failures.put(source)
                }
            } else if (PublicRss.allowed(source, name)) {
                val failures = task.optJSONArray("failed_sources") ?: JSONArray().also { task.put("failed_sources", it) }
                failures.put(source)
            }
            items = JSONArray()
        }
        visited.put(source)
        return JSONObject().put("items", items).put("next_cursor", if (queue.length() == 0) JSONObject.NULL else cursor.toString())
    }
}
