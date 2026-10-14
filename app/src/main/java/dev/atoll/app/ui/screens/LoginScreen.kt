package dev.atoll.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.AtollButton
import dev.atoll.app.ui.components.SegmentedControl
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono

/** Top-aligned and scrollable, so the keyboard never covers the submit button. */
@Composable
fun LoginScreen(state: LoginViewState, onSubmit: (LoginViewState) -> Unit) {
    var server by rememberSaveable { mutableStateOf(state.server) }
    var account by rememberSaveable { mutableStateOf(state.account) }
    var displayName by rememberSaveable { mutableStateOf(state.displayName) }
    var mode by rememberSaveable { mutableStateOf(state.mode) }
    var password by remember { mutableStateOf("") }
    var reveal by remember { mutableStateOf(false) }
    val register = mode == AuthMode.Register
    val error = state.error?.takeIf { mode == state.mode && server == state.server && account == state.account }
    val ready = !state.busy && server.isNotBlank() && account.isNotBlank() && password.isNotEmpty()
    Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp, vertical = 32.dp).widthIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AtollMark(Modifier.padding(top = 24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (register) "创建账号" else "登录 Atoll", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
            Text(if (register) "注册后由空间管理员准入，才能加入频道。" else "连接你的节点，和 Agent 一起工作。", fontSize = 15.sp, color = AtollColors.Muted)
        }
        SegmentedControl(listOf("登录", "注册"), if (register) 1 else 0, { if (!state.busy) mode = if (it == 1) AuthMode.Register else AuthMode.Login },
            Modifier.fillMaxWidth().padding(top = 8.dp), fill = true)
        LoginField("服务器地址", server, { server = it }, !state.busy, KeyboardType.Uri, mono = true)
        LoginField(if (register) "邮箱" else "账号", account, { account = it }, !state.busy, if (register) KeyboardType.Email else KeyboardType.Text)
        if (register) LoginField("显示名（可选）", displayName, { displayName = it }, !state.busy, KeyboardType.Text)
        LoginField("密码", password, { password = it }, !state.busy, KeyboardType.Password, error = error,
            transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
            trailing = { IconButton({ reveal = !reveal }) {
                Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, if (reveal) "隐藏密码" else "显示密码", tint = AtollColors.Muted)
            } })
        AtollButton(if (state.busy) "请稍候…" else if (register) "创建账号" else "进入 Atoll",
            { onSubmit(LoginViewState(server, account, password, mode = mode, displayName = displayName)) },
            Modifier.fillMaxWidth().padding(top = 8.dp), enabled = ready, busy = state.busy, height = 54.dp)
    }
}

@Composable
private fun LoginField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, keyboard: KeyboardType,
    mono: Boolean = false, error: String? = null, transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, enabled = enabled,
            isError = error != null, visualTransformation = transformation, trailingIcon = trailing,
            textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, fontFamily = if (mono) AtollMono else LocalTextStyle.current.fontFamily),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard), shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AtollColors.Lagoon, focusedLabelColor = AtollColors.LagoonText,
                unfocusedBorderColor = AtollColors.ControlLine, unfocusedContainerColor = AtollColors.Surface, focusedContainerColor = AtollColors.Surface,
                disabledContainerColor = AtollColors.Subtle, cursorColor = AtollColors.Lagoon))
        if (error != null) Text(error, fontSize = 13.sp, color = AtollColors.DangerText)
    }
}

/** The ring of an atoll around its lagoon. */
@Composable
private fun AtollMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).semantics { contentDescription = "Atoll" }) {
        val ring = size.minDimension * 0.125f
        drawCircle(AtollColors.Ink, radius = size.minDimension / 2 - ring / 2 - 2f, center = Offset(size.width / 2, size.height / 2), style = Stroke(ring))
        drawCircle(AtollColors.Lagoon, radius = size.minDimension * 0.15f, center = Offset(size.width / 2, size.height / 2))
    }
}
