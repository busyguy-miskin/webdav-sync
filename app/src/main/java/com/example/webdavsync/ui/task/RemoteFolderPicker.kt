package com.example.webdavsync.ui.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.webdavsync.data.webdav.RemoteResource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 连接到 WebDAV 服务器浏览远程目录,选定一个目录后通过 [onSelected] 回调返回。
 *
 * 用户在任务编辑页点「浏览」打开本组件;内部维护当前路径、加载/错误状态,
 * 支持进入子目录、返回上级、直接把当前目录作为同步根。
 *
 * @param serverUrl  当前表单中的服务器地址
 * @param username   当前表单中的用户名
 * @param password   当前表单中的密码(为空且 taskId>0 时,ViewModel 会回退到已存密码)
 * @param taskId     编辑现有任务时传入用于密码回退,新建传 0
 * @param initialPath 打开时的初始远程路径
 * @param trustAllCerts 是否信任所有证书(与任务设置一致)
 * @param viewModel  共享的任务 ViewModel
 * @param onDismiss  关闭
 * @param onSelected 选定目录后回调,参数为绝对远程路径(如 "/photos/2024")
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteFolderPicker(
    serverUrl: String,
    username: String,
    password: String,
    taskId: Long,
    initialPath: String,
    trustAllCerts: Boolean,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // 当前正在浏览的远程路径(始终以 / 开头)
    var currentPath by remember { mutableStateOf(normalizePath(initialPath)) }
    var entries by remember { mutableStateOf<List<RemoteResource>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // 记录最近一次加载请求,避免快速点击时旧请求覆盖新结果
    var loadJob by remember { mutableStateOf<Job?>(null) }

    /** 拉取 [path] 的直接子项。 */
    fun load(path: String) {
        loadJob?.cancel()
        loadJob = scope.launch {
            loading = true
            error = null
            val res = viewModel.browseDirectory(serverUrl, username, password, taskId, path, trustAllCerts)
            loading = false
            res.fold(
                onSuccess = { entries = it },
                onFailure = { e -> error = e.message ?: "加载目录失败" }
            )
        }
    }

    // 打开即加载初始路径;路径变化也重新加载
    LaunchedEffect(currentPath) { load(currentPath) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            // 标题栏:返回上级 + 当前路径 + 选择当前目录
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        // 返回上级;到根则停留
                        currentPath = parentOf(currentPath)
                    },
                    enabled = !loading
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回上级")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("选择远程目录", style = MaterialTheme.typography.titleMedium)
                    Text(
                        currentPath.ifEmpty { "/" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                TextButton(
                    onClick = { onSelected(currentPath.ifEmpty { "/" }) },
                    enabled = !loading && error == null
                ) { Text("选择此目录") }
            }

            Spacer(Modifier.height(8.dp))

            when {
                loading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                error != null -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().height(200.dp).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            error!!,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { load(currentPath) }) { Text("重试") }
                    }
                }
                entries.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "(空目录)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().height(420.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(entries, key = { it.relativePath }) { entry ->
                            DirEntryRow(
                                entry = entry,
                                onClick = {
                                    if (entry.isDirectory) {
                                        // 进入子目录
                                        currentPath = joinPath(currentPath, entry.name)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun DirEntryRow(entry: RemoteResource, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!entry.isDirectory) {
                Text(
                    formatSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (entry.isDirectory) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = "进入",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** 把任意路径规整成以 / 开头、不以 / 结尾(根目录为空串)的形式。 */
private fun normalizePath(path: String): String {
    val p = path.trim()
    if (p.isEmpty() || p == "/") return ""
    val withSlash = if (p.startsWith("/")) p else "/$p"
    return withSlash.trimEnd('/')
}

/** 计算上级目录路径(根目录的上级仍是根)。 */
private fun parentOf(path: String): String {
    val p = normalizePath(path)
    if (p.isEmpty()) return ""
    val idx = p.trimStart('/').lastIndexOf('/')
    return if (idx < 0) "" else "/" + p.trimStart('/').substring(0, idx)
}

/** 把当前路径与子目录名拼成绝对路径。 */
private fun joinPath(current: String, name: String): String {
    val c = normalizePath(current)
    val trimmed = name.trim().trimEnd('/')
    return if (c.isEmpty()) "/$trimmed" else "$c/$trimmed"
}

private fun formatSize(bytes: Long): String {
    if (bytes < 0) return "-"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format("%.1f %s", v, units[i])
}
