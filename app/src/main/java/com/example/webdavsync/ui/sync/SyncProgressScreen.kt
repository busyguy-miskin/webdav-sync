package com.example.webdavsync.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.webdavsync.domain.model.SyncProgress
import com.example.webdavsync.service.SyncService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncProgressScreen(
    onBack: () -> Unit,
    onCompleted: () -> Unit
) {
    val context = LocalContext.current
    val progress by SyncService.liveProgress.collectAsState()
    val p = progress

    // 同步结束(完成/取消/失败/跳过)后停留 1.5s 自动返回列表
    LaunchedEffect(p.phase) {
        if (p.phase in listOf(
                SyncProgress.Phase.FINISHED,
                SyncProgress.Phase.CANCELLED,
                SyncProgress.Phase.SKIPPED
            )
        ) {
            kotlinx.coroutines.delay(1500)
            onCompleted()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(p.taskName.ifEmpty { "同步中" }) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
            HorizontalDivider(thickness = 1.dp)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(phaseText(p.phase), style = MaterialTheme.typography.titleMedium)

            if (p.phase == SyncProgress.Phase.DOWNLOADING && p.totalFiles > 0) {
                LinearProgressIndicator(
                    progress = { p.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "${p.doneFiles} / ${p.totalFiles} 个文件 · ${formatBytes(p.doneBytes)} / ${formatBytes(p.totalBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (p.phase in listOf(SyncProgress.Phase.LISTING, SyncProgress.Phase.COMPARING, SyncProgress.Phase.IDLE)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (p.currentFile.isNotEmpty()) {
                Text(
                    "当前: ${p.currentFile}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }

            // 统计
            if (p.phase == SyncProgress.Phase.FINISHED || p.downloaded > 0 || p.skipped > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    StatItem("下载", p.downloaded)
                    StatItem("跳过", p.skipped)
                    StatItem("远程已变更", p.remoteChanged)
                    StatItem("失败", p.failed)
                }
            }

            if (p.message.isNotEmpty()) {
                Text(p.message, style = MaterialTheme.typography.bodyMedium,
                    color = if (p.failed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
            }

            if (p.errors.isNotEmpty()) {
                Text("失败详情:", style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(p.errors) { e ->
                        Text("• $e", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }

            if (p.phase == SyncProgress.Phase.DOWNLOADING ||
                p.phase == SyncProgress.Phase.LISTING ||
                p.phase == SyncProgress.Phase.COMPARING
            ) {
                Button(
                    onClick = { SyncService.cancel(context) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("取消同步") }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("$value", style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun phaseText(phase: SyncProgress.Phase) = when (phase) {
    SyncProgress.Phase.IDLE -> "准备同步…"
    SyncProgress.Phase.LISTING -> "正在获取远程文件清单…"
    SyncProgress.Phase.COMPARING -> "正在比对文件…"
    SyncProgress.Phase.DOWNLOADING -> "正在下载…"
    SyncProgress.Phase.FINISHED -> "同步完成"
    SyncProgress.Phase.CANCELLED -> "已取消"
    SyncProgress.Phase.FAILED -> "同步失败"
    SyncProgress.Phase.SKIPPED -> "已跳过"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
