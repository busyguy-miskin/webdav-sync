package com.example.webdavsync.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.webdavsync.data.local.entity.SyncTask
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncTaskDao {
    @Query("SELECT * FROM sync_tasks ORDER BY id ASC")
    fun observeAll(): Flow<List<SyncTask>>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    suspend fun getById(id: Long): SyncTask?

    /** 全部已启用任务(用于"同步全部")。 */
    @Query("SELECT * FROM sync_tasks WHERE enabled = 1 ORDER BY id ASC")
    suspend fun getEnabled(): List<SyncTask>

    @Query("SELECT * FROM sync_tasks WHERE id = :id")
    fun observeById(id: Long): Flow<SyncTask?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: SyncTask): Long

    @Update
    suspend fun update(task: SyncTask)

    @Delete
    suspend fun delete(task: SyncTask)

    @Query("UPDATE sync_tasks SET lastSyncTime = :time, lastSyncResult = :result WHERE id = :id")
    suspend fun updateSyncResult(id: Long, time: Long, result: String)

    /** 启用/禁用任务。 */
    @Query("UPDATE sync_tasks SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}
