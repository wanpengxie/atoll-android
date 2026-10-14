package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors

/** Content of the shared process sheet's Collaboration tab; it owns no dialog or ledger. */
@Composable
fun CollaborationContent(state: CollaborationViewState, modifier: Modifier = Modifier,
    initialRequestId: String? = null, onOpenRequest: (String) -> Unit = {},
    onLink: ((String) -> Unit)? = null, onAttachment: MessageAttachmentHandler? = null,
    onProcess: (String) -> Unit = {}) {
    var selectedId by rememberSaveable(state.root.id, initialRequestId) { mutableStateOf(initialRequestId ?: state.root.id) }
    val index = remember(state) { CollaborationIndex(state) }
    val current = index.selected(selectedId)
    val path = remember(index, current.id) { index.path(current.id) }
    val pathScroll = rememberScrollState()
    LaunchedEffect(current.id, pathScroll.maxValue) { pathScroll.animateScrollTo(pathScroll.maxValue) }
    Column(modifier.fillMaxSize().pointerInput(path.map { it.id }) {
        var drag = 0f
        detectHorizontalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f },
            onDragEnd = { if (drag < -64.dp.toPx() && path.size > 1) selectedId = path[path.lastIndex - 1].id }) { change, amount ->
            drag += amount; change.consume()
        }
    }) {
        Row(Modifier.fillMaxWidth().horizontalScroll(pathScroll).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { contentDescription = "线程路径" }, verticalAlignment = Alignment.CenterVertically) {
            path.forEachIndexed { position, call ->
                if (position > 0) Text("›", Modifier.padding(horizontal = 4.dp), color = AtollColors.Faint, fontSize = 13.sp)
                val active = position == path.lastIndex
                Text(index.label(call), Modifier.widthIn(max = 150.dp).heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(8.dp)).background(if (active) AtollColors.Ink else AtollColors.Ground)
                    .clickable { selectedId = call.id }.semantics { selected = active }
                    .padding(horizontal = 10.dp, vertical = 12.dp), fontSize = 13.sp,
                    color = if (active) AtollColors.Surface else AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item("selected:${current.id}") {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AtollColors.Surface)
                    .border(1.dp, AtollColors.Line, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${current.sender} → ${current.recipients.joinToString("、")}", Modifier.weight(1f),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(current.time, fontSize = 12.sp, color = AtollColors.Muted)
                        Text(collaborationStatus(current), fontSize = 12.sp,
                            color = if (current.status == MessageStatus.Failed) AtollColors.Danger else AtollColors.Muted)
                    }
                    if (current.request.isNotEmpty()) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(AtollColors.Ground).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Markdown("collab:${current.id}:request", current.request, onLink = onLink)
                    }
                    if (current.text.isNotEmpty()) FoldableMarkdown("collab:${current.id}:answer", current.text, onLink = onLink)
                    current.result?.let { StructuredResult(it) }
                    if (current.attachments.isNotEmpty()) MessageAttachments(current.attachments, onLink, onAttachment)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ onProcess(current.id) }) { Text("过程 ${current.trail?.records?.size ?: 0}") }
                        if (index.ordinaryCount(current.id) > 0) TextButton({ onProcess(current.id) }) { Text("调用 ${index.ordinaryCount(current.id)}") }
                    }
                    if (current.requestType in setOf("human.ask", "human.approve")) TextButton({ onOpenRequest(current.id) }) {
                        Text(if (current.status == MessageStatus.Completed || current.status == MessageStatus.Failed) "查看原消息" else "回原位置处理")
                    }
                }
            }
            val children = index.children(current.id)
            item("children-heading") { Text("${index.label(current)} 发起的 · ${children.size}", Modifier.padding(horizontal = 4.dp),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted) }
            items(children, key = { it.id }) { child ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(AtollColors.Surface)
                    .border(1.dp, AtollColors.Line, RoundedCornerShape(16.dp)).clickable { selectedId = child.id }
                    .semantics { contentDescription = "进入协作 ${child.title}" }.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActorAvatar(index.label(child), index.label(child), agent = !child.requestType.startsWith("human."), size = 28.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${child.sender} › ${index.label(child)}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(child.title, fontSize = 13.sp, color = AtollColors.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(collaborationStatus(child), fontSize = 12.sp, color = if (child.status == MessageStatus.Failed) AtollColors.Danger else AtollColors.Muted)
                    val descendants = index.children(child.id).size
                    if (descendants > 0) Text("下级 $descendants", fontSize = 11.sp, color = AtollColors.Muted)
                }
            }
            if (children.isEmpty()) item("no-children") { Text("没有下级协作。", Modifier.padding(horizontal = 4.dp), fontSize = 13.sp, color = AtollColors.Muted) }
        }
    }
}

private fun collaborationStatus(call: RelatedCallViewState): String = when {
    call.requestType == "human.ask" && call.status == MessageStatus.Completed -> "已回答"
    call.requestType == "human.approve" && call.status == MessageStatus.Completed -> "已回执"
    call.requestType.startsWith("human.") && call.status == MessageStatus.Processing -> "待处理"
    else -> call.status.label
}
