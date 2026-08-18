package com.example.webdavsync.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 单个文件的同步记录,用于增量判断。
 *
 * 一个 (taskId + relativePath) 唯一标识一个文件。
 * relativePath 相对于 [SyncTask.remotePath] 的远程子路径,正斜杠分隔,例如 "sub/a.txt"。
 *
 * @param etag          远程文件 ETag(可能为空);ETag 不变即认为文件未变
 * @param size          远程文件大小(字节)
 * @param lastModified  远程文件最后修改时间(HTTP 日期字符串)
 * @param syncedAt      本次记录写入时间(毫秒)
 * @param status        同步状态:OK / SKIPPED / FAILED / REMOTE_CHANGED
 */
@Entity(
    tableName = "file_records",
    indices = [Index(value = ["taskId", "relativePath"], unique = true)]
)
data class FileRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val relativePath: String,
    val etag: String,
    val size: Long,
    val lastModified: String,
    val syncedAt: Long,
    val status: String
)
