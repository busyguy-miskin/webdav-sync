package com.example.webdavsync

import com.example.webdavsync.data.webdav.PropfindParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser

class PropfindParserTest {

    private val sample = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/photos/</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype><D:collection/></D:resourcetype>
        <D:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</D:getlastmodified>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/photos/a.txt</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype/>
        <D:getcontentlength>12</D:getcontentlength>
        <D:getetag>"abc123"</D:getetag>
        <D:getlastmodified>Tue, 02 Jan 2024 00:00:00 GMT</D:getlastmodified>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/photos/sub/b.log</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype/>
        <D:getcontentlength>0</D:getcontentlength>
        <D:getetag>"def456"</D:getetag>
        <D:getlastmodified>Wed, 03 Jan 2024 00:00:00 GMT</D:getlastmodified>
      </D:prop>
    </D:propstat>
  </D:response>
</D:multistatus>"""

    @Test
    fun parses_files_and_drops_directory_and_root() {
        val parser = KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        parser.setInput(sample.reader())
        val result = PropfindParser.parseWith(parser, "/dav/photos")

        // 根目录被过滤;两个文件保留
        assertEquals(2, result.size)

        val a = result.find { it.relativePath == "a.txt" }
        assertTrue(a != null)
        assertEquals(12L, a!!.size)
        assertEquals("\"abc123\"", a.etag)
        assertEquals("Tue, 02 Jan 2024 00:00:00 GMT", a.lastModified)

        val b = result.find { it.relativePath == "sub/b.log" }
        assertTrue(b != null)
        assertEquals("\"def456\"", b!!.etag)
    }

    // AList 真实响应:目录同时返回 200 OK 和 404 Not Found 两个 propstat,
    // 只有 200 的属性(resourcetype=collection, getlastmodified)有效;404 的属性为空。
    // 文件则正常返回 etag/size。验证解析器按 RFC 4918 只采纳 200 propstat 的属性。
    private val alistSample = """<?xml version="1.0" encoding="UTF-8"?><D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/tianyi/%E6%88%91%E7%9A%84%E5%9B%BE%E7%89%87/%E7%85%A7%E7%89%87/202009/</D:href><D:propstat><D:prop><D:resourcetype><D:collection xmlns:D="DAV:"/></D:resourcetype><D:getlastmodified>Mon, 21 Sep 2020 14:07:55 GMT</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat><D:propstat><D:prop><D:getcontentlength></D:getcontentlength><D:getetag></D:getetag></D:prop><D:status>HTTP/1.1 404 Not Found</D:status></D:propstat></D:response><D:response><D:href>/dav/tianyi/%E6%88%91%E7%9A%84%E5%9B%BE%E7%89%87/%E7%85%A7%E7%89%87/202009/mmexport1600697200776.jpg</D:href><D:propstat><D:prop><D:resourcetype></D:resourcetype><D:getcontentlength>59756</D:getcontentlength><D:getetag>"D23F157073F56FA9986388282C39DDF0"</D:getetag><D:getlastmodified>Mon, 21 Sep 2020 14:07:53 GMT</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response><D:response><D:href>/dav/tianyi/%E6%88%91%E7%9A%84%E5%9B%BE%E7%89%87/%E7%85%A7%E7%89%87/202009/mmexport1600697203037.jpg</D:href><D:propstat><D:prop><D:resourcetype></D:resourcetype><D:getcontentlength>34969</D:getcontentlength><D:getetag>"406BF57AA4EAD10E6759BBC51CB263A3"</D:getetag><D:getlastmodified>Mon, 21 Sep 2020 14:07:55 GMT</D:getlastmodified></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>"""

    @Test
    fun parses_alist_multipropstat_and_chinese_path() {
        val parser = KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        parser.setInput(alistSample.reader())
        // basePath 传入未编码的中文路径,与解码后的 href 比对
        val result = PropfindParser.parseWith(parser, "/dav/tianyi/我的图片/照片/202009")

        // 目录被过滤,两个 jpg 保留
        assertEquals(2, result.size)

        val f1 = result.find { it.relativePath == "mmexport1600697200776.jpg" }
        assertTrue(f1 != null)
        assertEquals(59756L, f1!!.size)
        assertEquals("\"D23F157073F56FA9986388282C39DDF0\"", f1.etag)

        val f2 = result.find { it.relativePath == "mmexport1600697203037.jpg" }
        assertTrue(f2 != null)
        assertEquals(34969L, f2!!.size)
        assertEquals("\"406BF57AA4EAD10E6759BBC51CB263A3\"", f2.etag)
    }

    // 单层 PROPFIND(Depth:1)响应:目录本身 + 两个子目录 + 一个文件。
    // 用于验证 listDirectory 选目录场景:解析器自动剔除根目录,保留子目录与文件。
    private val depth1Sample = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/photos</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype>
      <D:getlastmodified>Mon, 01 Jan 2024 00:00:00 GMT</D:getlastmodified></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/docs</D:href>
    <D:propstat>
      <D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/readme.txt</D:href>
    <D:propstat>
      <D:prop><D:resourcetype/>
      <D:getcontentlength>42</D:getcontentlength>
      <D:getetag>"r1"</D:getetag></D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""

    @Test
    fun depth1_keeps_dirs_and_files_and_drops_root() {
        val parser = KXmlParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) }
        parser.setInput(depth1Sample.reader())
        // base 传空:由解析器用第一个 response(根 /dav)确定基准
        val result = PropfindParser.parseWith(parser, "")

        // 根目录自身被剔除,剩 2 目录 + 1 文件
        assertEquals(3, result.size)

        val photos = result.find { it.relativePath == "photos" }
        assertTrue("photos 应存在", photos != null)
        assertTrue("photos 应为目录", photos!!.isDirectory)
        val docs = result.find { it.relativePath == "docs" }
        assertTrue("docs 应存在", docs != null)
        assertTrue("docs 应为目录", docs!!.isDirectory)
        val readme = result.find { it.relativePath == "readme.txt" }
        assertTrue("readme 应存在", readme != null)
        assertTrue("readme 应为文件", !readme!!.isDirectory)
        assertEquals(42L, readme.size)
        assertEquals("\"r1\"", readme.etag)

        // name 取最后一段
        assertEquals("readme.txt", readme.name)
        assertEquals("photos", photos.name)
    }
}
