package com.example.webdavsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次同步运行的记录,用于历史日志。
 *
 * @param taskId      关联任务
 * @param startedAt   开始时间(毫秒)
 * @param finishedAt  结束时间(毫秒),同步未结束为 0
 * @param phase       结束时的状态:FINISHED / CANCELLED / FAILED / SKIPPED
 * @param downloaded  下载/更新文件数
 * @param skipped     跳过文件数
 * @param remoteChanged 远程已变更但未更新(overwrite 关闭)的数量
 * @param failed      失败文件数
 * @param totalBytes  本次传输字节数
 * @param message     摘要信息或失败原因
 */
@Entity(
    tableName = "sync_logs",
    indices = [Index(value = ["taskId"])]
)
data class SyncLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val startedAt: Long,
    val finishedAt: Long = 0L,
    val phase: String,
    val downloaded: Int = 0,
    val skipped: Int = 0,
    val remoteChanged: Int = 0,
    val failed: Int = 0,
    val totalBytes: Long = 0L,
    val message: String = ""
)
