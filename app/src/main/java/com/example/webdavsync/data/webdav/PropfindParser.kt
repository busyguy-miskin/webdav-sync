package com.example.webdavsync.data.webdav

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * 解析 WebDAV PROPFIND 的 multistatus 响应,输出 [RemoteResource] 列表。
 *
 * 兼容带 DAV: 命名空间前缀和不带前缀两种写法。
 *
 * 关键:AList 等 WebDAV 服务会对同一个 response 返回**多个 propstat**,
 * 其中属性缺失的 propstat 用 404 状态。按 RFC 4918,客户端只应接受 200 OK
 * 的 propstat 中的属性。本解析器按此规则实现。
 *
 * @param basePath 用于把每个 response 的 href 折算成相对路径。传入请求的目录 href(已 url-decode)。
 *                 传入空串则用第一个 response 作为基准。
 */
object PropfindParser {

    private fun XmlPullParser.nameNoNs(): String = name?.substringAfter(':') ?: ""

    fun parse(input: InputStream, basePath: String): List<RemoteResource> {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
        val parser = factory.newPullParser()
        parser.setInput(input, "UTF-8")
        return parseWith(parser, basePath)
    }

    /** 内部解析,接收已配置好的 parser(便于在 JVM 单测中注入 kxml2 实现)。 */
    internal fun parseWith(parser: XmlPullParser, basePath: String): List<RemoteResource> {
        val results = mutableListOf<RemoteResource>()
        var base = basePath.trimEnd('/')
        var events = parser.eventType

        // 当前 response 的属性暂存
        var currentHref: String? = null
        var isCollection = false
        var size = 0L
        var etag = ""
        var lastModified = ""

        // 当前所在 propstat 是否为成功(200 OK)状态;只有成功的 propstat 的属性才被采纳
        var propstatOk = true

        fun reset() {
            currentHref = null
            isCollection = false
            size = 0L
            etag = ""
            lastModified = ""
            propstatOk = true
        }

        while (events != XmlPullParser.END_DOCUMENT) {
            if (events == XmlPullParser.START_TAG) {
                when (parser.nameNoNs()) {
                    "response" -> reset()
                    "href" -> {
                        // 仅取第一个 href(每个 response 的主 href)
                        if (currentHref == null) currentHref = parser.nextText()
                    }
                    "propstat" -> propstatOk = true // 进入新 propstat,默认成功,等 status 校正
                    "status" -> {
                        // 例如 "HTTP/1.1 404 Not Found" / "HTTP/1.1 200 OK"
                        val text = parser.nextText().trim()
                        val code = text.split(' ').getOrNull(1)?.toIntOrNull()
                        if (code != null && code !in 200..299) propstatOk = false
                    }
                    "collection" -> { if (propstatOk) isCollection = true }
                    "getcontentlength" -> {
                        if (propstatOk) {
                            val txt = parser.nextText().trim()
                            if (txt.isNotEmpty()) size = txt.toLongOrNull() ?: 0L
                        }
                    }
                    "getetag" -> { if (propstatOk) etag = parser.nextText().trim() }
                    "getlastmodified" -> { if (propstatOk) lastModified = parser.nextText().trim() }
                }
            } else if (events == XmlPullParser.END_TAG && parser.nameNoNs() == "response") {
                val href = decode(currentHref ?: continue)
                // 第一个 response 若为目录,且调用方未提供 base,则以其为基准
                if (base.isEmpty()) base = href.trimEnd('/')
                val rel = relativize(base, href, isCollection)
                if (rel != null) {
                    results += RemoteResource(
                        relativePath = rel,
                        isDirectory = isCollection,
                        size = if (isCollection) 0L else size,
                        etag = etag,
                        lastModified = lastModified
                    )
                }
                reset()
            }
            events = parser.next()
        }
        return results
    }

    /**
     * URL 解码(仅 %XX)。href 属于 URI path 而非表单编码:
     * 文件名里的 '+' 是字面加号,不能被解码成空格,故先转义回 %2B 再解码。
     */
    private fun decode(s: String): String =
        try {
            java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: Exception) {
            s
        }

    /**
     * 把 [href] 折算为相对 [base] 的路径。返回 null 表示是 base 本身(根目录)。
     * 例:base="/dav/photos", href="/dav/photos/sub/a.txt" -> "sub/a.txt"
     *    href="/dav/photos" -> ""(根目录,返回 null,调用方按目录跳过)
     */
    private fun relativize(base: String, href: String, isCollection: Boolean): String? {
        val b = base.trimEnd('/')
        val h = href.trimEnd('/')
        if (h == b) return null // 这是查询的根目录本身
        if (!h.startsWith("$b/")) {
            // 某些服务器 href 不含 base 前缀,直接返回去除前导斜杠的原始值
            return h.removePrefix("/").ifEmpty { null }
        }
        return h.removePrefix("$b/")
    }
}
