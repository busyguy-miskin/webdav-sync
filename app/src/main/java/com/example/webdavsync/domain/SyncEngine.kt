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
import com.example.webdavsync.domain.model.FileResult
import com.example.webdavsync.domain.model.SyncProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
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
 *     - 有记录且 etag/size 一致 → 跳过(增量)
 *     - 有记录且变化 + overwrite=true → 重新下载(更新)
 *     - 有记录且变化 + overwrite=false → 跳过并标记 REMOTE_CHANGED
 *     - 本地已有文件但无记录 → 跳过(尊重本地文件,不覆盖)
 *  4. 流式下载(GET → SAF OutputStream)
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
        val password = credentialStore.getPassword(task.id)
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
                // 本地已有文件但没有记录(可能是用户手动放进来的) → 不覆盖
                localExists && rec == null -> Action.SKIP
                // 有记录且 etag(优先)或 size 一致 → 未变,跳过
                rec != null && isUnchanged(rec, rf) -> Action.SKIP
                // 文件有变化(或新增)
                rec != null && !isUnchanged(rec, rf) ->
                    if (task.overwrite) Action.UPDATE else Action.REMOTE_CHANGED
                // 新文件
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
        return if (rec.etag.isNotEmpty() && remote.etag.isNotEmpty()) {
            rec.etag == remote.etag
        } else {
            rec.size == remote.size &&
                    (rec.lastModified.isEmpty() || rec.lastModified == remote.lastModified)
        }
    }

    /** 流式下载单个文件到 SAF。 */
    private fun downloadOne(client: WebDavClient, task: SyncTask, relativePath: String) {
        val remotePath = if (task.remotePath.isEmpty() || task.remotePath == "/") {
            relativePath
        } else {
            "${task.remotePath.trimEnd('/')}/$relativePath"
        }
        val out: OutputStream = saf.openOutputStream(task.localTreeUri, relativePath, append = false)
        client.download(remotePath, fromByte = 0L) { input: InputStream, _ ->
            out.use { output -> input.copyTo(output) }
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

    @Suppress("unused") private fun unused(): FileResult = FileResult.DOWNLOADED
}
