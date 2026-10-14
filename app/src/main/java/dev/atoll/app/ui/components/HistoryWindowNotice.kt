package dev.atoll.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.ChatColors

/** Detached history remains readable; returning to the tail is owned by the channel controller. */
@Composable
fun HistoryWindowNotice(onLatest: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(ChatColors.Bar).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("正在看较早的记录。这里不显示运行和等待状态。", Modifier.weight(1f),
            color = ChatColors.Gray, fontSize = 13.sp, lineHeight = 18.sp)
        TextButton(onLatest, Modifier.heightIn(min = 44.dp), contentPadding = PaddingValues(horizontal = 12.dp)) {
            Text("回到最新", color = ChatColors.Link, fontSize = 13.sp)
        }
    }
}
