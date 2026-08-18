package com.example.webdavsync.ui.task

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.webdavsync.data.local.entity.SyncTask
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    taskId: Long,
    existing: SyncTask?,
    onSaved: () -> Unit,
    onBack: () -> Unit,
    viewModel: TaskViewModel = viewModel()
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var serverUrl by remember { mutableStateOf(existing?.serverUrl ?: "https://") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf("") }
    var remotePath by remember { mutableStateOf(existing?.remotePath ?: "/") }
    var localTreeUri by remember { mutableStateOf(existing?.localTreeUri ?: "") }
    var overwrite by remember { mutableStateOf(existing?.overwrite ?: false) }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var wifiOnly by remember { mutableStateOf(existing?.wifiOnly ?: false) }
    var trustAllCerts by remember { mutableStateOf(existing?.trustAllCerts ?: false) }

    // existing 由 Room 异步加载,进入页面首帧为 null,字段状态会被 remember 锁定在空默认值。
    // 这里在数据首次到达时把旧配置回填进表单(仅编辑已有任务 taskId>0 时需要)。
    if (taskId > 0) {
        LaunchedEffect(existing?.id) {
            val t = existing ?: return@LaunchedEffect
            if (t.id <= 0) return@LaunchedEffect
            name = t.name
            serverUrl = t.serverUrl
            username = t.username
            // 密码单独加密存储,这里保持留空(留空不改)
            remotePath = t.remotePath
            localTreeUri = t.localTreeUri
            overwrite = t.overwrite
            enabled = t.enabled
            wifiOnly = t.wifiOnly
            trustAllCerts = t.trustAllCerts
        }
    }

    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var showRemotePicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // SAF 目录选择器
    val dirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.takeSafPermission(uri)
            localTreeUri = uri.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (taskId > 0) "编辑任务" else "新建任务") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text("任务名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = serverUrl, onValueChange = { serverUrl = it },
                label = { Text("服务器地址") },
                placeholder = { Text("https://dav.example.com/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            if (serverUrl.startsWith("http://", ignoreCase = true)) {
                Text(
                    "⚠ 明文 HTTP:密码与文件内容不加密传输,请仅在可信内网使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("用户名") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text(if (taskId > 0) "密码(留空不改)" else "密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = remotePath, onValueChange = { remotePath = it },
                    label = { Text("远程目录路径") },
                    placeholder = { Text("/photos") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = { showRemotePicker = true },
                    enabled = serverUrl.isNotBlank() && serverUrl != "https://" &&
                        (username.isNotBlank() || password.isNotEmpty() || taskId > 0)
                ) { Text("浏览") }
            }

            // 本地目录选择
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Folder, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Text("本地存储目录", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (localTreeUri.isEmpty()) "未选择" else shortenUri(localTreeUri),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                OutlinedButton(onClick = { dirPicker.launch(null) }) { Text("选择目录") }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("更新已变更文件", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭(默认):只下载新文件,不覆盖本地文件\n开启:远程文件变化时覆盖本地",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = overwrite, onCheckedChange = { overwrite = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("仅 Wi-Fi 同步", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "开启后,在移动数据网络下不同步(避免消耗流量)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("信任所有证书", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "内网/自签名 HTTPS 服务器连接失败时开启。注意:会降低安全性,请勿用于公网未知服务器",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = trustAllCerts, onCheckedChange = { trustAllCerts = it })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用任务", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭后不参与「全部同步」,列表中以灰色显示",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }

            // 测试连接
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        testing = true; testResult = null
                        scope.launch {
                            // 编辑现有任务时,密码留空则用已存密码测试
                            val pwd = password.ifEmpty {
                                if (taskId > 0) existing?.let { viewModel.getPasswordForTest(it.id) } ?: "" else ""
                            }
                            val res = viewModel.testConnection(serverUrl, username, pwd, remotePath, trustAllCerts)
                            testing = false
                            testResult = if (res.isSuccess) "✓ 连接成功" else "✗ ${res.exceptionOrNull()?.message}"
                        }
                    },
                    enabled = !testing
                ) { Text("测试连接") }
                if (testing) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            testResult?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (it.startsWith("✓")) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(4.dp))

            Button(
                onClick = {
                    saving = true
                    viewModel.saveTask(
                        taskId = taskId,
                        name = name.trim(),
                        serverUrl = serverUrl.trim(),
                        username = username.trim(),
                        password = password,
                        remotePath = remotePath.trim(),
                        localTreeUri = localTreeUri,
                        overwrite = overwrite,
                        enabled = enabled,
                        wifiOnly = wifiOnly,
                        trustAllCerts = trustAllCerts
                    ) { saving = false; onSaved() }
                },
                enabled = !saving && name.isNotBlank() && serverUrl.isNotBlank() && localTreeUri.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (taskId > 0) "保存修改" else "创建任务")
                }
            }
        }

        // 远程目录浏览器:连接 WebDAV 选定同步目录
        if (showRemotePicker) {
            RemoteFolderPicker(
                serverUrl = serverUrl.trim(),
                username = username.trim(),
                password = password,
                taskId = taskId,
                initialPath = remotePath,
                trustAllCerts = trustAllCerts,
                viewModel = viewModel,
                onDismiss = { showRemotePicker = false },
                onSelected = { selected ->
                    remotePath = selected
                    showRemotePicker = false
                }
            )
        }
    }
}

private fun shortenUri(uri: String): String {
    val decoded = runCatching { android.net.Uri.decode(uri) }.getOrDefault(uri)
    return "…/" + decoded.substringAfterLast('/').ifEmpty { decoded }
}
