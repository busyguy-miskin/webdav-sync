package com.example.webdavsync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.webdavsync.data.webdav.RemoteResource
import com.example.webdavsync.data.webdav.WebDavClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/**
 * 设备端真实环境联调（instrumented test，跑在真机上）。
 * 验证 App 在真实设备上能通过 WebDavClient 跑通 PROPFIND + GET 全链路。
 *
 * 服务器地址与凭证一律外部传入，仓库中不保存任何真实账号信息。
 * 通过 instrumentation 参数传入（未配置则用 Assume 跳过，不算失败）：
 *
 * ```
 * ./gradlew connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_SERVER=http://192.168.x.x:5244/dav \
 *   -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_USER=admin \
 *   -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_PASS=secret \
 *   -Pandroid.testInstrumentationRunnerArguments.WEBDAV_E2E_DIR=/some/dir
 * ```
 *
 * DIR 应配置一个文件较少的目录，测试会下载其中第一个文件。
 */
@RunWith(AndroidJUnit4::class)
class DeviceSyncE2ETest {

    private fun arg(name: String): String =
        InstrumentationRegistry.getArguments().getString(name) ?: ""

    private val server by lazy { arg("WEBDAV_E2E_SERVER") }
    private val user by lazy { arg("WEBDAV_E2E_USER") }
    private val pass by lazy { arg("WEBDAV_E2E_PASS") }
    private val dir by lazy { arg("WEBDAV_E2E_DIR") }

    @Before
    fun assumeConfigured() {
        assumeTrue(
            "未配置 instrumentation 参数 WEBDAV_E2E_*，跳过设备端 E2E 测试",
            server.isNotBlank() && user.isNotBlank() && pass.isNotBlank() && dir.isNotBlank()
        )
    }

    @Test
    fun device_can_propfind_and_download() {
        val client = WebDavClient(server, user, pass)

        // PROPFIND
        val files = client.listFiles(dir)
        assertTrue("应至少列出 1 个文件，实际: ${files.size}", files.isNotEmpty())

        // GET 第一个文件，校验大小与 PROPFIND 一致
        val first = files.first()
        val out = ByteArrayOutputStream()
        client.download("${dir.trimEnd('/')}/${first.relativePath}") { input, _ ->
            input.copyTo(out)
        }
        val bytes = out.toByteArray()
        assertTrue("应下载到内容", bytes.isNotEmpty())
        if (first.size > 0) {
            assertEquals("大小应匹配 PROPFIND", first.size, bytes.size.toLong())
        }
        println("E2E 已下载: ${first.relativePath} (${bytes.size} bytes)")
    }

    /**
     * 验证任务编辑页「浏览」功能所依赖的 listDirectory（Depth:1）：
     * 能连上真实服务器，逐层列出子项，目录排在文件之前。
     * 这覆盖了"连接 WebDAV 选目录"的核心网络/解析逻辑。
     */
    @Test
    fun device_can_list_directory_single_level() {
        val client = WebDavClient(server, user, pass)

        val entries = client.listDirectory(dir)
        assertTrue("$dir 应能列出至少 1 个子项，实际: ${entries.size}", entries.isNotEmpty())
        println("E2E $dir 子项: ${entries.joinToString { it.name + typeLabel(it) }}")

        // 钻入第一个子目录，验证逐层浏览可行
        val firstSub = entries.firstOrNull { it.isDirectory }
        if (firstSub != null) {
            val subPath = "${dir.trimEnd('/')}/${firstSub.relativePath.removePrefix("/")}"
            val subEntries = client.listDirectory(subPath)
            println("E2E $subPath 子项: ${subEntries.joinToString { it.name + typeLabel(it) }}")
        }

        // 验证目录优先排序：前若干项若含目录，则目录在前
        val firstFileIdx = entries.indexOfFirst { !it.isDirectory }
        val lastDirIdx = entries.indexOfLast { it.isDirectory }
        if (firstFileIdx >= 0 && lastDirIdx >= 0) {
            assertTrue("目录应排在文件之前", lastDirIdx < firstFileIdx)
        }
    }

    private fun typeLabel(r: RemoteResource): String =
        if (r.isDirectory) "(目录)" else "(文件)"
}
