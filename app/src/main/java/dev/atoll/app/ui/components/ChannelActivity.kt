package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.screens.TimelineEntry
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.ChatColors

/** Counts stay cheap while closed: individual activity rows compose only when expanded. */
@Composable
fun ChannelActivity(group: TimelineEntry.ChannelActivity) {
    var expanded by rememberSaveable(group.key) { mutableStateOf(false) }
    val title = "频道活动 ${group.items.size} 条"
    if (!expanded) Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.heightIn(min = 28.dp).padding(horizontal = 24.dp).clickable { expanded = true }.semantics {
                contentDescription = "展开 ${group.items.size} 条频道活动"; stateDescription = "已折叠"
            }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title + " · " + group.items.takeLast(2).joinToString(" · ") { it.text }, fontSize = 12.sp,
                color = if (group.items.any { it.failed }) ChatColors.Red else ChatColors.Gray,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable { expanded = false }.semantics {
            contentDescription = "收起 ${group.items.size} 条频道活动"; stateDescription = "已展开"
        }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 12.sp, color = ChatColors.Gray)
            Text("收起", fontSize = 12.sp, color = ChatColors.Link)
        }
        group.items.forEach { item -> key(item.id) {
            Text(item.text + item.time.takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty(), fontSize = 12.sp, lineHeight = 18.sp,
                color = if (item.failed) ChatColors.Red else ChatColors.Gray)
        } }
    }
}
