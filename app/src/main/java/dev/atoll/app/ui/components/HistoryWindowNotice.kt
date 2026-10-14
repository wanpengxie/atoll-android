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

/** Detached history remains readable; returning to the tail is owned by the channel controller. */
@Composable
fun HistoryWindowNotice(onLatest: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
        .background(AtollColors.RunningSurface, RoundedCornerShape(12.dp))
        .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Outlined.Schedule, null, Modifier.size(16.dp), tint = AtollColors.RunningInk)
        Text("正在看较早的记录。这里不显示运行和等待状态。", Modifier.weight(1f),
            color = AtollColors.RunningInk, fontSize = 13.sp, lineHeight = 18.sp)
        TextButton(onLatest, Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
            Text("回到最新", color = AtollColors.RunningText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
