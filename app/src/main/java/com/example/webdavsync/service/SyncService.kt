package com.example.webdavsync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.webdavsync.MainActivity
import com.example.webdavsync.R
import com.example.webdavsync.WebDavSyncApp
import com.example.webdavsync.data.local.dao.SyncLogDao
import com.example.webdavsync.data.local.dao.SyncTaskDao
import com.example.webdavsync.data.local.entity.SyncLog
import com.example.webdavsync.domain.model.SyncProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 前台同步服务。手动触发后即使 App 切到后台/锁屏也继续同步,通知栏显示进度并可取消。
 *
 * 通过 Intent extra ACTION_SYNC + taskId 启动;ACTION_CANCEL 取消。
 * 进度通过单例 [liveProgress] 暴露给 UI 观察。
 */
class SyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var syncJob: Job? = null
    private lateinit var taskDao: SyncTaskDao
    private lateinit var logDao: SyncLogDao

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        taskDao = (application as WebDavSyncApp).container.syncTaskDao
        logDao = (application as WebDavSyncApp).container.syncLogDao
        createNotificationChannel()
    }

    /** 待同步任务队列(支持单任务与"同步全部")。主线程入队、IO 协程出队,加锁保证一致。 */
    private val pending = ArrayDeque<Long>()
    private val pendingLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC -> {
                val ids = intent.getLongArrayExtra(EXTRA_TASK_IDS)
                val single = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                synchronized(pendingLock) {
                    if (ids != null && ids.isNotEmpty()) {
                        ids.forEach { if (it > 0) pending.addLast(it) }
                    } else if (single >= 0) {
                        pending.addLast(single)
                    }
                    if (pending.isEmpty()) {
                        stopSelf(); return START_NOT_STICKY
                    }
                }
                startForegroundCompat(buildNotification(SyncProgress()))
                drainQueue()
            }
            ACTION_CANCEL -> {
                synchronized(pendingLock) { pending.clear() }
                syncJob?.cancel()
                _liveProgress.value = _liveProgress.value.copy(phase = SyncProgress.Phase.CANCELLED, message = "已取消")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** 依次消费队列中的任务;单个任务失败不影响后续。 */
    private fun drainQueue() {
        if (syncJob?.isActive == true) return // 同一时间只跑一个协程
        val container = (application as WebDavSyncApp).container
        syncJob = scope.launch {
            while (true) {
                val taskId = synchronized(pendingLock) { pending.removeFirstOrNull() } ?: break
                runOne(container, taskId)
            }
            runCatching { logDao.trim(KEEP_LOGS) }
            stopSelf()
        }
    }

    /** 同步单个任务并记录历史日志。 */
    private suspend fun runOne(container: com.example.webdavsync.di.AppContainer, taskId: Long) {
        val task = taskDao.getById(taskId) ?: return
        val logId = logDao.insert(
            SyncLog(taskId = taskId, startedAt = System.currentTimeMillis(), phase = "RUNNING")
        )
        try {
            val result = container.syncEngine.sync(task) { p ->
                _liveProgress.value = p
                notifyProgress(p)
            }
            taskDao.updateSyncResult(taskId, System.currentTimeMillis(), result.message)
            logDao.finish(
                id = logId,
                finishedAt = System.currentTimeMillis(),
                phase = result.phase.name,
                downloaded = result.downloaded,
                skipped = result.skipped,
                remoteChanged = result.remoteChanged,
                failed = result.failed,
                totalBytes = result.doneBytes,
                message = result.message
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消:把当前日志标记为取消后向上抛,让 drainQueue 退出
            logDao.finish(
                id = logId, finishedAt = System.currentTimeMillis(), phase = "CANCELLED",
                downloaded = 0, skipped = 0, remoteChanged = 0, failed = 0,
                totalBytes = 0, message = "已取消"
            )
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: "同步失败"
            _liveProgress.value = SyncProgress(
                phase = SyncProgress.Phase.FAILED,
                taskName = task.name,
                message = msg
            )
            logDao.finish(
                id = logId, finishedAt = System.currentTimeMillis(), phase = "FAILED",
                downloaded = 0, skipped = 0, remoteChanged = 0, failed = 0,
                totalBytes = 0, message = msg
            )
        }
    }

    private fun notifyProgress(p: SyncProgress) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(p))
    }

    private fun buildNotification(p: SyncProgress): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPi = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, SyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = when (p.phase) {
            SyncProgress.Phase.LISTING -> "正在获取远程文件清单…"
            SyncProgress.Phase.COMPARING -> "正在比对文件…"
            SyncProgress.Phase.DOWNLOADING -> "正在同步: ${p.taskName}"
            SyncProgress.Phase.FINISHED -> "同步完成: ${p.taskName}"
            SyncProgress.Phase.CANCELLED -> "已取消"
            SyncProgress.Phase.FAILED -> "同步失败"
            SyncProgress.Phase.SKIPPED -> "已跳过: ${p.taskName}"
            SyncProgress.Phase.IDLE -> "准备同步…"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(p.currentFile.ifEmpty { p.message })
            .setOngoing(p.phase == SyncProgress.Phase.DOWNLOADING || p.phase == SyncProgress.Phase.LISTING || p.phase == SyncProgress.Phase.COMPARING)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "取消", cancelPi)

        if (p.phase == SyncProgress.Phase.DOWNLOADING && p.totalFiles > 0) {
            builder.setProgress(100, p.percent, false)
            builder.setContentText("${p.doneFiles}/${p.totalFiles} · ${p.currentFile}")
        } else if (p.phase in listOf(SyncProgress.Phase.LISTING, SyncProgress.Phase.COMPARING, SyncProgress.Phase.IDLE)) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "同步进度",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "WebDAV 同步进度通知" }
        nm.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION")
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val ACTION_SYNC = "com.example.webdavsync.action.SYNC"
        const val ACTION_CANCEL = "com.example.webdavsync.action.CANCEL"
        const val EXTRA_TASK_ID = "task_id"
        const val EXTRA_TASK_IDS = "task_ids"
        private const val CHANNEL_ID = "sync_progress"
        private const val NOTIF_ID = 1001
        private const val KEEP_LOGS = 500

        /** UI 观察的实时进度流。 */
        private val _liveProgress = MutableStateFlow(SyncProgress(phase = SyncProgress.Phase.IDLE))
        val liveProgress: StateFlow<SyncProgress> = _liveProgress.asStateFlow()

        fun start(context: Context, taskId: Long) {
            val intent = Intent(context, SyncService::class.java)
                .setAction(ACTION_SYNC)
                .putExtra(EXTRA_TASK_ID, taskId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** 同步多个任务(用于"全部同步"),按顺序执行。 */
        fun startAll(context: Context, taskIds: LongArray) {
            if (taskIds.isEmpty()) return
            val intent = Intent(context, SyncService::class.java)
                .setAction(ACTION_SYNC)
                .putExtra(EXTRA_TASK_IDS, taskIds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, SyncService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
