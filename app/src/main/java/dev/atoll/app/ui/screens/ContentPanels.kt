package dev.atoll.app.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
import dev.atoll.app.ui.theme.AtollShapes
import kotlinx.coroutines.delay

/** Full-screen secondary page on the paper ground: title, 关闭, then content. */
@Composable
fun ContentPanel(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().background(AtollColors.Surface).heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClose, Modifier.heightIn(min = 44.dp)) { Text("关闭", fontSize = 15.sp, color = AtollColors.Muted) }
            }
            HorizontalDivider(color = AtollColors.Line)
            content()
        }
    }
}
@Composable
fun RunningDrawer(tasks: List<SubTaskViewState>, onJump: (String) -> Boolean, onClose: () -> Unit) {
    val running = tasks.filter { it.phase == SubTaskPhase.Running }
    var missing by remember { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis(), running.isNotEmpty()) {
        if (running.isNotEmpty()) while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    ContentPanel("运行中${if (running.isNotEmpty()) " · ${running.size}" else ""}", onClose) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (running.isEmpty()) item { PanelEmpty("没有正在运行的后台任务", "Agent 派出的子 Agent 和后台命令在运行时会出现在这里，点一下回到派出它的消息。") }
            items(running, key = { it.id }) { task ->
                Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp, AtollColors.Line, AtollShapes.Card).clickable {
                    if (task.requestId != null && onJump(task.requestId)) { missing = null; onClose() } else missing = task.id
                }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PulseDot(size = 8.dp)
                        Text("${task.kind.label} · ${task.owner}", Modifier.weight(1f), fontSize = 12.sp, color = AtollColors.Muted)
                        Text(runningFor(task.startedAtMillis, now), fontSize = 12.sp, color = AtollColors.RunningText)
                    }
                    Text(task.title.ifEmpty { "（未命名任务）" }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (task.latest.isNotEmpty()) Text(task.latest, fontSize = 13.sp, fontFamily = AtollMono, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (missing == task.id) Text(if (task.requestId == null) "这个任务没有记下是哪条消息派出的。" else "派出它的消息还没加载到页面上，往上翻到它附近后再点。", color = AtollColors.Warning, fontSize = 13.sp)
                }
            }
        }
    }
}
@Composable
fun StarredDrawer(stars: List<StarredMessageViewState>, onJump: (String) -> Boolean, onRemove: (String) -> Unit, onClose: () -> Unit) {
    var missing by remember { mutableStateOf<String?>(null) }
    ContentPanel("星标", onClose) {
        LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (stars.isEmpty()) item { PanelEmpty("还没有星标消息", "长按一条消息选择“星标”，它会出现在这里，点一下就能回到那条消息。") }
            items(stars, key = { it.id }) { star -> Row(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp, AtollColors.Line, AtollShapes.Card)) {
                Column(Modifier.weight(1f).clickable { if (onJump(star.id)) { missing = null; onClose() } else missing = star.id }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActorAvatar(star.sender, star.sender, agent = true, size = 22.dp)
                        Text(star.sender, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(star.time, fontSize = 12.sp, color = AtollColors.Muted)
                    }
                    Text(star.text.ifEmpty { "（无文字内容）" }, fontSize = 15.sp, lineHeight = 22.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (missing == star.id) Text("这条消息还没加载到页面上，往上翻到它附近后再点。", color = AtollColors.Warning, fontSize = 13.sp)
                }
                IconButton({ onRemove(star.id) }, Modifier.size(44.dp)) { Text("×", fontSize = 18.sp, color = AtollColors.Muted) }
            } }
        }
    }
}
@Composable private fun PanelEmpty(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, color = AtollColors.Muted, fontSize = 14.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
    }
}
