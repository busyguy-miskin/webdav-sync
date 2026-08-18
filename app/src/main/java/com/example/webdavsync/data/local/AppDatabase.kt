package com.example.webdavsync.data.local

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import android.content.Context
import com.example.webdavsync.data.local.dao.FileRecordDao
import com.example.webdavsync.data.local.dao.SyncLogDao
import com.example.webdavsync.data.local.dao.SyncTaskDao
import com.example.webdavsync.data.local.entity.FileRecord
import com.example.webdavsync.data.local.entity.SyncLog
import com.example.webdavsync.data.local.entity.SyncTask

@Database(
    entities = [SyncTask::class, FileRecord::class, SyncLog::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncTaskDao(): SyncTaskDao
    abstract fun fileRecordDao(): FileRecordDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2:为已安装用户保留数据,新增任务字段与同步日志表。
         * 新字段在旧表上不存在,ALTER 补列(布尔默认值对齐实体默认)。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN enabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN wifiOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sync_tasks ADD COLUMN trustAllCerts INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS sync_logs (
                        id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        taskId INTEGER NOT NULL,
                        startedAt INTEGER NOT NULL,
                        finishedAt INTEGER NOT NULL DEFAULT 0,
                        phase TEXT NOT NULL,
                        downloaded INTEGER NOT NULL DEFAULT 0,
                        skipped INTEGER NOT NULL DEFAULT 0,
                        remoteChanged INTEGER NOT NULL DEFAULT 0,
                        failed INTEGER NOT NULL DEFAULT 0,
                        totalBytes INTEGER NOT NULL DEFAULT 0,
                        message TEXT NOT NULL DEFAULT ''
                    )""".trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sync_logs_taskId ON sync_logs(taskId)")
            }
        }

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "webdav_sync.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
    }
}
