package com.zqr.freenote.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.sync.ServerUrl
import com.zqr.freenote.data.sync.SyncManager
import com.zqr.freenote.data.sync.SyncSettings
import com.zqr.freenote.util.TimeFormat
import kotlinx.coroutines.launch
import java.util.UUID

private const val PASSPHRASE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

private fun generatePassphrase(): String =
    (1..4).joinToString("-") { (1..4).map { PASSPHRASE_ALPHABET.random() }.joinToString("") }

/** 当前生效的同步账户：服务器账号优先，其次离线账户。 */
private fun accountSummary(serverUrl: String, username: String, localAccount: String): String {
    val url = serverUrl.trim().trimEnd('/')
    val user = username.trim()
    return when {
        url.isNotEmpty() && user.isNotEmpty() -> "同步账户：服务器账号 $user @ $url"
        localAccount.trim().isNotEmpty() -> "同步账户：离线账户 ${localAccount.trim()}"
        else -> "同步账户：未设置（同口令设备即同组；建议登录服务器或填写离线账户）"
    }
}

/**
 * 同步设置区（P2P + 服务器），由「设置」页折叠在提醒之后。
 * 与 FreeNote Windows 的设置面板对应。
 */
@Composable
fun SyncSection(onMessage: (String) -> Unit) {
    val config by SyncSettings.config.collectAsState()
    val status by SyncManager.status.collectAsState()
    val peers by SyncManager.peers.collectAsState()
    val syncing by SyncManager.syncing.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var serverUrl by remember { mutableStateOf(config.serverUrl) }
    var username by remember { mutableStateOf(config.username) }
    var password by remember { mutableStateOf("") }
    var busBusy by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf(config.syncIntervalMin.toString()) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var portText by remember {
        mutableStateOf(if (config.syncPort > 0) config.syncPort.toString() else "")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (syncing) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Button(
                onClick = { scope.launch { onMessage(SyncManager.syncNow()) } },
                enabled = !syncing
            ) {
                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("立即同步")
            }
        }

        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------------------ P2P
        Text("P2P 同步（局域网直连，无需服务器）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("启用 P2P 同步")
            Switch(
                checked = config.p2pEnabled,
                onCheckedChange = {
                    SyncSettings.setP2pEnabled(it)
                    SyncManager.onConfigChanged()
                }
            )
        }
        Text(
            "网络功能默认关闭：开启并设置口令后，才会监听端口并参与局域网发现/同步",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        val p2pReady = config.p2pEnabled
        OutlinedTextField(
            value = config.deviceName,
            onValueChange = {
                SyncSettings.setDeviceName(it)
                SyncManager.announceSoon()
            },
            label = { Text("设备名称") },
            singleLine = true,
            enabled = p2pReady,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = config.passphrase,
            onValueChange = {
                SyncSettings.setPassphrase(it)
                SyncManager.onConfigChanged()
            },
            label = { Text("同步口令") },
            placeholder = { Text("同一口令的设备才会互相发现并同步") },
            singleLine = true,
            enabled = p2pReady,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                SyncSettings.setPassphrase(generatePassphrase())
                SyncManager.onConfigChanged()
                onMessage("已生成新口令，请在另一台设备上填写相同口令")
            }, enabled = p2pReady) { Text("生成随机口令") }
            TextButton(onClick = {
                if (config.passphrase.isBlank()) {
                    onMessage("口令为空，无法复制")
                } else {
                    clipboard.setText(AnnotatedString(config.passphrase))
                    onMessage("同步口令已复制到剪贴板")
                }
            }, enabled = p2pReady) { Text("复制") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = config.p2pAccount,
            onValueChange = {
                SyncSettings.setP2pAccount(it)
                SyncManager.onConfigChanged()
            },
            label = { Text("离线账户") },
            placeholder = { Text("未登录服务器时用于区分同步组（两端需一致）") },
            supportingText = { Text("生成 UUID 复制到其他设备即可同组，无需注册服务器账号") },
            singleLine = true,
            enabled = p2pReady,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val account = UUID.randomUUID().toString()
                SyncSettings.setP2pAccount(account)
                SyncManager.onConfigChanged()
                onMessage("已生成 UUID 离线账户：复制到其他设备即可同组（无需注册服务器账号）")
            }, enabled = p2pReady) { Text("生成 UUID") }
            TextButton(onClick = {
                if (config.p2pAccount.isBlank()) {
                    onMessage("离线账户为空，无法复制")
                } else {
                    clipboard.setText(AnnotatedString(config.p2pAccount))
                    onMessage("离线账户已复制到剪贴板")
                }
            }, enabled = p2pReady) { Text("复制") }
        }
        Text(
            accountSummary(config.serverUrl, config.username, config.p2pAccount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("局域网自动发现")
            Switch(
                checked = config.discoveryEnabled,
                onCheckedChange = {
                    SyncSettings.setDiscoveryEnabled(it)
                    SyncManager.onConfigChanged()
                },
                enabled = p2pReady
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("自动同步")
            Switch(
                checked = config.p2pAutoSync,
                onCheckedChange = { SyncSettings.setP2pAutoSync(it) },
                enabled = p2pReady
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("同步间隔", modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { text ->
                    intervalText = text.filter { it.isDigit() }.take(4)
                    intervalText.toIntOrNull()?.let {
                        SyncSettings.setSyncIntervalMin(it)
                        SyncManager.onIntervalChanged()
                    }
                },
                suffix = { Text("分钟") },
                singleLine = true,
                enabled = p2pReady,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(130.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("同步端口", modifier = Modifier.weight(1f))
            OutlinedTextField(
                value = portText,
                onValueChange = { text ->
                    portText = text.filter { it.isDigit() }.take(5)
                    val value = portText.toIntOrNull() ?: 0
                    if (value <= 65535) {
                        SyncSettings.setSyncPort(value)
                        SyncManager.onConfigChanged()
                    }
                },
                suffix = { Text("0=自动") },
                singleLine = true,
                enabled = p2pReady,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp)
            )
        }
        Text(
            if (SyncManager.p2pRunning) {
                "局域网设备通过此端口直连本机（当前监听 ${SyncManager.p2pPort}）"
            } else {
                "开启「启用 P2P 同步」并设置口令后，局域网设备才能通过此端口直连本机"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text("已发现/已添加的设备", style = MaterialTheme.typography.labelMedium)
        if (peers.isEmpty()) {
            Text(
                "暂无设备：等待局域网发现，或在下方手动添加地址",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            peers.forEach { peer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(peer.displayName(), style = MaterialTheme.typography.bodyMedium)
                        val parts = mutableListOf(peer.addressText())
                        if (peer.lastSyncAt > 0) {
                            parts.add("上次同步 ${TimeFormat.noteTime(peer.lastSyncAt)}")
                        }
                        if (peer.lastStatus.isNotBlank()) parts.add(peer.lastStatus)
                        Text(
                            parts.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        scope.launch { onMessage(SyncManager.syncPeer(peer.id)) }
                    }, enabled = p2pReady) { Text("同步") }
                    TextButton(onClick = { SyncManager.forgetPeer(peer.id) }, enabled = p2pReady) { Text("移除") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manualAddress,
                onValueChange = { manualAddress = it },
                label = { Text("手动添加") },
                placeholder = { Text("192.168.1.8:8765") },
                singleLine = true,
                enabled = p2pReady,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = {
                scope.launch {
                    val (ok, message) = SyncManager.addManualPeer(manualAddress)
                    onMessage(message)
                    if (ok) manualAddress = ""
                }
            }, enabled = p2pReady) { Text("添加") }
        }

        Spacer(Modifier.height(16.dp))

        // ------------------------------------------------------------ 服务器
        Text("服务器同步（经 FreeNote 服务端，可跨网络）", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = {
                serverUrl = it
                SyncSettings.setServerUrl(it)
                SyncManager.onConfigChanged()
            },
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.8:8000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = username,
                onValueChange = {
                    username = it
                    SyncSettings.setUsername(it)
                    SyncManager.onConfigChanged()
                },
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                placeholder = { Text("不保存") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (config.serverReady) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            SyncManager.logout()
                            onMessage("已退出登录")
                        }
                    }
                ) { Text("退出登录") }
                OutlinedButton(onClick = { showChangePasswordDialog = true }) { Text("修改密码") }
                OutlinedButton(onClick = { showDeleteAccountDialog = true }) { Text("注销账号") }
            } else {
                Button(
                    enabled = !busBusy,
                    onClick = {
                        scope.launch {
                            busBusy = true
                            val result = SyncManager.register(ServerUrl.normalize(serverUrl), username, password)
                            busBusy = false
                            onMessage(result.message)
                            if (result.ok) password = ""
                        }
                    }
                ) { Text("注册并登录") }
                OutlinedButton(
                    enabled = !busBusy,
                    onClick = {
                        scope.launch {
                            busBusy = true
                            val result = SyncManager.login(ServerUrl.normalize(serverUrl), username, password)
                            busBusy = false
                            onMessage(result.message)
                            if (result.ok) password = ""
                        }
                    }
                ) { Text("登录") }
            }
            OutlinedButton(
                enabled = !busBusy,
                onClick = {
                    scope.launch {
                        busBusy = true
                        val (ok, message) = SyncManager.testServer(ServerUrl.normalize(serverUrl))
                        busBusy = false
                        onMessage(if (ok) message else "连接失败：$message")
                    }
                }
            ) { Text("测试连接") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("跳过 HTTPS 证书校验")
            Switch(
                checked = config.serverAllowSelfSigned,
                onCheckedChange = { SyncSettings.setServerAllowSelfSigned(it) }
            )
        }
        Text(
            "仅 HTTPS + 自签名证书的自建服务器需要开启；传输仍会加密，但不验证服务器身份。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("自动同步")
            Switch(
                checked = config.serverAutoSync,
                onCheckedChange = { SyncSettings.setServerAutoSync(it) }
            )
        }
        Text(
            if (config.serverReady) {
                "已登录：${config.username} @ ${config.serverUrl}"
            } else {
                "未登录：填写地址与账号后点「注册并登录」或「登录」（与 Android / Windows 版共用同一账号）"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (showChangePasswordDialog) {
            ChangePasswordDialog(
                busy = busBusy,
                onConfirm = { old, new ->
                    scope.launch {
                        busBusy = true
                        val result = SyncManager.changePassword(old, new)
                        busBusy = false
                        if (result.ok) showChangePasswordDialog = false
                        onMessage(result.message)
                    }
                },
                onDismiss = { showChangePasswordDialog = false }
            )
        }
        if (showDeleteAccountDialog) {
            DeleteAccountDialog(
                busy = busBusy,
                onConfirm = { pwd ->
                    scope.launch {
                        busBusy = true
                        val result = SyncManager.deleteAccount(pwd)
                        busBusy = false
                        if (result.ok) showDeleteAccountDialog = false
                        onMessage(result.message)
                    }
                },
                onDismiss = { showDeleteAccountDialog = false }
            )
        }
    }
}

/** 修改服务器账号密码（旧密码 + 新密码两次输入）。 */
@Composable
private fun ChangePasswordDialog(
    busy: Boolean,
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    val mismatch = confirmPassword.isNotEmpty() && newPassword != confirmPassword
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改密码") },
        text = {
            Column {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("旧密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("新密码（至少 6 位）") },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = { Text("再次输入新密码") },
                    singleLine = true,
                    isError = mismatch,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (mismatch) {
                    Text(
                        "两次输入的新密码不一致",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    "修改成功后，其他设备上的登录会失效，需要重新登录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && oldPassword.isNotEmpty() &&
                    newPassword.length >= 6 && newPassword == confirmPassword,
                onClick = { onConfirm(oldPassword, newPassword) }
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}

/** 注销服务器账号：删除服务端全部数据（本机数据保留），需输入密码确认。 */
@Composable
private fun DeleteAccountDialog(
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("注销账号") },
        text = {
            Column {
                Text("注销后将删除服务端上的全部便签与图片，且无法恢复。本机便签数据会保留。")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("输入账号密码以确认") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && password.isNotEmpty(),
                onClick = { onConfirm(password) }
            ) { Text("注销") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        }
    )
}
