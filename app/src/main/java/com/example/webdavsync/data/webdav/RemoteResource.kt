package com.example.webdavsync.data.webdav

/**
 * PROPFIND 解析后的一条远程资源。
 *
 * @param relativePath 相对查询根目录的子路径,正斜杠分隔,**不以** / 开头。
 *                     查询根目录本身为 ""。
 * @param isDirectory  是否为目录
 * @param size         文件大小(字节);目录为 0
 * @param etag         ETag;可能为空
 * @param lastModified HTTP 日期字符串(RFC1123);可能为空
 */
data class RemoteResource(
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
    val etag: String,
    val lastModified: String
) {
    /** 显示名:relativePath 的最后一段(单层列举时就是子项名)。 */
    val name: String
        get() = relativePath.substringAfterLast('/').ifEmpty { relativePath }
}
