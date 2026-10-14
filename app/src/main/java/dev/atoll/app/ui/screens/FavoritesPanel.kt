package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.atoll.app.files.FileReference
import dev.atoll.app.ui.components.PageHeader
import dev.atoll.app.ui.components.SegmentedControl
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollShapes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class FavoritesTab { Starred, Recent }

@Composable
fun FavoritesPanel(channelName: String, tab: FavoritesTab, stars: List<StarredMessageViewState>, recent: List<FileReference>,
    onTab: (FavoritesTab) -> Unit, onJump: (String) -> Boolean, onRemove: (String) -> Unit, onOpenFile: (FileReference) -> Unit,
    onClose: () -> Unit, channelNames: Map<String, String> = emptyMap()) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Column(Modifier.fillMaxSize().background(AtollColors.Ground).safeDrawingPadding()) {
            PageHeader("收藏", onClose, "# $channelName")
            SegmentedControl(listOf("星标消息 ${stars.size}", "最近阅读"), if (tab == FavoritesTab.Starred) 0 else 1,
                { onTab(if (it == 0) FavoritesTab.Starred else FavoritesTab.Recent) },
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp), fill = true)
            if (tab == FavoritesTab.Starred) StarredMessagesContent(stars, onJump, onRemove, onClose, Modifier.weight(1f))
            else RecentReadsContent(recent, onOpenFile, Modifier.weight(1f), channelNames)
        }
    }
}

@Composable
fun RecentReadsContent(recent: List<FileReference>, onOpen: (FileReference) -> Unit, modifier: Modifier = Modifier,
    channelNames: Map<String, String> = emptyMap()) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recent.isEmpty()) item { Text("尚未阅读文件", Modifier.padding(16.dp), fontSize = 15.sp, color = AtollColors.Muted) }
        items(recent, key = { it.key }) { reference ->
            Column(Modifier.fillMaxWidth().clip(AtollShapes.Card).background(AtollColors.Surface).border(1.dp, AtollColors.Line, AtollShapes.Card)
                .clickable { onOpen(reference) }.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(reference.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val time = if (reference.openedAt > 0) DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(reference.openedAt)) else ""
                Text(listOfNotNull(channelNames[reference.channelId]?.let { "# $it" }, time.takeIf(String::isNotEmpty),
                    reference.line.takeIf { it > 0 }?.let { "第 $it 行" }).joinToString(" · "), fontSize = 12.sp, color = AtollColors.Muted)
                Text(reference.resourceId, fontSize = 12.sp, color = AtollColors.Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
