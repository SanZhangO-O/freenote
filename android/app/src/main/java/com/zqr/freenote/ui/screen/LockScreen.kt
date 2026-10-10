package com.zqr.freenote.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zqr.freenote.data.DefaultSettingsRepository
import com.zqr.freenote.data.SettingsRepository
import com.zqr.freenote.ui.common.PinValidation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LockScreen(
    onUnlocked: (String) -> Unit,
    onCancel: () -> Unit,
    settingsRepository: SettingsRepository = DefaultSettingsRepository.instance
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var verifying by remember { mutableStateOf(false) }
    // 本机未设置 PIN 时任何输入都无法通过校验：明确告知原因，避免用户陷入「永远 PIN 错误」
    val hasPin = remember { settingsRepository.hasPin() }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    val submit: () -> Unit = {
        if (!verifying && pin.isNotEmpty()) {
            verifying = true
            scope.launch {
                // PBKDF2 校验放到后台线程，避免主线程卡顿
                val ok = withContext(Dispatchers.Default) {
                    settingsRepository.verifyPin(pin)
                }
                // 验证期间用户可能已按返回离开：协程被取消后不再执行导航副作用
                if (!isActive) return@launch
                verifying = false
                if (ok) {
                    onUnlocked(pin)
                } else {
                    // 清空并保持焦点：错误后直接重新输入，不必手动删旧值
                    error = true
                    pin = ""
                }
            }
        }
    }

    // 进入解锁页自动聚焦 PIN 输入框并弹出数字键盘
    LaunchedEffect(Unit) {
        if (hasPin) focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("解锁便签") },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Outlined.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(16.dp))
            if (!hasPin) {
                Text(
                    "本机尚未设置应用锁 PIN。这条便签可能来自使用其他 PIN 的设备，无法在本机解锁。",
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            } else {
                Text("请输入 PIN", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { if (PinValidation.isAcceptable(it)) { pin = it; error = false } },
                    label = { Text("PIN") },
                    singleLine = true,
                    isError = error,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(focusRequester)
                )
                if (error) {
                    Spacer(Modifier.height(8.dp))
                    Text("PIN 错误", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                Button(
                    enabled = !verifying,
                    onClick = submit
                ) { Text("解锁") }
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.material3.TextButton(onClick = onCancel) { Text("取消") }
        }
    }
}
