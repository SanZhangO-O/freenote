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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.sync.MIN_PASSPHRASE_LENGTH
import com.zqr.freenote.data.sync.ServerUrl
import com.zqr.freenote.data.sync.SyncManager
import com.zqr.freenote.data.sync.SyncPeer
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
    // 文本字段用本地状态、失焦/键盘完成后才写入设置：避免边打字边切换同步账户、
    // 边打字边重启 P2P 服务（改到一半立即生效是误操作的常见来源）
    var deviceNameText by remember { mutableStateOf(config.deviceName) }
    var passphraseText by remember { mutableStateOf(config.passphrase) }
    var accountText by remember { mutableStateOf(config.p2pAccount) }
    var password by remember { mutableStateOf("") }
    var busBusy by remember { mutableStateOf(false) }
    var manualAddress by remember { mutableStateOf("") }
    var intervalText by remember { mutableStateOf(config.syncIntervalMin.toString()) }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showGeneratePassphraseDialog by remember { mutableStateOf(false) }
    var showGenerateAccountDialog by remember { mutableStateOf(false) }
    var showLogoutDialog by remember { mutableStateOf(false) }
    var peerToRemove by remember { mutableStateOf<SyncPeer?>(null) }
    val focusManager = LocalFocusManager.current
    val textFieldOptions = KeyboardOptions(imeAction = ImeAction.Done)
    val commitOnDone = KeyboardActions(onDone = { focusManager.clearFocus() })
    var portText by remember {
        mutableStateOf(if (config.syncPort > 0) config.syncPort.toString() else "")
    }

    fun commitDeviceName() {
        val value = deviceNameText.trim()
        if (value != config.deviceName && value.isNotEmpty()) {
            SyncSettings.setDeviceName(value)
            SyncManager.announceSoon()
        }
    }

    fun commitPassphrase() {
        val value = passphraseText.trim()
        if (value != config.passphrase) {
            if (value.isNotEmpty() && value.length < MIN_PASSPHRASE_LENGTH) {
                // 太短的口令可被局域网被动监听者离线穷举：拒绝写入设置
                onMessage("同步口令至少 $MIN_PASSPHRASE_LENGTH 位（建议使用「生成随机口令」）")
                return
            }
            SyncSettings.setPassphrase(value)
            SyncManager.onConfigChanged()
        }
    }

    fun commitAccount() {
        val value = accountText.trim()
        if (value != config.p2pAccount) {
            SyncSettings.setP2pAccount(value)
            SyncManager.onConfigChanged()
        }
    }

    fun commitServerFields() {
        val url = serverUrl.trim().trimEnd('/')
        val user = username.trim()
        if (ServerUrl.normalize(url) != ServerUrl.normalize(config.serverUrl) ||
            user != config.username
        ) {
            SyncSettings.setServerUrl(url)
            SyncSettings.setUsername(user)
            SyncManager.onConfigChanged()
        }
    }

    fun commitPort() {
        val value = (portText.toIntOrNull() ?: 0).coerceIn(0, 65535)
        if (value != config.syncPort) {
            SyncSettings.setSyncPort(value)
            SyncManager.onConfigChanged()
        }
    }

    // 离开设置页（返回/旋转）时把仍聚焦、尚未失焦的文本框内容落盘：
    // 改完直接返回不应丢失；各 commit 内部有「无变化不写」保护，可安全重复调用
    DisposableEffect(Unit) {
        onDispose {
            commitDeviceName()
            commitPassphrase()
            commitAccount()
            commitServerFields()
            commitPort()
        }
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
            value = deviceNameText,
            onValueChange = { deviceNameText = it },
            label = { Text("设备名称") },
            singleLine = true,
            enabled = p2pReady,
            keyboardOptions = textFieldOptions,
            keyboardActions = commitOnDone,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) commitDeviceName() }
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = passphraseText,
            onValueChange = { passphraseText = it },
            label = { Text("同步口令") },
            placeholder = { Text("同一口令的设备才会互相发现并同步") },
            supportingText = { Text("至少 $MIN_PASSPHRASE_LENGTH 位；建议使用「生成随机口令」") },
            singleLine = true,
            enabled = p2pReady,
            keyboardOptions = textFieldOptions,
            keyboardActions = commitOnDone,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) commitPassphrase() }
        )
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                // 更换口令=更换全组共享凭据：先确认，误触会让其他设备立即失联
                showGeneratePassphraseDialog = true
            }, enabled = p2pReady) { Text("生成随机口令") }
            TextButton(onClick = {
                if (passphraseText.isBlank()) {
                    onMessage("口令为空，无法复制")
                } else {
                    clipboard.setText(AnnotatedString(passphraseText.trim()))
                    onMessage("同步口令已复制到剪贴板")
                }
            }, enabled = p2pReady) { Text("复制") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = accountText,
            onValueChange = { accountText = it },
            label = { Text("离线账户") },
            placeholder = { Text("未登录服务器时用于区分同步组（两端需一致）") },
            supportingText = { Text("生成 UUID 复制到其他设备即可同组，无需注册服务器账号") },
            singleLine = true,
            enabled = p2pReady,
            keyboardOptions = textFieldOptions,
            keyboardActions = commitOnDone,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) commitAccount() }
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                // 更换离线账户=切换同步组：先确认，避免误触后设备列表被重置
                showGenerateAccountDialog = true
            }, enabled = p2pReady) { Text("生成 UUID") }
            TextButton(onClick = {
                if (accountText.isBlank()) {
                    onMessage("离线账户为空，无法复制")
                } else {
                    clipboard.setText(AnnotatedString(accountText.trim()))
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
                    // 只更新本地文本：逐个数字就重启监听会让端口短暂处于错误值
                    portText = text.filter { it.isDigit() }.take(5)
                },
                suffix = { Text("0=自动") },
                singleLine = true,
                enabled = p2pReady,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .width(150.dp)
                    .onFocusChanged { if (!it.isFocused) commitPort() }
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
                    TextButton(onClick = { peerToRemove = peer }, enabled = p2pReady) { Text("移除") }
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
            onValueChange = { serverUrl = it },
            label = { Text("服务器地址") },
            placeholder = { Text("http://192.168.1.8:8000") },
            singleLine = true,
            keyboardOptions = textFieldOptions,
            keyboardActions = commitOnDone,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) commitServerFields() }
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                keyboardOptions = textFieldOptions,
                keyboardActions = commitOnDone,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { if (!it.isFocused) commitServerFields() }
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
                    onClick = { showLogoutDialog = true }
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
        if (showLogoutDialog) {
            AlertDialog(
                onDismissRequest = { showLogoutDialog = false },
                title = { Text("退出登录") },
                text = { Text("退出后本机将停止服务器自动同步，需要重新登录才能继续（本机便签数据保留）。") },
                confirmButton = {
                    TextButton(onClick = {
                        showLogoutDialog = false
                        scope.launch {
                            SyncManager.logout()
                            onMessage("已退出登录")
                        }
                    }) { Text("退出") }
                },
                dismissButton = {
                    TextButton(onClick = { showLogoutDialog = false }) { Text("取消") }
                }
            )
        }
        if (showGeneratePassphraseDialog) {
            AlertDialog(
                onDismissRequest = { showGeneratePassphraseDialog = false },
                title = { Text("生成随机口令？") },
                text = { Text("将立即替换当前同步口令：其他设备必须改用新口令，否则无法再发现/同步本机。") },
                confirmButton = {
                    TextButton(onClick = {
                        showGeneratePassphraseDialog = false
                        passphraseText = generatePassphrase()
                        SyncSettings.setPassphrase(passphraseText)
                        SyncManager.onConfigChanged()
                        onMessage("已生成新口令，请在另一台设备上填写相同口令")
                    }) { Text("生成") }
                },
                dismissButton = {
                    TextButton(onClick = { showGeneratePassphraseDialog = false }) { Text("取消") }
                }
            )
        }
        if (showGenerateAccountDialog) {
            AlertDialog(
                onDismissRequest = { showGenerateAccountDialog = false },
                title = { Text("生成 UUID 离线账户？") },
                text = { Text("将立即替换当前离线账户：其他设备需要填写相同账户才能同组同步。") },
                confirmButton = {
                    TextButton(onClick = {
                        showGenerateAccountDialog = false
                        accountText = UUID.randomUUID().toString()
                        SyncSettings.setP2pAccount(accountText)
                        SyncManager.onConfigChanged()
                        onMessage("已生成 UUID 离线账户：复制到其他设备即可同组（无需注册服务器账号）")
                    }) { Text("生成") }
                },
                dismissButton = {
                    TextButton(onClick = { showGenerateAccountDialog = false }) { Text("取消") }
                }
            )
        }
        peerToRemove?.let { peer ->
            AlertDialog(
                onDismissRequest = { peerToRemove = null },
                title = { Text("移除设备？") },
                text = { Text("从设备列表中移除「${peer.displayName()}」？（之后可重新发现或手动添加）") },
                confirmButton = {
                    TextButton(onClick = {
                        peerToRemove = null
                        SyncManager.forgetPeer(peer.id)
                        onMessage("已移除设备「${peer.displayName()}」")
                    }) { Text("移除", color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { peerToRemove = null }) { Text("取消") }
                }
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
