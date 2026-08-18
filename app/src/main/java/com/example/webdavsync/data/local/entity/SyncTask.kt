package com.example.webdavsync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 一个同步任务:把远程 WebDAV 目录下载到本地 SAF 目录。
 *
 * @param id           主键
 * @param name         任务名称(用户可读)
 * @param serverUrl    WebDAV 服务器根地址,例如 https://dav.example.com/
 * @param username     用户名(明文,与密码分开;密码存加密 Prefs)
 * @param remotePath   要同步的远程目录相对路径,例如 /photos
 * @param localTreeUri SAF 授权的本地目录 treeUri
 * @param overwrite    是否覆盖本地已存在且远程已变更的文件。false=只增不删不覆盖(默认)
 * @param enabled      任务是否启用。关闭后不参与"全部同步",列表上以灰色显示
 * @param wifiOnly     是否仅在 Wi-Fi 下同步。开启后在移动网络下同步会被跳过并给出提示
 * @param trustAllCerts 是否信任所有证书(用于内网自签名 HTTPS)。默认关闭
 * @param lastSyncTime 上次成功同步的时间戳(毫秒),0 表示从未同步
 * @param lastSyncResult 上次同步的简要结果,例如 "完成 12,跳过 3,失败 0"
 */
@Entity(tableName = "sync_tasks")
data class SyncTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val serverUrl: String,
    val username: String,
    val remotePath: String,
    val localTreeUri: String,
    val overwrite: Boolean = false,
    val enabled: Boolean = true,
    val wifiOnly: Boolean = false,
    val trustAllCerts: Boolean = false,
    val lastSyncTime: Long = 0L,
    val lastSyncResult: String = ""
)
