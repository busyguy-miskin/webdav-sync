package com.example.webdavsync

import com.example.webdavsync.data.webdav.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import kotlin.io.path.exists
import java.nio.file.Paths

/**
 * 端到端联调：针对真实 WebDAV 服务器验证 WebDavClient 的 PROPFIND + GET 代码路径。
 *
 * 服务器地址与凭证一律外部传入，仓库中不保存任何真实账号信息。按以下顺序读取
 * （找到即用，全都没配置则用 Assume 跳过，不算失败）：
 *   1. 环境变量        WEBDAV_E2E_SERVER / WEBDAV_E2E_USER / WEBDAV_E2E_PASS / WEBDAV_E2E_DIR
 *   2. JVM 系统属性    同名 key
 *   3. 本地配置文件    app/e2e.properties（已被 .gitignore 排除，模板见 e2e.properties.example）
 *
 * DIR 应配置一个文件较少的目录，测试会下载其中第一个文件。
 */
class WebDavClientE2ETest {

    private val server = cfg("WEBDAV_E2E_SERVER")
    private val user = cfg("WEBDAV_E2E_USER")
    private val pass = cfg("WEBDAV_E2E_PASS")
    private val dir = cfg("WEBDAV_E2E_DIR")

    private fun cfg(key: String): String {
        val file = Paths.get("e2e.properties").takeIf { it.exists() }?.let { p ->
            // 必须用 UTF-8 Reader:Properties.load(InputStream) 按 ISO-8859-1 解码,会把中文目录读成乱码
            Properties().apply { p.toFile().reader(Charsets.UTF_8).use { load(it) } }
        }
        return System.getenv(key) ?: System.getProperty(key) ?: file?.getProperty(key) ?: ""
    }

    @Before
    fun assumeConfigured() {
        assumeTrue(
            "未配置 WEBDAV_E2E_*（环境变量/系统属性/app/e2e.properties），跳过端到端测试",
            server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() && dir.isNotBlank()
        )
    }

    /** 服务器可达才继续，否则跳过。 */
    private fun assumeServerReachable() {
        var reachable = false
        try {
            val conn = (URL("$server/").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET"
            }
            conn.connect()
            reachable = conn.responseCode in 200..499 // 任意 HTTP 响应都算可达
            conn.disconnect()
        } catch (e: Exception) {
            reachable = false
        }
        assumeTrue("WebDAV 服务器不可达，跳过端到端测试", reachable)
    }

    @Test
    fun connection_works() {
        assumeServerReachable()
        val client = WebDavClient(server, user, pass)
        assertTrue("连接/认证应成功", client.testConnection(dir))
    }

    @Test
    fun propfind_lists_files() {
        assumeServerReachable()
        val client = WebDavClient(server, user, pass)
        val files = client.listFiles(dir)
        assertTrue("应至少列出 1 个文件，实际: ${files.size}", files.isNotEmpty())
        assertTrue("文件应有非空 relativePath", files.all { it.relativePath.isNotBlank() })
        // AList 等服务器会带 etag；允许个别为空，但至少一个非空更符合预期
        assertTrue("应至少有一个文件带 etag", files.any { it.etag.isNotEmpty() })
    }

    @Test
    fun download_first_file_matches_propfind_size() {
        assumeServerReachable()
        val client = WebDavClient(server, user, pass)
        val first = client.listFiles(dir).first()
        val out = ByteArrayOutputStream()
        client.download("${dir.trimEnd('/')}/${first.relativePath}") { input, _ ->
            input.copyTo(out)
        }
        val bytes = out.toByteArray()
        assertTrue("应下载到内容", bytes.isNotEmpty())
        if (first.size > 0) {
            assertEquals("下载大小应匹配 PROPFIND 声明", first.size, bytes.size.toLong())
        }
    }
}
