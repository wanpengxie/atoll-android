package dev.atoll.app.app

import dev.atoll.app.net.IdentityClient
import dev.atoll.app.ui.screens.AuthMode
import dev.atoll.app.ui.screens.LoginViewState

fun identityFormError(form: LoginViewState): String? = when {
    form.server.isBlank() || form.account.isBlank() || form.password.isEmpty() -> "请填写服务器、账号和密码"
    form.mode == AuthMode.Register && !Regex("^[^\\s@]+@[^\\s@]+$").matches(form.account.trim()) -> "请输入有效邮箱"
    form.mode == AuthMode.Register && form.password.length < 8 -> "注册密码至少需要 8 个字符"
    else -> null
}
suspend fun submitIdentity(identity: IdentityClient, form: LoginViewState): kotlinx.serialization.json.JsonObject {
    require(identityFormError(form) == null) { identityFormError(form).orEmpty() }
    return when (form.mode) {
    AuthMode.Login -> identity.login(form.account.trim(), form.password)
    AuthMode.Register -> identity.register(form.account.trim(), form.password, displayName = form.displayName.trim())
}
}
