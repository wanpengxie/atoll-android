package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono

internal enum class UpgradeStepState { Pending, Active, Done }
internal data class UpgradeStep(val text: String, val state: UpgradeStepState)

/** Presentation of existing monitor facts; no timer, request or additional update owner. */
internal fun upgradeSteps(update: UpgradeViewState, connection: ConnectionPhase): List<UpgradeStep> {
    val pending = UpgradeStepState.Pending; val active = UpgradeStepState.Active; val done = UpgradeStepState.Done
    return when (update.phase) {
        "starting" -> listOf(UpgradeStep("正在请求升级", active), UpgradeStep("节点重启", pending), UpgradeStep("重新连接并核对版本", pending))
        "downloading", "verifying", "installing" -> listOf(UpgradeStep("已请求升级", done), UpgradeStep(update.label(connection), active), UpgradeStep("重新连接并核对版本", pending))
        "restarting" -> if (connection == ConnectionPhase.OPEN)
            listOf(UpgradeStep("已请求升级", done), UpgradeStep("节点重启中", active), UpgradeStep("重新连接并核对版本", pending))
            else listOf(UpgradeStep("已请求升级", done), UpgradeStep("节点已断开", done), UpgradeStep("重新连接并核对版本", active))
        "available", "failed" -> if (update.available) listOf(UpgradeStep("请求升级", pending), UpgradeStep("节点重启", pending), UpgradeStep("重新连接并核对版本", pending)) else emptyList()
        else -> emptyList()
    }
}

@Composable
fun UpgradePanel(update: UpgradeViewState, connection: ConnectionPhase, onClose: () -> Unit, onUpgrade: (() -> Unit)? = null) {
    var confirming by remember { mutableStateOf(false) }
    val canUpgrade = update.canStart && connection == ConnectionPhase.OPEN && onUpgrade != null
    val steps = upgradeSteps(update, connection)
    Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding()) {
        PageHeader("节点版本", onClose)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            UpgradeCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("当前", fontSize = 12.sp, color = AtollColors.Muted)
                        Text(update.current, fontSize = 18.sp, fontFamily = AtollMono, fontWeight = FontWeight.Medium)
                    }
                    Icon(Icons.Outlined.ArrowForward, null, Modifier.size(20.dp), tint = AtollColors.Faint)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(if (update.available) "可升级到" else "最新", fontSize = 12.sp, color = AtollColors.LagoonText)
                        Text(update.latest.ifEmpty { "未获取" }, fontSize = 18.sp, fontFamily = AtollMono, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            UpgradeCard {
                Text("升级时会发生什么", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                UpgradeImpact(Icons.Outlined.RestartAlt, "Atoll 会重启，当前连接暂时中断")
                UpgradeImpact(Icons.Outlined.Pause, "正在运行的 Agent 回合会被打断")
                UpgradeImpact(Icons.Outlined.Check, "频道记录、任务和工作区数据都会保留", preserved = true)
            }
            if (steps.isNotEmpty()) UpgradeCard {
                Text(if (steps.any { it.state != UpgradeStepState.Pending }) "升级中" else "升级流程", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
                steps.forEach { step -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (step.state) {
                        UpgradeStepState.Active -> Spinner(20.dp, AtollColors.Running, AtollColors.RunningTrack)
                        UpgradeStepState.Done -> Box(Modifier.size(20.dp).background(AtollColors.Ink, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = AtollColors.Surface)
                        }
                        UpgradeStepState.Pending -> Box(Modifier.size(20.dp).border(1.5.dp, AtollColors.ControlLine, CircleShape))
                    }
                    Text(step.text, fontSize = 14.sp, color = if (step.state == UpgradeStepState.Active) AtollColors.RunningText else if (step.state == UpgradeStepState.Pending) AtollColors.Muted else AtollColors.Ink)
                } }
            }
            if (update.phase !in setOf("available", "starting", "downloading", "verifying", "installing", "restarting"))
                NoticeBanner(update.label(connection), if (update.phase == "failed") Tone.Danger else Tone.Neutral)
            if (update.detail.isNotBlank()) Text(update.detail, fontSize = 13.sp, lineHeight = 19.sp, color = AtollColors.Muted)
        }
        AtollButton(if (update.canStart) "升级到 ${update.latest}" else update.label(connection), { confirming = true },
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp).testTag("upgrade-action"), enabled = canUpgrade, height = 52.dp)
    }
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text("确认升级到 ${update.latest}？") },
        text = { Text("升级会重启 Atoll，暂时中断连接和正在运行的 Agent 回合。频道记录、任务和工作区数据会保留。") },
        confirmButton = { TextButton({ confirming = false; onUpgrade?.invoke() }, enabled = canUpgrade) { Text("确认升级") } },
        dismissButton = { TextButton({ confirming = false }) { Text("取消") } })
}

@Composable private fun UpgradeCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(AtollColors.Surface)
        .border(1.dp, AtollColors.Line, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable private fun UpgradeImpact(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, preserved: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.size(18.dp), tint = if (preserved) AtollColors.Lagoon else AtollColors.Warning)
        Text(text, fontSize = 14.sp, lineHeight = 21.sp, color = AtollColors.Body)
    }
}
