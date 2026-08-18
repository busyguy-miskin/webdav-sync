package com.example.webdavsync.di

import android.content.Context
import com.example.webdavsync.data.local.AppDatabase
import com.example.webdavsync.data.local.dao.FileRecordDao
import com.example.webdavsync.data.local.dao.SyncLogDao
import com.example.webdavsync.data.local.dao.SyncTaskDao
import com.example.webdavsync.data.prefs.CredentialStore
import com.example.webdavsync.data.storage.NetworkChecker
import com.example.webdavsync.data.storage.SafStorageHelper
import com.example.webdavsync.domain.SyncEngine

/**
 * 轻量手动依赖容器。在 [com.example.webdavsync.WebDavSyncApp] 中初始化为单例,
 * UI / Service 通过 applicationContext 取用。不引入 Hilt 以保持轻量与快速构建。
 */
class AppContainer(private val context: Context) {

    val database: AppDatabase by lazy { AppDatabase.get(context) }
    val syncTaskDao: SyncTaskDao by lazy { database.syncTaskDao() }
    val fileRecordDao: FileRecordDao by lazy { database.fileRecordDao() }
    val syncLogDao: SyncLogDao by lazy { database.syncLogDao() }

    val credentialStore: CredentialStore by lazy { CredentialStore(context) }
    val safStorage: SafStorageHelper by lazy { SafStorageHelper(context) }
    val networkChecker: NetworkChecker by lazy { NetworkChecker(context) }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(fileRecordDao, safStorage, credentialStore, networkChecker)
    }
}
