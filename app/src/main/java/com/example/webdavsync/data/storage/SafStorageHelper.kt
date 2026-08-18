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
 *
 * 所有接受 relativePath 的入口都会先做路径安全校验(见 [safeParts]),
 * 拒绝 ../、空段、控制字符与系统保留名,防止异常/恶意的远程文件名注入本地路径。
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
        val parts = safeParts(relativePath)
        val dir = navigateToDir(treeUri, parts)
            ?: throw WebDavException.NotFound("本地目录权限失效,请重新选择目录")
        val fileName = parts.last()
        var file = dir.findFile(fileName)
        if (file == null) {
            file = dir.createFile(MIME_OCTET_STREAM, fileName)
                ?: throw WebDavException.Network("无法创建文件 $fileName", IOException())
        }
        return context.contentResolver.openOutputStream(file.uri, if (append) "wa" else "wt")
            ?: throw WebDavException.Network("无法打开文件输出流 $fileName", IOException())
    }

    /**
     * 安全写入:全部字节先写临时文件,完成后原子替换目标文件。
     * - 写入中途失败(断网/取消/进程被杀):删除临时文件,原文件保持完好;
     * - 替换阶段失败:自动恢复备份,尽力不丢旧版本。
     */
    fun writeAtomically(treeUri: String, relativePath: String, write: (OutputStream) -> Unit) {
        val parts = safeParts(relativePath)
        val dir = navigateToDir(treeUri, parts)
            ?: throw WebDavException.NotFound("本地目录权限失效,请重新选择目录")
        val fileName = parts.last()
        val target = dir.findFile(fileName)
        val tempName = fileName + TEMP_SUFFIX
        // 复用上次异常退出遗留的临时文件,"wt" 模式会先截断
        val temp = dir.findFile(tempName)
            ?: dir.createFile(MIME_OCTET_STREAM, tempName)
            ?: throw WebDavException.Network("无法创建临时文件 $tempName", IOException())
        try {
            context.contentResolver.openOutputStream(temp.uri, "wt")?.use { write(it) }
                ?: throw WebDavException.Network("无法打开临时文件输出流 $tempName", IOException())

            // 旧文件先挪走,再把临时文件改回正名;失败则恢复,避免替换过程丢数据
            val backupName = fileName + BACKUP_SUFFIX
            val backup = target?.takeIf { it.exists() }
            if (backup != null && !backup.renameTo(backupName)) {
                throw WebDavException.Network("无法备份旧文件 $fileName", IOException())
            }
            try {
                if (!temp.renameTo(fileName)) {
                    throw WebDavException.Network("无法替换文件 $fileName", IOException())
                }
            } catch (e: Exception) {
                backup?.renameTo(fileName)
                throw e
            }
            backup?.delete()
        } catch (e: Exception) {
            runCatching { temp.delete() } // 清掉残缺临时文件,不在本地留半截文件
            throw e
        }
    }

    /** 本地是否已存在该文件。 */
    fun fileExists(treeUri: String, relativePath: String): Boolean {
        val parts = safeParts(relativePath)
        val dir = rootDir(treeUri) ?: return false
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
        val parts = safeParts(relativePath)
        val dir = rootDir(treeUri) ?: return -1L
        var current = dir
        for (i in parts.indices) {
            val next = current.findFile(parts[i]) ?: return -1L
            if (i == parts.lastIndex) return if (next.isFile) next.length() else -1L
            current = next
        }
        return -1L
    }

    /**
     * 校验远程下发的相对路径并切分为路径段:拒绝 `..`/`.`/空段、
     * 控制字符(含 NUL)、超长段(>255)与 Windows 保留设备名(CON、CON.txt 等)。
     */
    private fun safeParts(relativePath: String): List<String> {
        val parts = relativePath.split('/').filter { it.isNotEmpty() }
        val dangerous = parts.any { seg ->
            seg == "." || seg == ".." || seg.length > 255 ||
                seg.any { it.code < 0x20 || it.code == 0x7F } ||
                WINDOWS_RESERVED.matches(seg)
        }
        if (parts.isEmpty() || dangerous) {
            throw WebDavException.Parse(
                "远程路径不安全,已拒绝写入本地: $relativePath",
                IllegalArgumentException(relativePath)
            )
        }
        return parts
    }

    /** 走到 relativePath 的父目录(按需逐级创建子目录);根目录权限失效返回 null。 */
    private fun navigateToDir(treeUri: String, parts: List<String>): DocumentFile? {
        val root = rootDir(treeUri) ?: return null
        var current = root
        for (i in 0 until parts.size - 1) {
            current = current.findFile(parts[i])
                ?: current.createDirectory(parts[i])
                ?: throw WebDavException.Network("无法创建目录 ${parts[i]}", IOException())
        }
        return current
    }

    companion object {
        private const val MIME_OCTET_STREAM = "application/octet-stream"
        private const val TEMP_SUFFIX = ".webdavsync-part"
        private const val BACKUP_SUFFIX = ".webdavsync-old"

        // Windows 保留设备名(CON、CON.txt 等):目录将来被拷到 PC 时会变成不可用/不可删文件
        private val WINDOWS_RESERVED =
            Regex("^(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?$", RegexOption.IGNORE_CASE)
    }
}
