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
fun LoginScreen(state: LoginViewState, onChange: (LoginViewState) -> Unit, onSubmit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(AtollColors.Canvas).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(), color = AtollColors.Rail,
            shape = MaterialTheme.shapes.large, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ATOLL", style = MaterialTheme.typography.titleLarge, color = AtollColors.Accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                TextButton({ onChange(state.copy(mode = AuthMode.Login, error = null)) }, Modifier.weight(1f), enabled = !state.busy) { Text("登录", color = if (state.mode == AuthMode.Login) AtollColors.Accent else AtollColors.Muted) }
                TextButton({ onChange(state.copy(mode = AuthMode.Register, error = null)) }, Modifier.weight(1f), enabled = !state.busy) { Text("注册", color = if (state.mode == AuthMode.Register) AtollColors.Accent else AtollColors.Muted) }
            }
            HorizontalDivider(color = AtollColors.ControlLine)
            OutlinedTextField(state.server, { onChange(state.copy(server = it)) }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(state.account, { onChange(state.copy(account = it)) }, Modifier.fillMaxWidth(), label = { Text(if (state.mode == AuthMode.Register) "邮箱" else "账号") }, singleLine = true, enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = if (state.mode == AuthMode.Register) KeyboardType.Email else KeyboardType.Text))
            if (state.mode == AuthMode.Register) OutlinedTextField(state.displayName, { onChange(state.copy(displayName = it)) }, Modifier.fillMaxWidth(), label = { Text("显示名（可选）") }, singleLine = true, enabled = !state.busy)
            OutlinedTextField(state.password, { onChange(state.copy(password = it)) }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            state.error?.let { Text(it, color = AtollColors.Accent, style = MaterialTheme.typography.bodySmall) }
            Button(onSubmit, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, enabled = !state.busy && state.server.isNotBlank() && state.account.isNotBlank() && state.password.isNotEmpty()) {
                Text(if (state.busy) "请稍候…" else if (state.mode == AuthMode.Register) "创建账号" else "进入 Atoll")
            }
        }
        }
    }
}
