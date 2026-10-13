package dev.atoll.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollColors

/** These facts come from the source; an empty filtered window cannot prove an empty channel. */
@Composable
fun TimelineEmptyFeedback(kind: TimelineEmptyKind, settled: Boolean, modifier: Modifier = Modifier) {
    if (kind == TimelineEmptyKind.Channel && !settled) {
        Row(modifier.fillMaxWidth().padding(24.dp).semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(10.dp), color = AtollColors.Muted, strokeWidth = 1.dp)
            Spacer(Modifier.width(6.dp))
            Text("正在准备频道内容…", fontSize = 10.sp, color = AtollColors.Muted)
        }
        return
    }
    val title: String
    val detail: String
    when {
        !settled -> {
            title = "当前已加载的动态里没有符合筛选的往来"
            detail = "会继续读取更早内容，找到后自动显示。"
        }
        kind == TimelineEmptyKind.Channel -> {
            title = "这本账还没有可见条目"
            detail = "从下方编辑器开始一段往来。"
        }
        kind == TimelineEmptyKind.ActorFilter -> {
            title = "已扫描到频道开头，没有符合当前成员筛选的往来"
            detail = "这不表示频道为空；移除成员筛选可查看当前范围。"
        }
        else -> {
            title = "这个频道里还没有与你相关的往来"
            detail = "切回「全部」可以看到频道里其他人的动态。"
        }
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Surface(Modifier.size(48.dp), color = AtollColors.MutedSurface, shape = RoundedCornerShape(12.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Text(if (kind == TimelineEmptyKind.Channel) "#" else "@", color = AtollColors.Accent, fontSize = 22.sp)
            }
        }
        Spacer(Modifier.height(15.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(7.dp))
        Text(detail, fontSize = 12.sp, color = AtollColors.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun TimelineHistoryStart(label: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 35.dp).padding(start = 26.dp, end = 26.dp, top = 6.dp, bottom = 4.dp),
        contentAlignment = Alignment.TopCenter) {
        Surface(shape = RoundedCornerShape(50), color = AtollColors.Workspace,
            border = BorderStroke(1.dp, AtollColors.ControlLine)) {
            Text(label, Modifier.padding(horizontal = 10.dp, vertical = 5.dp).semantics { liveRegion = LiveRegionMode.Polite },
                color = AtollColors.Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
    }
}
