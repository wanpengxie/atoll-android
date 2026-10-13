package dev.atoll.app.ui.benchmark

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.theme.AtollTheme

/** Benchmark-only control: no networking, Markdown, cards, or mutable data. */
class TextListBaselineActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtollTheme {
                val rows = remember { List(1000) { index ->
                    "消息 $index\n这是纯文本列表的滚动基线。\n相同字号、固定内容，只有 Text 和 LazyColumn。"
                } }
                val list = rememberLazyListState(initialFirstVisibleItemIndex =
                    if (intent.getBooleanExtra("start_at_bottom", false)) rows.lastIndex else 0)
                LazyColumn(Modifier.fillMaxSize().safeDrawingPadding(), state = list, contentPadding = PaddingValues(12.dp)) {
                    items(rows, key = { it }, contentType = { "text" }) { row ->
                        Text(row, Modifier.fillMaxWidth().padding(vertical = 12.dp), fontSize = 14.sp, lineHeight = 21.sp)
                    }
                }
            }
        }
    }
}
