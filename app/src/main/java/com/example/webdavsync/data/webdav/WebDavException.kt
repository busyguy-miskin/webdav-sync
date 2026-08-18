package com.example.webdavsync.data.webdav

/** WebDAV 客户端统一异常。 */
sealed class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 认证失败(401 / 403)。 */
    class AuthFailed(message: String = "认证失败:用户名或密码错误") : WebDavException(message)

    /** 远程资源不存在(404)。 */
    class NotFound(message: String = "远程路径不存在") : WebDavException(message)

    /** 非 2xx 且非上面已知情况的 HTTP 错误。 */
    class HttpError(val code: Int, message: String) : WebDavException(message)

    /** 网络/IO 错误。 */
    class Network(message: String, cause: Throwable) : WebDavException(message, cause)

    /** 响应解析失败。 */
    class Parse(message: String, cause: Throwable) : WebDavException(message, cause)
}
