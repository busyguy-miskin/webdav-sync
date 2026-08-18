package com.example.webdavsync.ui.task

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.webdavsync.WebDavSyncApp
import com.example.webdavsync.data.local.entity.SyncTask
import com.example.webdavsync.data.webdav.RemoteResource
import com.example.webdavsync.data.webdav.WebDavClient
import com.example.webdavsync.data.webdav.WebDavException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TaskViewModel(app: Application) : AndroidViewModel(app) {

    private val container get() = getApplication<WebDavSyncApp>().container
    private val taskDao get() = container.syncTaskDao
    private val credentialStore get() = container.credentialStore
    private val saf get() = container.safStorage

    /** 全部任务,UI 自动刷新。 */
    val tasks: StateFlow<List<SyncTask>> = taskDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 保存(新建或更新)。taskId<=0 表示新建。返回新/更新后的 id。 */
    fun saveTask(
        taskId: Long,
        name: String,
        serverUrl: String,
        username: String,
        password: String,
        remotePath: String,
        localTreeUri: String,
        overwrite: Boolean,
        enabled: Boolean,
        wifiOnly: Boolean,
        trustAllCerts: Boolean,
        onDone: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val id = if (taskId > 0) {
                taskDao.update(
                    SyncTask(
                        id = taskId, name = name, serverUrl = serverUrl, username = username,
                        remotePath = remotePath, localTreeUri = localTreeUri, overwrite = overwrite,
                        enabled = enabled, wifiOnly = wifiOnly, trustAllCerts = trustAllCerts
                    )
                )
                // 更新密码(若提供非空则覆盖;空字符串视为不修改)
                if (password.isNotEmpty()) credentialStore.savePassword(taskId, password)
                taskId
            } else {
                val newId = taskDao.insert(
                    SyncTask(
                        name = name, serverUrl = serverUrl, username = username,
                        remotePath = remotePath, localTreeUri = localTreeUri, overwrite = overwrite,
                        enabled = enabled, wifiOnly = wifiOnly, trustAllCerts = trustAllCerts
                    )
                )
                credentialStore.savePassword(newId, password)
                newId
            }
            onDone(id)
        }
    }

    /** 启用/禁用任务。 */
    fun toggleEnabled(task: SyncTask) {
        viewModelScope.launch { taskDao.setEnabled(task.id, !task.enabled) }
    }

    /**
     * 删除任务,并清理相关数据:加密凭证、文件记录、同步日志,以及释放 SAF 持久化权限。
     */
    fun deleteTask(task: SyncTask) {
        viewModelScope.launch {
            credentialStore.deletePassword(task.id)
            container.fileRecordDao.deleteByTask(task.id)
            container.syncLogDao.deleteByTask(task.id)
            releaseSafPermission(task.localTreeUri)
            taskDao.delete(task)
        }
    }

    /** 释放某 treeUri 的持久化读写权限(任务删除时调用)。 */
    private fun releaseSafPermission(treeUri: String) {
        runCatching {
            val uri = android.net.Uri.parse(treeUri)
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            getApplication<WebDavSyncApp>().contentResolver.releasePersistableUriPermission(uri, flags)
        }
    }

    /** 读取已存密码(测试连接时,编辑现有任务且密码留空用)。 */
    fun getPasswordForTest(taskId: Long): String = credentialStore.getPassword(taskId)

    /** 持久化 SAF 授权,供保存任务后使用。返回可读的目录名称用于显示。 */
    fun takeSafPermission(uri: Uri): String {
        saf.takePersistablePermission(uri)
        return uri.toString()
    }

    /** 测试 WebDAV 连接与认证。 */
    suspend fun testConnection(
        serverUrl: String,
        username: String,
        password: String,
        remotePath: String,
        trustAllCerts: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val ok = WebDavClient(serverUrl, username, password, trustAllCerts).testConnection(remotePath)
            if (ok) Result.success(Unit) else Result.failure(WebDavException.HttpError(0, "连接失败,服务器未返回成功状态"))
        } catch (e: WebDavException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(WebDavException.Network("网络错误: ${e.message}", e))
        }
    }

    /**
     * 浏览远程目录的**直接子项**(目录 + 文件),供任务编辑页选目录。
     *
     * @param serverUrl  服务器地址
     * @param username   用户名
     * @param password   密码(为空时,编辑现有任务会自动回退到已存密码)
     * @param taskId     编辑现有任务时传入(用于密码回退),新建传 0
     * @param remotePath 要列举的远程目录
     * @param trustAllCerts 是否信任所有证书(与任务的设置保持一致)
     */
    suspend fun browseDirectory(
        serverUrl: String,
        username: String,
        password: String,
        taskId: Long,
        remotePath: String,
        trustAllCerts: Boolean = false
    ): Result<List<RemoteResource>> = withContext(Dispatchers.IO) {
        try {
            val pwd = password.ifEmpty {
                if (taskId > 0) credentialStore.getPassword(taskId) else ""
            }
            val entries = WebDavClient(serverUrl, username, pwd, trustAllCerts).listDirectory(remotePath)
            Result.success(entries)
        } catch (e: WebDavException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(WebDavException.Network("网络错误: ${e.message}", e))
        }
    }
}
