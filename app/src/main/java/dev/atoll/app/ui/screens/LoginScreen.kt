package dev.atoll.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
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
import dev.atoll.app.ui.components.Spinner
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
    Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding().imePadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 24.dp, end = 24.dp, top = if (register) 8.dp else 72.dp, bottom = 24.dp)) {
            if (register) TextButton({ if (!state.busy) mode = AuthMode.Login }, Modifier.heightIn(min = 44.dp)) {
                Text("‹ 返回登录", color = AtollColors.Ink)
            } else {
                AtollMark()
                Spacer(Modifier.height(18.dp))
            }
            Column(Modifier.padding(top = if (register) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (register) "创建账号" else "登录 Atoll", fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold)
                Text(if (register) "注册后由空间管理员准入，才能加入频道。" else "连接你的节点，和 Agent 一起工作。",
                    fontSize = 15.sp, lineHeight = 22.sp, color = AtollColors.Muted)
            }
            Spacer(Modifier.height(36.dp))
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LoginField("服务器地址", server, { server = it }, !state.busy, KeyboardType.Uri, mono = true)
                LoginField(if (register) "邮箱" else "账号", account, { account = it }, !state.busy, if (register) KeyboardType.Email else KeyboardType.Text)
                if (register) LoginField("显示名（可选）", displayName, { displayName = it }, !state.busy, KeyboardType.Text)
                LoginField("密码", password, { password = it }, !state.busy, KeyboardType.Password, error = error,
                    transformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = { IconButton({ reveal = !reveal }, Modifier.size(44.dp)) {
                        Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (reveal) "隐藏密码" else "显示密码", Modifier.size(20.dp), tint = AtollColors.Muted)
                    } })
                Button({ onSubmit(LoginViewState(server, account, password, mode = mode, displayName = displayName)) },
                    Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 54.dp), enabled = ready,
                    shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = AtollColors.Ink,
                        disabledContainerColor = AtollColors.Sunken, disabledContentColor = AtollColors.Faint)) {
                    if (state.busy) { Spinner(14.dp, AtollColors.Faint, AtollColors.Line); Spacer(Modifier.width(8.dp)) }
                    Text(if (state.busy) "请稍候…" else if (register) "创建账号" else "进入 Atoll", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (register) "已有账号？" else "还没有账号？", fontSize = 15.sp, color = AtollColors.Muted)
            TextButton({ if (!state.busy) mode = if (register) AuthMode.Login else AuthMode.Register }, enabled = !state.busy) {
                Text(if (register) "登录" else "注册", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Lagoon)
            }
        }
    }
}

@Composable
private fun LoginField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, keyboard: KeyboardType,
    mono: Boolean = false, error: String? = null, transformation: VisualTransformation = VisualTransformation.None,
    trailing: (@Composable () -> Unit)? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Body)
        BasicTextField(value, onChange, Modifier.fillMaxWidth().height(52.dp).semantics { contentDescription = label },
            singleLine = true, enabled = enabled, visualTransformation = transformation,
            textStyle = LocalTextStyle.current.copy(fontSize = if (mono) 15.sp else 16.sp, color = AtollColors.Ink,
                fontFamily = if (mono) AtollMono else LocalTextStyle.current.fontFamily),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            decorationBox = { inner -> Row(Modifier.fillMaxSize().background(AtollColors.Surface, RoundedCornerShape(14.dp))
                .border(if (error != null) 1.5.dp else 1.dp, if (error != null) AtollColors.Danger else AtollColors.ControlLine, RoundedCornerShape(14.dp))
                .padding(start = 16.dp, end = if (trailing == null) 16.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { inner() }
                trailing?.invoke()
            } })
        if (error != null) Text(error, fontSize = 13.sp, color = AtollColors.DangerText)
    }
}

/** The ring of an atoll around its lagoon. */
@Composable
private fun AtollMark(modifier: Modifier = Modifier) {
    Canvas(modifier.size(48.dp).semantics { contentDescription = "Atoll" }) {
        val ring = size.minDimension * 0.125f
        drawCircle(AtollColors.Ink, radius = size.minDimension * (17f / 48f), center = Offset(size.width / 2, size.height / 2), style = Stroke(ring))
        drawCircle(AtollColors.Lagoon, radius = size.minDimension * (7f / 48f), center = Offset(size.width / 2, size.height / 2))
    }
}
