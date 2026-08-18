package com.example.webdavsync.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密存储各任务的 WebDAV 密码(用户名与其它配置存在 Room,仅密码加密落盘)。
 * 基于 EncryptedSharedPreferences(AES-GCM 256 + AES-SIV-CMAC256)。
 *
 * key 为 "task_{id}_password"。
 */
class CredentialStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun savePassword(taskId: Long, password: String) {
        prefs.edit().putString(key(taskId), password).apply()
    }

    fun getPassword(taskId: Long): String =
        prefs.getString(key(taskId), "") ?: ""

    fun deletePassword(taskId: Long) {
        prefs.edit().remove(key(taskId)).apply()
    }

    private fun key(taskId: Long) = "task_${taskId}_password"

    companion object {
        private const val FILE_NAME = "webdav_credentials"
    }
}
