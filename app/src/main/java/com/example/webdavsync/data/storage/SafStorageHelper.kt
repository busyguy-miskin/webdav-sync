package com.example.webdavsync.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.webdavsync.data.webdav.WebDavException
import java.io.IOException
import java.io.OutputStream

/**
 * 封装 SAF(DocumentFile)目录的持久化授权与读写。
 *
 * 用户通过 ACTION_OPEN_DOCUMENT_TREE 选中目录后,App 调 [takePersistablePermission]
 * 持久化访问权限;之后可跨进程/重启继续访问。
 */
class SafStorageHelper(private val context: Context) {

    /** 申请持久化访问权限。在 picker 的 onActivityResult 中调用。 */
    fun takePersistablePermission(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    /** 校验已保存的 treeUri 仍有持久化读写权限。 */
    fun hasPermission(treeUri: String): Boolean {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    /** 返回目录根 DocumentFile;权限失效或目录不存在返回 null。 */
    fun rootDir(treeUri: String): DocumentFile? {
        if (!hasPermission(treeUri)) return null
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) ?: return null
        return if (root.exists()) root else null
    }

    /**
     * 在本地目录下创建/取得到 [relativePath] 对应文件的输出流。
     * 自动按正斜杠创建缺失的子目录。relativePath 形如 "sub/a.txt"。
     * 若文件已存在则覆盖(写入由调用方决定是否真写)。
     */
    fun openOutputStream(treeUri: String, relativePath: String, append: Boolean): OutputStream {
        val dir = rootDir(treeUri) ?: throw WebDavException.NotFound("本地目录权限失效,请重新选择目录")
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = dir
        // 除最后一段外,逐级创建/进入目录
        for (i in 0 until parts.size - 1) {
            current = current.findFile(parts[i])
                ?: current.createDirectory(parts[i])
                ?: throw WebDavException.Network("无法创建目录 ${parts[i]}", IOException())
        }
        val fileName = parts.last()
        var file = current.findFile(fileName)
        if (file == null) {
            file = current.createFile("application/octet-stream", fileName)
                ?: throw WebDavException.Network("无法创建文件 $fileName", IOException())
        } else if (append) {
            // 追加模式:SAF 不支持真正的 append,这里以 "rws" 风格由调用方控制偏移
        }
        // append 模式下无法直接拿到 OutputStream 追加;SAF 限制下,我们提供覆盖写,断点续传由调用方 seek 处理
        return context.contentResolver.openOutputStream(file.uri, if (append) "wa" else "wt")
            ?: throw WebDavException.Network("无法打开文件输出流 $fileName", IOException())
    }

    /** 本地是否已存在该文件。 */
    fun fileExists(treeUri: String, relativePath: String): Boolean {
        val dir = rootDir(treeUri) ?: return false
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = dir
        for (i in parts.indices) {
            val next = current.findFile(parts[i]) ?: return false
            if (i == parts.lastIndex) return next.isFile
            current = next
        }
        return false
    }

    /** 本地文件大小(字节);不存在返回 -1。 */
    fun fileSize(treeUri: String, relativePath: String): Long {
        val dir = rootDir(treeUri) ?: return -1L
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        var current = dir
        for (i in parts.indices) {
            val next = current.findFile(parts[i]) ?: return -1L
            if (i == parts.lastIndex) return if (next.isFile) next.length() else -1L
            current = next
        }
        return -1L
    }
}
