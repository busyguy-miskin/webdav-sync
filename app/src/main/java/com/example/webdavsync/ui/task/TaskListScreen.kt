package com.example.webdavsync.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.webdavsync.data.local.entity.SyncTask
import com.example.webdavsync.service.SyncService
import com.example.webdavsync.ui.theme.EInkCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskListScreen(
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onSync: (Long) -> Unit,
    onSyncAll: (LongArray) -> Unit,
    onOpenHistory: (Long) -> Unit,
    viewModel: TaskViewModel = viewModel()
) {
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    // 待删除任务 → 弹出确认对话框
    var pendingDelete by remember { mutableStateOf<SyncTask?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "WebDAV 同步",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                ),
                actions = {
                    val enabledTasks = tasks.filter { it.enabled }
                    androidx.compose.material3.TextButton(
                        onClick = { onSyncAll(enabledTasks.map { it.id }.toLongArray()) },
                        enabled = enabledTasks.isNotEmpty()
                    ) { Text("全部同步", style = MaterialTheme.typography.labelLarge) }
                }
            )
            HorizontalDivider(thickness = 1.dp)
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewTask,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建任务") }
            )
        }
    ) { padding ->
        if (tasks.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 88.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task = task,
                        onSync = { onSync(task.id) },
                        onEdit = { onEditTask(task.id) },
                        onDelete = { pendingDelete = task },
                        onToggleEnabled = { viewModel.toggleEnabled(task) },
                        onOpenHistory = { onOpenHistory(task.id) }
                    )
                }
            }
        }
    }

    // 删除确认对话框:避免误删任务及其文件记录
    pendingDelete?.let { task ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除任务") },
            text = { Text("确定删除任务「${task.name}」吗?\n将同时清除该任务的同步记录与本地目录授权,但不会删除已下载的文件。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.deleteTask(task)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TaskCard(
    task: SyncTask,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: () -> Unit,
    onOpenHistory: () -> Unit
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // 墨水屏:启用状态用实心/空心圆点明确标识,不依赖色彩
    val stateDot = if (task.enabled) "● " else "○ "
    EInkCard(
        modifier = Modifier.fillMaxWidth(),
        prominent = true
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = stateDot + task.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp, end = 8.dp).weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (task.enabled) MaterialTheme.colorScheme.onSurface else muted
                )
                IconButton(onClick = onOpenHistory) {
                    Icon(Icons.Default.History, contentDescription = "历史")
                }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, contentDescription = "编辑") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "删除") }
            }
            Text(
                "远程: ${task.serverUrl}${task.remotePath}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "本地: ${shortenUri(task.localTreeUri)}",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                "上次同步: ${formatTime(task.lastSyncTime)}",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
            if (task.lastSyncResult.isNotEmpty()) {
                Text(
                    task.lastSyncResult,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted
                )
            }
            // 选项标签:墨水屏用描边 AssistChip,清晰无阴影
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.AssistChip(
                    onClick = {},
                    label = { Text(if (task.overwrite) "覆盖更新" else "只增不删", style = MaterialTheme.typography.labelSmall) },
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                )
                if (task.wifiOnly) {
                    androidx.compose.material3.AssistChip(
                        onClick = {},
                        label = { Text("仅 Wi-Fi", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(start = 6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    )
                }
                if (task.trustAllCerts) {
                    androidx.compose.material3.AssistChip(
                        onClick = {},
                        label = { Text("信任证书", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.padding(start = 6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.OutlinedButton(
                    onClick = onToggleEnabled,
                    modifier = Modifier.weight(1f)
                ) { Text(if (task.enabled) "停用" else "启用") }
                androidx.compose.material3.Button(
                    onClick = onSync,
                    modifier = Modifier.weight(1f),
                    enabled = task.enabled
                ) { Text("同步") }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            "还没有同步任务",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text(
            "点击右下角「新建任务」创建你的第一个 WebDAV 同步",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

private fun formatTime(ts: Long): String =
    if (ts <= 0L) "从未" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/** SAF treeUri 较长,只保留最后一段显示。 */
private fun shortenUri(uri: String): String {
    val decoded = runCatching { android.net.Uri.decode(uri) }.getOrDefault(uri)
    val seg = decoded.substringAfterLast('/').ifEmpty { decoded }
    return "…/$seg"
}
