package com.example.webdavsync.data.webdav

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 轻量 WebDAV 客户端,基于 OkHttp 实现 PROPFIND(目录列举)与 GET(下载)。
 * 仅依赖标准 WebDAV(RFC 4918),不引入第三方库。
 *
 * 一个 [WebDavClient] 实例对应一组凭证。线程安全(OkHttp 本身线程安全)。
 */
class WebDavClient(
    private val serverUrl: String,
    username: String,
    password: String,
    /** 是否信任所有证书(用于内网自签名 HTTPS)。默认关闭以保证安全。 */
    trustAllCerts: Boolean = false
) {
    private val auth = if (username.isNotEmpty() || password.isNotEmpty())
        Credentials.basic(username, password) else null

    private val client: OkHttpClient = buildClient(trustAllCerts)

    /** 拉取某远程目录下全部资源(含子目录文件),返回相对 [remotePath] 的文件清单(不含目录本身)。 */
    fun listFiles(remotePath: String): List<RemoteResource> {
        val url = joinUrl(serverUrl, remotePath)
        val body = PROPFIND_BODY.toRequestBody(MEDIA_XML)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "infinity")
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply { auth?.let { header("Authorization", it) } }
            .build()

        val response = exec(request)
        response.use {
            val parsed = try {
                // base 传空:由解析器用服务器返回的第一个 response(目录自身)的 href
                // 自动确定基准,避免 remotePath 与服务器实际 URL 路径前缀不一致的问题
                // (例如 serverUrl 带 /dav 前缀,而 remotePath 只是 /tianyi/...)
                PropfindParser.parse(it.body!!.byteStream(), "")
            } catch (e: Exception) {
                throw WebDavException.Parse("解析 PROPFIND 响应失败: ${e.message}", e)
            }
            // 过滤掉目录,只保留文件
            return parsed.filterNot { r -> r.isDirectory }
        }
    }

    /**
     * 下载远程文件,把响应体交给 [consumer] 处理(通常流式写入 SAF OutputStream)。
     * 支持断点续传:[fromByte] > 0 时发送 Range 头。
     * 返回响应声明的总字节数(未知时为 -1)。
     */
    fun download(
        remotePath: String,
        fromByte: Long = 0L,
        consumer: (InputStream, Long) -> Unit
    ): Long {
        val url = joinUrl(serverUrl, remotePath)
        val request = Request.Builder()
            .url(url)
            .get()
            .apply {
                auth?.let { header("Authorization", it) }
                if (fromByte > 0L) header("Range", "bytes=$fromByte-")
            }
            .build()

        val response = exec(request)
        response.use {
            val body = it.body ?: throw WebDavException.Network("响应体为空", IllegalStateException())
            val total = body.contentLength()
            consumer(body.byteStream(), if (total > 0) total else -1L)
            return total
        }
    }

    /**
     * 列举某远程目录的**直接子项**(单层,Depth:1),返回子目录与文件清单。
     * 用于任务编辑页的远程目录浏览器:逐层浏览并选定要同步的目录。
     *
     * 返回结果**不含**被查询的目录本身;目录排在文件之前,均按名称排序。
     */
    fun listDirectory(remotePath: String): List<RemoteResource> {
        val url = joinUrl(serverUrl, remotePath)
        val body = PROPFIND_BODY.toRequestBody(MEDIA_XML)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "1")
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply { auth?.let { header("Authorization", it) } }
            .build()

        val response = exec(request)
        response.use {
            val parsed = try {
                // base 传空:由解析器用服务器返回的第一个 response(目录自身)的 href
                // 自动确定基准,避免 remotePath 与服务器实际 URL 路径前缀不一致的问题。
                PropfindParser.parse(it.body!!.byteStream(), "")
            } catch (e: Exception) {
                throw WebDavException.Parse("解析 PROPFIND 响应失败: ${e.message}", e)
            }
            // 解析器已自动剔除被查询目录自身(null 的相对路径)。
            // 单层 PROPFIND 下,剩余条目的相对路径就是其直接子项名。
            return parsed.sortedWith(
                compareByDescending<RemoteResource> { it.isDirectory }
                    .thenBy { it.relativePath }
            )
        }
    }

    /** 轻量探测:对根目录 PROPFIND Depth:0 验证连接与认证。 */
    fun testConnection(remotePath: String = "/"): Boolean {
        val url = joinUrl(serverUrl, remotePath)
        val body = PROPFIND_BODY.toRequestBody(MEDIA_XML)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", "0")
            .header("Content-Type", "application/xml; charset=utf-8")
            .apply { auth?.let { header("Authorization", it) } }
            .build()
        return exec(request).use { it.code in 200..299 }
    }

    private fun exec(request: Request): Response {
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw WebDavException.Network("网络请求失败: ${e.message}", e)
        }
        if (!response.isSuccessful && response.code !in 200..299 && response.code != 206) {
            val code = response.code
            response.close()
            throw when (code) {
                401, 403 -> WebDavException.AuthFailed()
                404 -> WebDavException.NotFound()
                else -> WebDavException.HttpError(code, "HTTP $code")
            }
        }
        return response
    }

    private fun buildClient(trustAll: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (trustAll) {
            installTrustAll(builder)
        }
        return builder.build()
    }

    /** 安装信任全部证书的 TrustManager(仅用于内网自签名场景,务必谨慎)。 */
    private fun installTrustAll(builder: OkHttpClient.Builder) {
        try {
            val tm = object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
            val sslContext = javax.net.ssl.SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<javax.net.ssl.TrustManager>(tm), java.security.SecureRandom())
            }
            builder.sslSocketFactory(sslContext.socketFactory, tm)
            builder.hostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            // 信任全部失败则忽略,回退到默认校验
        }
    }

    companion object {
        private val MEDIA_XML = "application/xml; charset=utf-8".toMediaType()

        // 请求 getetag / getcontentlength / getlastmodified / resourcetype
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:resourcetype/>
    <D:getcontentlength/>
    <D:getetag/>
    <D:getlastmodified/>
  </D:prop>
</D:propfind>"""

        /** 拼接 serverUrl 与 remotePath,处理重复斜杠。 */
        fun joinUrl(base: String, path: String): String {
            val b = base.trimEnd('/')
            val p = if (path.isEmpty() || path == "/") "" else "/" + path.trimStart('/')
            return "$b$p"
        }
    }
}
