package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.ChatColors

@Composable
fun ReconnectNotice(onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(ChatColors.Bar)
        .semantics { liveRegion = LiveRegionMode.Polite }
        .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.WifiOff, null, Modifier.size(18.dp), tint = ChatColors.Red)
        Text("连接中断。你仍可浏览已缓存的记录，消息会先存在本机。", Modifier.weight(1f),
            color = ChatColors.Body, fontSize = 13.sp, lineHeight = 19.sp)
        TextButton(onRetry, Modifier.heightIn(min = 44.dp), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp)) {
            Text("立即重试", color = ChatColors.Link, fontSize = 13.sp)
        }
    }
}
