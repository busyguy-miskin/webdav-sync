package com.example.webdavsync.ui.task

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.webdavsync.WebDavSyncApp
import com.example.webdavsync.data.local.entity.SyncLog
import com.example.webdavsync.ui.theme.EInkCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步历史页:展示某任务最近若干次同步的运行结果(时间、状态、下载/跳过/失败数、摘要)。
 * 让用户能回溯失败原因,而不是只能看到"最后一次"的结果。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHistoryScreen(
    taskId: Long,
    taskName: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val logDao = remember { (context.applicationContext as WebDavSyncApp).container.syncLogDao }
    val logs by logDao.observeRecent(taskId).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("$taskName · 历史") },
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
        if (logs.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("暂无同步记录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "同步运行后,这里会显示历次结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 16.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(logs, key = { it.id }) { log -> LogRow(log) }
            }
        }
    }
}

@Composable
private fun LogRow(log: SyncLog) {
    val (label, symbol, color) = phaseLabel(log.phase)
    EInkCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatTime(log.startedAt),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                // 墨水屏:状态用符号 + 文字双重标识,不依赖颜色
                Text(
                    "$symbol $label",
                    style = MaterialTheme.typography.labelLarge,
                    color = color
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CountText("下载", log.downloaded)
                CountText("跳过", log.skipped)
                if (log.remoteChanged > 0) CountText("远程变更", log.remoteChanged)
                if (log.failed > 0) CountText("失败", log.failed)
                if (log.totalBytes > 0) Text(
                    formatBytes(log.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (log.message.isNotEmpty()) {
                Text(
                    log.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CountText(label: String, value: Int) {
    Text(
        "$label $value",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * 同步状态 → (文案, 符号, 颜色)。
 *
 * 墨水屏上颜色差异极弱,故同时返回符号(✓/✗/–):即便灰度几乎相同,
 * 符号 + 文案仍能让用户一眼区分成功/失败/跳过。
 * 颜色:完成与失败都用最高对比的纯黑(强调),中性状态用次级灰。
 */
@Composable
private fun phaseLabel(phase: String): Triple<String, String, androidx.compose.ui.graphics.Color> {
    val scheme = MaterialTheme.colorScheme
    return when (phase) {
        "FINISHED" -> Triple("完成", "✓", scheme.onSurface)
        "SKIPPED" -> Triple("跳过", "–", scheme.onSurfaceVariant)
        "CANCELLED" -> Triple("已取消", "–", scheme.onSurfaceVariant)
        "FAILED" -> Triple("失败", "✗", scheme.error)
        else -> Triple(phase, "·", scheme.onSurfaceVariant)
    }
}

private fun formatTime(ts: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
