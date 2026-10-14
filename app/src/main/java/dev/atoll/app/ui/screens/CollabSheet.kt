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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import dev.atoll.app.ui.theme.ChatColors

/**
 * CollabSheet board: the collaboration of one turn read as a chat. Each delegated call is the callee's
 * block (its answer and a grey status line) with a grey quote of who asked it what. Content of the
 * process sheet's 协作 tab; it owns no dialog or ledger.
 */
@Composable
fun CollaborationContent(state: CollaborationViewState, modifier: Modifier = Modifier,
    initialRequestId: String? = null, onOpenRequest: (String) -> Unit = {},
    onLink: ((String) -> Unit)? = null, onAttachment: MessageAttachmentHandler? = null,
    onProcess: (String) -> Unit = {}) {
    val index = remember(state) { CollaborationIndex(state) }
    // Depth-first, so a reply sits right under the request that caused it.
    val rows = remember(index) {
        val out = mutableListOf<Pair<RelatedCallViewState, Int>>()
        fun walk(id: String, depth: Int) { index.children(id).forEach { child -> out += child to depth; walk(child.id, depth + 1) } }
        walk(state.root.id, 1); out
    }
    val listState = rememberLazyListState()
    LaunchedEffect(initialRequestId, rows.size) {
        val target = rows.indexOfFirst { it.first.id == initialRequestId }
        if (target >= 0) listState.scrollToItem(target + 1)
    }
    LazyColumn(modifier.fillMaxSize().background(ChatColors.Ground), state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp)) {
        item("summary") {
            Text("${state.root.title.ifBlank { index.label(state.root) }} · ${index.label(state.root)} 的回合\n协作 ${rows.size} · 最深 ${rows.maxOfOrNull { it.second } ?: 0} 层",
                Modifier.fillMaxWidth().padding(vertical = 6.dp), fontSize = 12.sp, lineHeight = 17.sp, color = ChatColors.Gray,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (rows.isEmpty()) item("empty") { Text("没有下级协作。", Modifier.padding(16.dp), fontSize = 13.sp, color = ChatColors.Gray) }
        items(rows, key = { it.first.id }) { (call, _) ->
            val callee = index.label(call)
            val human = call.requestType.startsWith("human.")
            Column(Modifier.fillMaxWidth().padding(top = 12.dp).semantics(mergeDescendants = false) { contentDescription = "${call.sender} 请 $callee：${call.title}" },
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NameLine(callee, callee, agent = !human, time = call.time)
                Column(Modifier.fillMaxWidth().clip(OthersShape).background(ChatColors.Block).padding(start = 14.dp, end = 14.dp, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (call.text.isNotEmpty()) FoldableMarkdown("collab:${call.id}:answer", call.text, onLink = onLink)
                    else Text(if (call.status == MessageStatus.Processing) "正在处理…" else "没有回复正文", fontSize = 16.sp, lineHeight = 25.sp, color = ChatColors.Gray)
                    call.result?.let { StructuredResult(it) }
                    if (call.attachments.isNotEmpty()) MessageAttachments(call.attachments, onLink, onAttachment)
                    val parts = listOfNotNull(
                        collaborationStatus(call),
                        call.trail?.records?.size?.takeIf { it > 0 }?.let { "过程 $it" },
                        index.ordinaryCount(call.id).takeIf { it > 0 }?.let { "调用 $it" },
                        index.children(call.id).size.takeIf { it > 0 }?.let { "下级 $it" })
                    Row(Modifier.tuckedUp().fillMaxWidth().heightIn(min = 44.dp).clickable { if (human) onOpenRequest(call.id) else onProcess(call.id) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when (call.status) {
                            MessageStatus.Processing -> ChatSpinner()
                            MessageStatus.Completed -> Text("✓", fontSize = 13.sp, color = ChatColors.Gray)
                            else -> Unit
                        }
                        Text(parts.joinToString(" · ") + if (human) (if (call.status == MessageStatus.Processing) " · 回原位置处理 ›" else " · 查看原消息 ›") else " ›",
                            fontSize = 13.sp, color = if (call.status == MessageStatus.Failed) ChatColors.Red else ChatColors.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Text("${call.sender}：${call.request.ifBlank { call.title }.replace('\n', ' ')}", Modifier.clip(RoundedCornerShape(4.dp)).background(ChatColors.Quote)
                    .padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 13.sp, lineHeight = 19.sp, color = ChatColors.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun collaborationStatus(call: RelatedCallViewState): String = when {
    call.requestType == "human.ask" && call.status == MessageStatus.Completed -> "已回答"
    call.requestType == "human.approve" && call.status == MessageStatus.Completed -> "已回执"
    call.requestType.startsWith("human.") && call.status == MessageStatus.Processing -> "待处理"
    else -> call.status.label
}
