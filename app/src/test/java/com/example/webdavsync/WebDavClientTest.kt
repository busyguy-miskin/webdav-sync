package com.example.webdavsync

import com.example.webdavsync.data.webdav.WebDavClient
import com.example.webdavsync.data.webdav.WebDavException
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * WebDavClient 网络行为单测(MockWebServer):
 * 429/5xx 指数退避重试、Retry-After 尊重、Range 续传守卫、Depth:infinity 拒绝后的逐层回退。
 * 退避基数注入为 1ms 加速测试;真实默认值见 WebDavClient 构造参数。
 */
class WebDavClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(maxRetries: Int = 3, baseDelayMs: Long = 1L) = WebDavClient(
        serverUrl = server.url("/dav").toString(),
        username = "user",
        password = "pass",
        retryMaxAttempts = maxRetries,
        retryBaseDelayMs = baseDelayMs
    )

    @Test
    fun retries_on_429_then_succeeds() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("hello"))

        val received = ByteArrayOutputStream()
        client().download("/a.txt") { input, _ -> input.copyTo(received) }

        assertEquals("hello", received.toString("UTF-8"))
        assertEquals("429 后应重试一次", 2, server.requestCount)
    }

    @Test
    fun gives_up_after_max_retries_on_503() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val e = assertThrows(WebDavException.HttpError::class.java) {
            client(maxRetries = 2).download("/a.txt") { _, _ -> }
        }
        assertEquals(503, e.code)
        assertEquals("1 次首发 + 2 次重试后放弃", 3, server.requestCount)
    }

    @Test(timeout = 10_000)
    fun retry_after_header_overrides_backoff_delay() {
        // 退避基数设为 60s:若实现忽略 Retry-After: 0,本测试必然超时失败
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val received = ByteArrayOutputStream()
        client(maxRetries = 1, baseDelayMs = 60_000L).download("/a.txt") { input, _ ->
            input.copyTo(received)
        }

        assertEquals("ok", received.toString("UTF-8"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun range_request_returning_200_throws_instead_of_corrupting() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("abcdef"))

        // 服务器忽略 Range 返回 200 + 完整 body:必须报错,不能让调用方按追加语义写出损坏数据
        assertThrows(WebDavException.Network::class.java) {
            client().download("/a.txt", fromByte = 5L) { _, _ -> }
        }
    }

    @Test
    fun range_request_returning_206_passes() {
        server.enqueue(MockResponse().setResponseCode(206).setBody("f"))

        val received = ByteArrayOutputStream()
        client().download("/a.txt", fromByte = 5L) { input, _ -> input.copyTo(received) }

        assertEquals("f", received.toString("UTF-8"))
    }

    @Test
    fun auth_failure_maps_to_authfailed_exception() {
        server.enqueue(MockResponse().setResponseCode(401))
        assertThrows(WebDavException.AuthFailed::class.java) {
            client().download("/a.txt") { _, _ -> }
        }
    }

    // Depth:infinity 被拒(403)后,回退为逐层 Depth:1 遍历,结果与一次拉全等价
    @Test
    fun listFiles_falls_back_to_depth1_walk_when_infinity_rejected() {
        server.enqueue(MockResponse().setResponseCode(403)) // Depth:infinity 被拒
        server.enqueue(MockResponse().setResponseCode(207).setBody(ROOT_LISTING))
        server.enqueue(MockResponse().setResponseCode(207).setBody(SUB_LISTING))

        val files = client().listFiles("/")

        assertEquals(2, files.size)
        assertTrue("根下文件应保留", files.any { it.relativePath == "root.txt" && it.size == 5L })
        assertTrue("子目录文件应带完整层级", files.any { it.relativePath == "sub/inner.txt" && it.size == 3L })
    }

    companion object {
        private const val ROOT_LISTING = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/sub</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/root.txt</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>5</D:getcontentlength><D:getetag>"r"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
</D:multistatus>"""

        private const val SUB_LISTING = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/dav/sub/</D:href>
    <D:propstat><D:prop><D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
  <D:response>
    <D:href>/dav/sub/inner.txt</D:href>
    <D:propstat><D:prop><D:resourcetype/><D:getcontentlength>3</D:getcontentlength><D:getetag>"i"</D:getetag></D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
  </D:response>
</D:multistatus>"""
    }
}
