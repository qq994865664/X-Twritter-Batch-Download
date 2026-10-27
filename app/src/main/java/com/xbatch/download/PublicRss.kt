package com.xbatch.download

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException

/** Public mirror supplies discovery links only. X metadata remains the authority for downloads. */
object PublicRss {
    const val HOST = "nitter.mie.foundation"
    val HOSTS = listOf(HOST, "nitter.kareem.one", "nitter.meowing.monster", "nitter.jaydenha.uk", "nitter.mizuki.st")
    val TABS = listOf("", "media", "search")
    fun url(name: String, cursor: String? = null, host: String = HOST, tab: String = ""): String {
        require(host in HOSTS && tab in (TABS + listOf("with_replies", "search_tweets")))
        return Uri.Builder().scheme("https").authority(host)
            .apply { if (tab.startsWith("search")) appendPath("search") else { appendPath(name); if (tab.isNotEmpty()) appendPath(tab) } }
            .appendPath("rss").apply {
                if (tab.startsWith("search")) { appendQueryParameter("f", if (tab == "search") "media" else "tweets"); appendQueryParameter("q", "from:$name") }
                if (cursor != null) appendQueryParameter("cursor", cursor)
            }.build().toString()
    }

    fun allowed(source: String, name: String): Boolean {
        val uri = Uri.parse(source)
        val path = uri.pathSegments
        val normal = (path == listOf(name, "rss") || (path.size == 3 && path[0] == name && path[1] in listOf("media", "with_replies") && path[2] == "rss")) &&
            uri.queryParameterNames.all { it == "cursor" }
        val search = path == listOf("search", "rss") && uri.getQueryParameter("q") == "from:$name" && uri.getQueryParameter("f") in listOf("media", "tweets") &&
            uri.queryParameterNames.all { it in listOf("q", "f", "cursor") }
        return uri.scheme == "https" && uri.host in HOSTS && uri.userInfo == null && uri.port == -1 && (normal || search) && uri.fragment == null
    }

    data class Page(val posts: List<String>, val entries: Int)
    fun parse(xml: String, name: String): Page {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val posts = linkedSetOf<String>()
        var entries = 0
        var rss = false
        var itemDepth = -1
        var link = ""
        var creator = ""
        var title = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.DOCDECL -> throw IOException("RSS 不允许文档实体声明")
                XmlPullParser.START_TAG -> when {
                    parser.depth == 1 -> { if (parser.name != "rss") throw IOException("返回的不是 RSS，可能需要验证"); rss = true }
                    parser.name == "item" -> { itemDepth = parser.depth; entries++; link = ""; creator = ""; title = "" }
                    itemDepth > 0 && parser.depth == itemDepth + 1 -> when (parser.name.substringAfter(':')) {
                        "link" -> link = parser.nextText().trim()
                        "creator" -> creator = parser.nextText().trim().removePrefix("@")
                        "title" -> title = parser.nextText().trim()
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "item" && parser.depth == itemDepth) {
                    val uri = Uri.parse(link)
                    val segments = uri.pathSegments
                    if (uri.scheme in listOf("https", "http") && uri.host in (HOSTS + listOf("x.com", "twitter.com")) && uri.userInfo == null && uri.port == -1 &&
                        segments.size == 3 && segments[0].equals(name, true) && segments[1] == "status" &&
                        segments[2].matches(Regex("[0-9]+")) && (creator.isBlank() || creator.equals(name, true)) && !title.startsWith("RT ", true)) {
                        posts.add("https://x.com/$name/status/${segments[2]}")
                    }
                    itemDepth = -1
                }
            }
            event = parser.nextToken()
        }
        if (!rss) throw IOException("没有可识别的 RSS 内容")
        return Page(posts.toList(), entries)
    }
}
