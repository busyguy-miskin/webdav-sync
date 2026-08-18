package com.example.webdavsync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.example.webdavsync.ui.AppRoot
import com.example.webdavsync.ui.theme.WebDavSyncTheme

class MainActivity : ComponentActivity() {

    // Android 13+ 需运行时申请通知权限(前台服务通知)
    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 结果不影响启动;无权限时通知不显示,但同步仍可在前台运行 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            WebDavSyncTheme {
                AppRoot()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
