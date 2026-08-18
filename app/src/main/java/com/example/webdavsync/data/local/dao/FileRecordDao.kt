package com.example.webdavsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.webdavsync.data.local.entity.FileRecord

@Dao
interface FileRecordDao {
    /** 取某任务下全部文件记录。 */
    @Query("SELECT * FROM file_records WHERE taskId = :taskId")
    suspend fun getByTask(taskId: Long): List<FileRecord>

    /** 单条 upsert(按 taskId+relativePath 唯一索引替换)。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(record: FileRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(records: List<FileRecord>)

    /** 删除某任务的全部记录(任务删除时清理)。 */
    @Query("DELETE FROM file_records WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)
}
