package dev.atoll.app.roster

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.governance.AddMemberForm
import dev.atoll.app.governance.GovernanceViewModel
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors

data class RosterHeaderState(val count: Int = 0, val refreshEnabled: Boolean = false, val refreshing: Boolean = false)

@Composable
fun RosterPanel(viewModel: RosterViewModel, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null,
    onSelect: ((RosterActor) -> Unit)? = null, onAdd: (() -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    RosterContent(state, modifier, onSelect = { viewModel.select(it); onSelect?.invoke(it) }, onAdd = onAdd)
}

@Composable
fun RosterChannelPanel(viewModel: RosterViewModel, governance: GovernanceViewModel, onSelect: (RosterActor) -> Unit) {
    var adding by remember(viewModel.channelId) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        RosterPanel(viewModel, onSelect = onSelect, onAdd = { adding = true })
        if (adding) AddMemberOverlay(onDismiss = { adding = false }) { AddMemberForm(governance) }
    }
}

/** Members board body; the channel owns its header, tabs and refresh action. */
@Composable
fun RosterContent(state: RosterUiState, modifier: Modifier = Modifier, onSelect: (RosterActor) -> Unit,
    onAdd: (() -> Unit)? = null) {
    val (people, members) = remember(state.rows) { state.rows.partition { it.kind == "human" } }
    Box(modifier.fillMaxSize().background(AtollColors.Ground)) {
        LazyColumn(Modifier.fillMaxSize().semantics { contentDescription = "频道成员列表" },
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 90.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.error.isNotEmpty()) item { NoticeBanner(state.error, Tone.Waiting) }
            if (state.identityPending) item { Text("正在确认你在本频道中的 Actor 身份", fontSize = 13.sp, color = AtollColors.Muted) }
            if (state.rows.isEmpty()) item {
                if (state.busy) Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spinner(14.dp); Text("正在读取成员…", fontSize = 13.sp, color = AtollColors.Muted)
                } else Text("暂无业务成员", Modifier.padding(16.dp), fontSize = 15.sp, color = AtollColors.Muted)
            }
            for ((label, rows) in listOf("Agent 与工具" to members, "人" to people)) {
                if (rows.isEmpty()) continue
                item(key = "label:$label") { SectionLabel("$label · ${rows.size}") }
                item(key = "group:$label") { GroupCard {
                    rows.forEachIndexed { index, actor ->
                        Row(Modifier.fillMaxWidth().clickable { onSelect(actor) }.heightIn(min = 64.dp)
                            .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActorAvatar(actor.name, actor.id, agent = actor.kind != "human", size = 40.dp, self = actor.id == state.selfId)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(actor.name + if (actor.id == state.selfId) " · 我" else "", fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(actor.description.ifEmpty { "${actor.kind.ifEmpty { "actor" }} · ${MemberConfig.rosterBodyLabel(actor.body).ifEmpty { "channel member" }}" },
                                    color = AtollColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            StatusPill(actor.presence, when (actor.presence) { "在线" -> Tone.Lagoon; "未绑定" -> Tone.Waiting; else -> Tone.Neutral })
                        }
                        if (index < rows.lastIndex) HorizontalDivider(Modifier.padding(start = 66.dp), color = AtollColors.LineSoft)
                    }
                } }
            }
        }
        if (onAdd != null) Button(onAdd, enabled = state.online && state.writable,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 20.dp)
                .height(52.dp).shadow(6.dp, RoundedCornerShape(18.dp)), shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AtollColors.Ink, contentColor = Color.White),
            contentPadding = PaddingValues(start = 16.dp, end = 20.dp)) {
            Icon(Icons.Outlined.Add, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
            Text("添加成员", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Same-window overlay: its scrolling form stays above the host's consumed IME insets. */
@Composable
fun AddMemberOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().background(AtollColors.Ink.copy(alpha = .32f)).clickable(onClick = onDismiss))
        Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.92f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = AtollColors.Surface) {
            Column {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("添加成员", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    IconButton(onDismiss, Modifier.size(44.dp)) { Icon(Icons.Outlined.Close, "关闭添加成员", Modifier.size(20.dp)) }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) { content() }
            }
        }
    }
}
