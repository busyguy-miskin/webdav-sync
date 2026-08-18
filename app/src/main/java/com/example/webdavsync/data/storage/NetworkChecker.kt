package com.example.webdavsync.data.storage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 网络状态检查,用于"仅 Wi-Fi 同步"需求。
 *
 * 注意:仅查询当前网络类型,不做后台网络轮询(避免额外权限与电量开销)。
 * 检查的是活跃网络是否为 Wi-Fi(或以太网,通常也是不计流量的连接)。
 */
class NetworkChecker(private val context: Context) {

    /** 设备当前是否有可用网络连接。 */
    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * 当前是否通过 Wi-Fi(或以太网)联网。
     * 用于 wifiOnly 任务:移动数据 / 计量网络下应跳过同步。
     */
    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
