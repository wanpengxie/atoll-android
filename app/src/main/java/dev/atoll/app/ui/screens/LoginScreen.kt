package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun LoginScreen(state: LoginViewState, onChange: (LoginViewState) -> Unit, onLogin: () -> Unit, onRegister: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AtollColors.Rail).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("atoll", style = MaterialTheme.typography.titleLarge, color = AtollColors.Accent)
            Text("登录你的空间", style = MaterialTheme.typography.titleMedium)
            Text("连接频道，与团队一起工作。", style = MaterialTheme.typography.bodyMedium, color = AtollColors.Muted)
            OutlinedTextField(state.server, { onChange(state.copy(server = it)) }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(state.account, { onChange(state.copy(account = it)) }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true, enabled = !state.busy)
            OutlinedTextField(state.password, { onChange(state.copy(password = it)) }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            state.error?.let { Text(it, color = AtollColors.Accent, style = MaterialTheme.typography.bodySmall) }
            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = !state.busy && state.server.isNotBlank() && state.account.isNotBlank() && state.password.isNotEmpty()) {
                Text(if (state.busy) "登录中…" else "登录")
            }
            TextButton(onRegister, Modifier.align(Alignment.CenterHorizontally), enabled = !state.busy) { Text("注册账号") }
        }
    }
}
