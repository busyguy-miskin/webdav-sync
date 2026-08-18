package com.example.webdavsync.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.webdavsync.data.local.entity.SyncLog
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {
    /** 插入一条日志,返回新 id。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SyncLog): Long

    /** 更新结束信息(结束时调用)。 */
    @Query("""UPDATE sync_logs
              SET finishedAt = :finishedAt, phase = :phase,
                  downloaded = :downloaded, skipped = :skipped,
                  remoteChanged = :remoteChanged, failed = :failed,
                  totalBytes = :totalBytes, message = :message
              WHERE id = :id""")
    suspend fun finish(
        id: Long,
        finishedAt: Long,
        phase: String,
        downloaded: Int,
        skipped: Int,
        remoteChanged: Int,
        failed: Int,
        totalBytes: Long,
        message: String
    )

    /** 某任务最近 N 条日志(新到旧)。 */
    @Query("SELECT * FROM sync_logs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(taskId: Long, limit: Int = 20): Flow<List<SyncLog>>

    /** 某任务最近 N 条日志(一次性)。 */
    @Query("SELECT * FROM sync_logs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(taskId: Long, limit: Int = 20): List<SyncLog>

    /** 删除某任务的全部日志(任务删除时清理)。 */
    @Query("DELETE FROM sync_logs WHERE taskId = :taskId")
    suspend fun deleteByTask(taskId: Long)

    /** 全局保留最近 [keep] 条,删除更早的(定期清理)。 */
    @Query("DELETE FROM sync_logs WHERE id NOT IN (SELECT id FROM sync_logs ORDER BY startedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int = 500)
}
