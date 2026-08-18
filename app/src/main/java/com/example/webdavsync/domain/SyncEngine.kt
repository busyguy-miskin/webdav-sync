package com.example.webdavsync.domain

import com.example.webdavsync.data.local.dao.FileRecordDao
import com.example.webdavsync.data.local.entity.FileRecord
import com.example.webdavsync.data.local.entity.SyncTask
import com.example.webdavsync.data.prefs.CredentialStore
import com.example.webdavsync.data.storage.NetworkChecker
import com.example.webdavsync.data.storage.SafStorageHelper
import com.example.webdavsync.data.webdav.RemoteResource
import com.example.webdavsync.data.webdav.WebDavClient
import com.example.webdavsync.data.webdav.WebDavException
import com.example.webdavsync.domain.model.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.OutputStream

/** 单个文件的同步动作。 */
private enum class Action { DOWNLOAD, UPDATE, SKIP, REMOTE_CHANGED }

/** 比对后对单个文件的计划。 */
private data class Plan(val rel: String, val remote: RemoteResource, val action: Action)

/**
 * 核心同步引擎。实现增量下载,只增不删/不覆盖(由 [SyncTask.overwrite] 决定是否更新已变更文件)。
 *
 * 算法:
 *  1. 校验本地 SAF 权限
 *  2. PROPFIND 拉取远程文件清单
 *  3. 与 Room 中已有 [FileRecord] 比对,决定每个文件的动作:
 *     - 无记录且本地文件不存在 → 下载(新增)
 *     - 有记录且 etag/size 一致、本地文件仍存在 → 跳过(增量)
 *     - 有记录且变化 + overwrite=true → 重新下载(更新)
 *     - 有记录且变化 + overwrite=false → 跳过并标记 REMOTE_CHANGED
 *     - 本地已有文件但无记录 → 跳过(尊重本地文件,不覆盖)
 *     - 记录未变但本地文件缺失(换过本地目录/文件被删) → 重新下载(自愈)
 *  4. 流式下载(GET → 临时文件,写完原子替换,失败不留残缺文件)
 *  5. 全程不删除任何本地文件
 */
class SyncEngine(
    private val fileRecordDao: FileRecordDao,
    private val saf: SafStorageHelper,
    private val credentialStore: CredentialStore,
    private val networkChecker: NetworkChecker
) {
    /**
     * 执行一次同步。[onProgress] 在每个阶段/文件变化时回调,可用于更新 UI/通知。
     * 抛出 [WebDavException] 表示同步整体失败;部分文件失败不抛出,计入 failed。
     */
    suspend fun sync(
        task: SyncTask,
        onProgress: (SyncProgress) -> Unit
    ): SyncProgress {
        val password = credentialStore.getPassword(task.id) ?: ""
        var progress = SyncProgress(phase = SyncProgress.Phase.LISTING, taskName = task.name)
        onProgress(progress)

        // 0. 网络前置检查:仅 Wi-Fi 任务在移动网络下跳过
        if (task.wifiOnly && !networkChecker.isOnWifi()) {
            progress = progress.copy(
                phase = SyncProgress.Phase.SKIPPED,
                message = "已跳过:任务设置为仅 Wi-Fi 同步,当前非 Wi-Fi 网络"
            )
            onProgress(progress)
            return progress
        }
        if (!networkChecker.isOnline()) {
            progress = progress.copy(
                phase = SyncProgress.Phase.FAILED,
                message = "无网络连接,请检查网络后重试"
            )
            onProgress(progress)
            return progress
        }

        // 1. 校验本地目录权限
        if (!saf.hasPermission(task.localTreeUri)) {
            progress = progress.copy(
                phase = SyncProgress.Phase.FAILED,
                message = "本地目录权限失效,请在任务编辑中重新选择目录"
            )
            onProgress(progress)
            return progress
        }

        val client = WebDavClient(
            serverUrl = task.serverUrl,
            username = task.username,
            password = password,
            trustAllCerts = task.trustAllCerts
        )

        // 2. PROPFIND 拉取远程文件清单
        val remoteFiles = try {
            client.listFiles(task.remotePath)
        } catch (e: WebDavException.NotFound) {
            progress = progress.copy(
                phase = SyncProgress.Phase.FAILED,
                message = "远程目录不存在: ${task.remotePath}"
            )
            onProgress(progress)
            return progress
        } catch (e: WebDavException) {
            progress = progress.copy(
                phase = SyncProgress.Phase.FAILED,
                message = e.message ?: "拉取远程清单失败"
            )
            onProgress(progress)
            return progress
        }

        // 3. 读取已有记录,构造比对映射
        val existing = fileRecordDao.getByTask(task.id).associateBy { it.relativePath }
        val errors = mutableListOf<String>()

        progress = progress.copy(phase = SyncProgress.Phase.COMPARING)
        onProgress(progress)

        // 计算每个文件的动作
        val plans = mutableListOf<Plan>()
        var toDownloadCount = 0
        var toDownloadBytes = 0L
        var skipCount = 0
        var remoteChangedCount = 0

        for (rf in remoteFiles) {
            currentCoroutineContext().ensureActive()
            val rec = existing[rf.relativePath]
            val localExists = saf.fileExists(task.localTreeUri, rf.relativePath)
            val action = when {
                // 有记录未变,且本地文件确实存在 → 跳过(增量)
                rec != null && isUnchanged(rec, rf) && localExists -> Action.SKIP
                // 本地已有文件但没有记录(可能是用户手动放进来的) → 不覆盖
                localExists && rec == null -> Action.SKIP
                // 文件有变化(或新增)
                rec != null && !isUnchanged(rec, rf) ->
                    if (task.overwrite) Action.UPDATE else Action.REMOTE_CHANGED
                // 其余:新文件,或记录未变但本地缺失(换过目录/文件被删) → 下载,自动修复
                else -> Action.DOWNLOAD
            }
            plans += Plan(rf.relativePath, rf, action)
            when (action) {
                Action.DOWNLOAD, Action.UPDATE -> {
                    toDownloadCount++
                    toDownloadBytes += rf.size
                }
                Action.SKIP -> skipCount++
                Action.REMOTE_CHANGED -> remoteChangedCount++
            }
        }

        // 4. 下载
        val toDownload = plans.filter { it.action == Action.DOWNLOAD || it.action == Action.UPDATE }
        progress = progress.copy(
            phase = SyncProgress.Phase.DOWNLOADING,
            totalFiles = toDownload.size,
            totalBytes = toDownloadBytes,
            skipped = skipCount,
            remoteChanged = remoteChangedCount
        )
        onProgress(progress)

        var doneFiles = 0
        var doneBytes = 0L
        var downloaded = 0
        var updated = 0
        var failed = 0

        for (plan in toDownload) {
            currentCoroutineContext().ensureActive()
            val base = progress.copy(
                currentFile = plan.rel,
                doneFiles = doneFiles,
                doneBytes = doneBytes
            )
            onProgress(base)

            try {
                downloadOne(client, task, plan.rel)
                if (plan.action == Action.UPDATE) updated++ else downloaded++
                upsertRecord(task.id, plan.remote)
                doneBytes += plan.remote.size
                doneFiles++
                onProgress(base.copy(doneFiles = doneFiles, doneBytes = doneBytes))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed++
                errors += "${plan.rel}: ${e.message}"
                onProgress(base.copy(failed = failed))
            }
        }

        // 5. 完成
        val msg = "完成 ${downloaded + updated},跳过 ${skipCount},远程已变更 ${remoteChangedCount},失败 ${failed}"
        val finalProgress = progress.copy(
            phase = SyncProgress.Phase.FINISHED,
            doneFiles = doneFiles,
            doneBytes = doneBytes,
            downloaded = downloaded + updated,
            skipped = skipCount,
            remoteChanged = remoteChangedCount,
            failed = failed,
            message = msg,
            errors = errors,
            currentFile = ""
        )
        onProgress(finalProgress)
        return finalProgress
    }

    /** 判断文件是否未变:ETag 优先,无 ETag 时用 size + lastModified 兜底。 */
    private fun isUnchanged(rec: FileRecord, remote: RemoteResource): Boolean {
        if (rec.etag.isNotEmpty() && remote.etag.isNotEmpty()) {
            return rec.etag == remote.etag
        }
        if (rec.size != remote.size) return false
        if (rec.lastModified.isEmpty() || remote.lastModified.isEmpty()) return true
        // 日期优先解析为同一时间基(秒)再比较,避免服务器日期格式/时区写法漂移造成假阳性
        val local = parseHttpDate(rec.lastModified)
        val remoteDate = parseHttpDate(remote.lastModified)
        return if (local != null && remoteDate != null) local == remoteDate
        else rec.lastModified == remote.lastModified
    }

    /** RFC1123 HTTP 日期 → epoch 秒;不识别的格式返回 null,由调用方回退字符串比较。 */
    private fun parseHttpDate(s: String): Long? = try {
        java.time.OffsetDateTime
            .parse(s, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toEpochSecond()
    } catch (e: java.time.format.DateTimeParseException) {
        null
    }

    /** 流式下载单个文件到 SAF:先写临时文件,成功后原子替换,失败不留残缺文件。 */
    private suspend fun downloadOne(client: WebDavClient, task: SyncTask, relativePath: String) {
        val remotePath = if (task.remotePath.isEmpty() || task.remotePath == "/") {
            relativePath
        } else {
            "${task.remotePath.trimEnd('/')}/$relativePath"
        }
        val coroutineContext = currentCoroutineContext()
        val ensureActive = {
            if (!coroutineContext.isActive) throw CancellationException("同步已取消")
        }
        saf.writeAtomically(task.localTreeUri, relativePath) { output ->
            val guarded = CancellingOutputStream(output, ensureActive)
            client.download(remotePath, fromByte = 0L) { input, _ ->
                input.copyTo(guarded)
            }
        }
    }

    /** 写入/更新文件记录。 */
    private suspend fun upsertRecord(taskId: Long, remote: RemoteResource) {
        fileRecordDao.upsert(
            FileRecord(
                taskId = taskId,
                relativePath = remote.relativePath,
                etag = remote.etag,
                size = remote.size,
                lastModified = remote.lastModified,
                syncedAt = System.currentTimeMillis(),
                status = "OK"
            )
        )
    }
}

/** 每次写缓冲前检查协程是否仍活跃,让"取消"能及时中断大文件的下载写入。 */
private class CancellingOutputStream(
    private val delegate: OutputStream,
    private val ensureActive: () -> Unit
) : OutputStream() {
    override fun write(b: Int) {
        ensureActive()
        delegate.write(b)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureActive()
        delegate.write(b, off, len)
    }

    override fun flush() = delegate.flush()
    override fun close() = delegate.close()
}
