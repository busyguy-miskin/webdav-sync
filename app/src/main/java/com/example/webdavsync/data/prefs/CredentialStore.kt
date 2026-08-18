package com.example.webdavsync.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 加密存储各任务的 WebDAV 密码(用户名与其它配置存在 Room,仅密码加密落盘)。
 * 基于 EncryptedSharedPreferences(AES-GCM 256 + AES-SIV-CMAC256)。
 *
 * key 为 "task_{id}_password"。
 *
 * 换机恢复等场景下备份文件被还原但 Android Keystore 密钥不在,解密会抛异常:
 * 此时删除损坏的 prefs 文件后重建(丢失已存密码,用户重新输入优于崩溃)。
 */
class CredentialStore(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        runCatching { createPrefs() }.getOrElse {
            runCatching { context.deleteSharedPreferences(FILE_NAME) }
            createPrefs()
        }
    }

    private fun createPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
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

    /** 读取密码;null 表示从未保存过(区别于"空密码")。 */
    fun getPassword(taskId: Long): String? = prefs.getString(key(taskId), null)

    fun deletePassword(taskId: Long) {
        prefs.edit().remove(key(taskId)).apply()
    }

    private fun key(taskId: Long) = "task_${taskId}_password"

    companion object {
        private const val FILE_NAME = "webdav_credentials"
    }
}
