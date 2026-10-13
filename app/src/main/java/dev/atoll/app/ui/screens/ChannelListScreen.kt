package dev.atoll.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.atoll.app.ui.theme.AtollColors

@Composable
fun ChannelListScreen(channels: List<ChannelViewState>, connection: String, onOpen: (String) -> Unit,
    onCreate: () -> Unit = {}, onActivity: () -> Unit = {}, onSettings: () -> Unit = {}) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(AtollColors.ChannelRail).safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("atoll", style = MaterialTheme.typography.titleLarge, color = AtollColors.Accent, modifier = Modifier.weight(1f))
            IconButton(onActivity) { Icon(Icons.Outlined.NotificationsNone, "活动中心") }
            IconButton(onSettings) { Icon(Icons.Outlined.Settings, "设置") }
        }
        Text(connection, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = AtollColors.Online, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), placeholder = { Text("搜索频道") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) })
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("我的频道", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onCreate) { Icon(Icons.Outlined.Add, "新建频道") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(channels.filter { it.name.contains(query, ignoreCase = true) }, key = { it.id }) { channel ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(channel.id) }.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("#", color = AtollColors.Muted, style = MaterialTheme.typography.titleLarge)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(channel.name, fontWeight = FontWeight.SemiBold)
                        if (channel.description.isNotBlank()) Text(channel.description, style = MaterialTheme.typography.bodySmall, color = AtollColors.Muted, maxLines = 2)
                        if (channel.running > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(6.dp).background(AtollColors.Online, CircleShape))
                            Text("${channel.running} 个 Agent 运行中", style = MaterialTheme.typography.labelSmall, color = AtollColors.Online)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(channel.updated, style = MaterialTheme.typography.labelSmall, color = AtollColors.Muted)
                        if (channel.unread > 0) Badge { Text(channel.unread.toString()) }
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = AtollColors.Line)
            }
        }
    }
}
