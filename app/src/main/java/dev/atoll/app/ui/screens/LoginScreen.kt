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
    Box(Modifier.fillMaxSize().background(AtollColors.Canvas).safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
        Surface(Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(), color = AtollColors.Rail,
            shape = MaterialTheme.shapes.large, shadowElevation = 6.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("ATOLL", style = MaterialTheme.typography.titleLarge, color = AtollColors.Accent)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                TextButton({}, Modifier.weight(1f), enabled = !state.busy) { Text("登录", color = AtollColors.Accent) }
                TextButton(onRegister, Modifier.weight(1f), enabled = !state.busy) { Text("注册", color = AtollColors.Muted) }
            }
            HorizontalDivider(color = AtollColors.ControlLine)
            OutlinedTextField(state.server, { onChange(state.copy(server = it)) }, Modifier.fillMaxWidth(), label = { Text("服务器地址") }, singleLine = true,
                enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(state.account, { onChange(state.copy(account = it)) }, Modifier.fillMaxWidth(), label = { Text("账号") }, singleLine = true, enabled = !state.busy)
            OutlinedTextField(state.password, { onChange(state.copy(password = it)) }, Modifier.fillMaxWidth(), label = { Text("密码") }, singleLine = true,
                enabled = !state.busy, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            state.error?.let { Text(it, color = AtollColors.Accent, style = MaterialTheme.typography.bodySmall) }
            Button(onLogin, Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.small, enabled = !state.busy && state.server.isNotBlank() && state.account.isNotBlank() && state.password.isNotEmpty()) {
                Text(if (state.busy) "登录中…" else "进入 Atoll")
            }
        }
        }
    }
}
