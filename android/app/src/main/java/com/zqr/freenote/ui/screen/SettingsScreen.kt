package com.zqr.freenote.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zqr.freenote.data.DefaultSettingsRepository
import com.zqr.freenote.data.SettingsManager
import com.zqr.freenote.data.SettingsRepository
import com.zqr.freenote.data.ThemeMode
import com.zqr.freenote.ui.common.PinValidation
import com.zqr.freenote.viewmodel.ChangePinResult
import com.zqr.freenote.viewmodel.NoteViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: NoteViewModel,
    onNavigateBack: () -> Unit,
    settingsRepository: SettingsRepository = DefaultSettingsRepository.instance
) {
    val settings by settingsRepository.settings.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showSetPin by remember { mutableStateOf(false) }
    var pinInput by remember { mutableStateOf("") }
    var pinConfirm by remember { mutableStateOf("") }

    var showClearPin by remember { mutableStateOf(false) }
    var clearPinInput by remember { mutableStateOf("") }
    var clearPinError by remember { mutableStateOf(false) }

    var showChangePin by remember { mutableStateOf(false) }
    var changeOldPin by remember { mutableStateOf("") }
    var changeNewPin by remember { mutableStateOf("") }
    var changeNewPinConfirm by remember { mutableStateOf("") }
    var changePinError by remember { mutableStateOf(false) }
    var changingPin by remember { mutableStateOf(false) }
    var lockedCount by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { lockedCount = viewModel.lockedCount() }
    var pendingImportUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var backupRunning by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupRunning = true
            viewModel.exportAll(uri) { result ->
                backupRunning = false
                scope.launch { snackbarHostState.showSnackbar(result.message) }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) pendingImportUri = uri
    }

    val textImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            backupRunning = true
            viewModel.importTextFiles(uris) { result ->
                backupRunning = false
                scope.launch { snackbarHostState.showSnackbar(result.message) }
            }
        }
    }

    val context = LocalContext.current
    val alarmManager = context.getSystemService(android.content.Context.ALARM_SERVICE) as android.app.AlarmManager

    var autoBackupEnabled by remember { mutableStateOf(SettingsManager.autoBackupEnabled()) }
    var autoBackupTree by remember { mutableStateOf(SettingsManager.autoBackupTree()) }
    var webdavUrl by remember { mutableStateOf(SettingsManager.webdavUrl()) }
    var webdavUser by remember { mutableStateOf(SettingsManager.webdavUser()) }
    var webdavPassword by remember { mutableStateOf(SettingsManager.webdavPassword()) }

    val treePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            SettingsManager.setAutoBackupTree(uri.toString())
            SettingsManager.setAutoBackupEnabled(true)
            autoBackupTree = uri.toString()
            autoBackupEnabled = true
            scope.launch { snackbarHostState.showSnackbar("已选择自动备份文件夹") }
        }
    }
    var canExactAlarm by remember { mutableStateOf(computeCanExactAlarm(alarmManager)) }
    var canNotify by remember { mutableStateOf(computeCanNotify(context)) }
    // 从系统授权页返回时刷新授权状态，避免按钮停留在"去授权"
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, alarmManager) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                canExactAlarm = computeCanExactAlarm(alarmManager)
                canNotify = computeCanNotify(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("主题", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ThemeMode.values().forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsRepository.setThemeMode(mode) }
                ) {
                    RadioButton(
                        selected = settings.themeMode == mode,
                        onClick = null
                    )
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "跟随系统"
                            ThemeMode.LIGHT -> "浅色"
                            ThemeMode.DARK -> "深色"
                        }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            // Android 12 以下没有动态取色：不显示无效开关，避免用户以为设置没生效
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("动态取色 (Material You)")
                    Switch(
                        checked = settings.dynamicColor,
                        onCheckedChange = { settingsRepository.setDynamicColor(it) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("隐私", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("便签加密 PIN")
                    Text(
                        if (settings.pinHash != null) "已设置（用于锁定单条便签）" else "未设置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (settings.pinHash != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { showChangePin = true }) { Text("修改 PIN") }
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = { showClearPin = true }) { Text("清除") }
                    }
                } else {
                    Button(onClick = { showSetPin = true }) { Text("设置 PIN") }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("提醒", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("通知权限")
                    Text(
                        if (canNotify) "已开启" else "已关闭，提醒到点不会弹出通知",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canNotify) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
                if (!canNotify) {
                    OutlinedButton(onClick = { openNotificationSettings(context) }) { Text("去开启") }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    Text("精准提醒权限")
                    Text(
                        if (canExactAlarm) "已授予" else "未授予，提醒可能延迟",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canExactAlarm) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
                if (!canExactAlarm) {
                    OutlinedButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            val intent = android.content.Intent(
                                android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
                            ).apply { data = "package:${context.packageName}".toUri() }
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }) { Text("去授权") }
                }
            }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("同步", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SyncSection(onMessage = { message ->
                scope.launch { snackbarHostState.showSnackbar(message) }
            })

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("备份", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = !backupRunning,
                onClick = { exportLauncher.launch("freenote_backup.json") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("导出全部便签 (JSON)") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !backupRunning,
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("从文件导入") }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !backupRunning,
                onClick = { textImportLauncher.launch(arrayOf("text/*", "application/octet-stream", "application/xml", "text/xml")) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("导入 Markdown / 文本") }
            if (backupRunning) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在处理备份…", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "备份（含图片）不依赖系统云备份；系统换机迁移不包含本应用数据，换机请用此处的导出/导入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("自动备份（每 24 小时）")
                    Text(
                        autoBackupTree.ifBlank { "未选择备份文件夹" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Switch(
                    checked = autoBackupEnabled,
                    enabled = !backupRunning,
                    onCheckedChange = { checked ->
                        if (checked && autoBackupTree.isBlank()) {
                            // 没有目录时先让用户选目录（选完自动开启）
                            treePickerLauncher.launch(null)
                        } else {
                            autoBackupEnabled = checked
                            SettingsManager.setAutoBackupEnabled(checked)
                        }
                    }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row {
                OutlinedButton(
                    enabled = !backupRunning,
                    onClick = { treePickerLauncher.launch(null) },
                    modifier = Modifier.weight(1f)
                ) { Text("选择备份文件夹") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    enabled = !backupRunning,
                    onClick = {
                        backupRunning = true
                        viewModel.autoBackupNow { result ->
                            backupRunning = false
                            scope.launch { snackbarHostState.showSnackbar(result.message) }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("立即备份一次") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "WebDAV（可选：备份后自动上传）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = webdavUrl,
                onValueChange = { webdavUrl = it },
                label = { Text("WebDAV 地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = webdavUser,
                onValueChange = { webdavUser = it },
                label = { Text("WebDAV 用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = webdavPassword,
                onValueChange = { webdavPassword = it },
                label = { Text("WebDAV 密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                SettingsManager.setWebdavUrl(webdavUrl)
                SettingsManager.setWebdavUser(webdavUser)
                SettingsManager.setWebdavPassword(webdavPassword)
                scope.launch { snackbarHostState.showSnackbar("WebDAV 设置已保存") }
            }) { Text("保存 WebDAV 设置") }

            Spacer(Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(Modifier.height(20.dp))

            Text("关于", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            val versionName = remember {
                runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.0"
            }
            Text(
                "FreeNote v$versionName — 本地私有便签",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    pendingImportUri?.let { importUri ->
        AlertDialog(
            onDismissRequest = { pendingImportUri = null },
            title = { Text("导入备份") },
            text = {
                Text("将把备份中的便签合并导入：备份里较新的便签会覆盖本机旧版本，本机较新的保持不变。继续？")
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingImportUri = null
                    backupRunning = true
                    viewModel.import(importUri) { result ->
                        backupRunning = false
                        scope.launch { snackbarHostState.showSnackbar(result.message) }
                    }
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingImportUri = null }) { Text("取消") }
            }
        )
    }

    if (showClearPin) {
        AlertDialog(
            onDismissRequest = {
                showClearPin = false
                clearPinInput = ""
                clearPinError = false
            },
            title = { Text("清除 PIN") },
            text = {
                Column {
                    Text(
                        if (lockedCount > 0) {
                            "清除后所有已加密便签将无法解锁（当前 $lockedCount 条）。如需更换 PIN，请使用“修改 PIN”。输入当前 PIN 确认："
                        } else {
                            "输入当前 PIN 确认清除："
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = clearPinInput,
                        onValueChange = {
                            if (PinValidation.isAcceptable(it)) {
                                clearPinInput = it
                                clearPinError = false
                            }
                        },
                        label = { Text("当前 PIN") },
                        singleLine = true,
                        isError = clearPinError,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (clearPinError) {
                        Spacer(Modifier.height(4.dp))
                        Text("PIN 错误", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // PBKDF2 校验放到后台线程
                        val ok = withContext(Dispatchers.Default) {
                            settingsRepository.verifyPin(clearPinInput)
                        }
                        // 对话框关闭/离开页面后不再执行副作用
                        if (!isActive) return@launch
                        if (ok) {
                            settingsRepository.clearPin()
                            showClearPin = false
                            clearPinInput = ""
                            clearPinError = false
                            snackbarHostState.showSnackbar("已清除 PIN")
                        } else {
                            clearPinError = true
                        }
                    }
                }) { Text("清除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearPin = false
                    clearPinInput = ""
                    clearPinError = false
                }) { Text("取消") }
            }
        )
    }

    if (showSetPin) {
        AlertDialog(
            onDismissRequest = {
                showSetPin = false
                pinInput = ""
                pinConfirm = ""
            },
            title = { Text("设置锁屏 PIN") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pinInput,
                        onValueChange = { if (PinValidation.isAcceptable(it)) pinInput = it },
                        label = { Text("输入 PIN (数字)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pinConfirm,
                        onValueChange = { if (PinValidation.isAcceptable(it)) pinConfirm = it },
                        label = { Text("确认 PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when {
                        !PinValidation.isComplete(pinInput) -> scope.launch { snackbarHostState.showSnackbar("PIN 至少 4 位") }
                        pinInput != pinConfirm -> scope.launch { snackbarHostState.showSnackbar("两次输入不一致") }
                        else -> scope.launch {
                            // PBKDF2 哈希放到后台线程，避免阻塞 UI（与清除 PIN 一致）
                            withContext(Dispatchers.Default) {
                                settingsRepository.setPin(pinInput)
                            }
                            // 对话框关闭/离开页面后不再执行副作用
                            if (!isActive) return@launch
                            showSetPin = false
                            pinInput = ""
                            pinConfirm = ""
                            snackbarHostState.showSnackbar("PIN 已设置")
                        }
                    }
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showSetPin = false
                    pinInput = ""
                    pinConfirm = ""
                }) { Text("取消") }
            }
        )
    }

    if (showChangePin) {
        AlertDialog(
            onDismissRequest = {
                showChangePin = false
                changeOldPin = ""
                changeNewPin = ""
                changeNewPinConfirm = ""
                changePinError = false
            },
            title = { Text("修改 PIN") },
            text = {
                Column {
                    if (changePinError) {
                        Text("当前 PIN 错误", color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedTextField(
                        value = changeOldPin,
                        onValueChange = {
                            if (PinValidation.isAcceptable(it)) {
                                changeOldPin = it
                                changePinError = false
                            }
                        },
                        label = { Text("当前 PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = changeNewPin,
                        onValueChange = { if (PinValidation.isAcceptable(it)) changeNewPin = it },
                        label = { Text("新 PIN (数字)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = changeNewPinConfirm,
                        onValueChange = { if (PinValidation.isAcceptable(it)) changeNewPinConfirm = it },
                        label = { Text("确认新 PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    if (lockedCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "修改后将用新 PIN 重新加密 $lockedCount 条加密便签",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !changingPin,
                    onClick = {
                        when {
                            changingPin -> Unit
                            !PinValidation.isComplete(changeNewPin) ->
                                scope.launch { snackbarHostState.showSnackbar("新 PIN 至少 4 位") }
                            changeNewPin != changeNewPinConfirm ->
                                scope.launch { snackbarHostState.showSnackbar("两次输入不一致") }
                            else -> scope.launch {
                                // 重加密可能耗时数秒：禁用按钮并显示进度，避免重复点击把
                                // 第二次校验变成「当前 PIN 错误」的假象
                                changingPin = true
                                val result = viewModel.changePin(changeOldPin, changeNewPin)
                                // 离开页面后不再更新对话框状态
                                if (!isActive) return@launch
                                changingPin = false
                                when (result) {
                                    is ChangePinResult.WrongPin -> {
                                        changePinError = true
                                    }
                                    is ChangePinResult.Done -> {
                                        showChangePin = false
                                        changeOldPin = ""
                                        changeNewPin = ""
                                        changeNewPinConfirm = ""
                                        changePinError = false
                                        val msg = if (result.failed > 0) {
                                            "PIN 已修改，重新加密 ${result.reencrypted} 条；" +
                                                "${result.failed} 条无法解密（需原 PIN）"
                                        } else {
                                            "PIN 已修改，重新加密 ${result.reencrypted} 条"
                                        }
                                        snackbarHostState.showSnackbar(msg)
                                    }
                                }
                            }
                        }
                    }
                ) {
                    if (changingPin) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("正在重新加密…")
                    } else {
                        Text("确定")
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !changingPin,
                    onClick = {
                        showChangePin = false
                        changeOldPin = ""
                        changeNewPin = ""
                        changeNewPinConfirm = ""
                        changePinError = false
                        changingPin = false
                    }
                ) { Text("取消") }
            }
        )
    }
}

private fun computeCanExactAlarm(alarmManager: android.app.AlarmManager): Boolean =
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else true

private fun computeCanNotify(context: android.content.Context): Boolean =
    androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

/** 打开本应用的系统通知设置页（权限被拒绝后系统不再弹窗，只能去设置里改）。 */
private fun openNotificationSettings(context: android.content.Context) {
    val intent = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
