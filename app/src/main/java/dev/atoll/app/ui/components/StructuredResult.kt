package dev.atoll.app.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.serialization.json.*

@Immutable
data class ResultViewState(val value: JsonElement? = null, val title: String = "结构化结果", val errorCode: String? = null,
    val detail: String = "", val errorData: JsonElement? = null, val emptyText: Boolean = false, val acknowledgement: Boolean = false)
fun failureTitle(code: String): String = ERROR_LABELS[code] ?: "请求失败"
private val ERROR_LABELS = mapOf(
    "unanswered_timeout" to "请求在截止时间前没有得到最终响应", "receiver_unavailable" to "接收方已不可用",
    "receiver_internal_error" to "接收方处理失败", "type_unsupported" to "接收方不支持这个操作",
    "payload_invalid" to "请求参数不符合要求", "bad_payload" to "请求格式不正确", "not_in_audience" to "收件人不在频道",
    "unauthorized_sender" to "发送者无权执行", "already_closed" to "请求已经结束", "request_not_found" to "找不到请求",
    "invalid_decision" to "审批决定无效", "unavailable" to "频道暂不可用", "routing_unavailable" to "未找到可用收件人",
    "idempotency_conflict" to "消息编号发生冲突", "channel_not_found" to "找不到频道", "channel_unavailable" to "频道暂不可用",
    "capability_unavailable" to "所需能力暂不可用", "forbidden" to "无权在此发言", "permission_denied" to "没有执行该操作的权限",
    "closed" to "连接已关闭", "timeout" to "等待回执超时", "cas_mismatch" to "任务回合已经变化，请刷新后重试",
    "steer_missed" to "没赶上这一轮，已排到下一轮执行", "superseded" to "已被更新的操作取代，任务已回到队列",
    "control_timeout" to "受理方没有在时限内回应", "busy" to "另一个控制正在进行，稍后再试",
    "interrupted" to "任务已被打断", "cancelled" to "任务已取消", "empty_input" to "控制内容不能为空")
@Composable
fun StructuredResult(state: ResultViewState) {
    if (state.errorCode != null) {
        Surface(color = AtollColors.DangerSurface, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(failureTitle(state.errorCode), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Danger)
                Text(state.errorCode, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = AtollColors.DangerText)
                if (state.detail.isNotEmpty()) Text(state.detail, fontSize = 14.sp, lineHeight = 21.sp)
                state.errorData?.let { JsonTree(it) }
            }
        }
    } else if (state.emptyText) Text("返回了空文本", color = AtollColors.Muted, fontSize = 13.sp)
    else if (state.acknowledgement) Text("✓ 已完成", color = AtollColors.Muted, fontSize = 13.sp)
    else state.value?.let { value ->
        var expanded by remember(value) { mutableStateOf(false) }
        Surface(shape = MaterialTheme.shapes.medium, color = AtollColors.Surface, border = BorderStroke(1.dp, AtollColors.Line)) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Body)
                    Text(summary(value), fontSize = 12.sp, color = AtollColors.Muted)
                    Text(if (expanded) "收起" else "展开", fontSize = 12.sp, color = AtollColors.Lagoon)
                }
                if (expanded) { HorizontalDivider(color = AtollColors.LineSoft); JsonTree(value, Modifier.padding(12.dp)) }
            }
        }
    }
}
private fun summary(value: JsonElement) = when (value) { is JsonArray -> "${value.size} 项"; is JsonObject -> "${value.size} 个字段"; else -> "" }
@Composable
fun JsonTree(value: JsonElement, modifier: Modifier = Modifier, depth: Int = 0) {
    var expanded by remember(value) { mutableStateOf(depth < 2) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        when (value) {
            is JsonObject, is JsonArray -> {
                if (value is JsonObject && value.isEmpty()) Text("空对象", color = AtollColors.Muted, fontSize = 12.sp)
                else {
                    Text((if (expanded) "⌄ " else "› ") + summary(value), Modifier.clickable { expanded = !expanded }.padding(vertical = 5.dp), fontSize = 12.sp, color = AtollColors.Muted)
                    if (expanded) {
                        val entries = if (value is JsonObject) value.entries.map { it.key to it.value } else (value as JsonArray).mapIndexed { index, element -> "${index + 1}" to element }
                        entries.take(20).forEach { (key, child) ->
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(key, Modifier.width(80.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AtollColors.Muted)
                                JsonTree(child, Modifier.weight(1f), depth = depth + 1)
                            }
                        }
                    }
                }
            }
            JsonNull -> Text("null", color = AtollColors.Muted, fontSize = 12.sp)
            is JsonPrimitive -> Text(value.booleanOrNull?.let { if (it) "是" else "否" } ?: value.content, fontSize = 13.sp, lineHeight = 19.sp)
        }
    }
}
