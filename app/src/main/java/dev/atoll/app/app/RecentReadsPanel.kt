package dev.atoll.app.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atoll.app.files.FileReference
import dev.atoll.app.ui.screens.ContentPanel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun RecentReadsPanel(recent: List<FileReference>, onOpen: (FileReference) -> Unit, onClose: () -> Unit) {
    ContentPanel("最近阅读", onClose) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (recent.isEmpty()) item { Text("尚未阅读文件", style = MaterialTheme.typography.bodyMedium) }
            items(recent, key = { it.key }) { reference ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(reference) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(reference.name, style = MaterialTheme.typography.titleSmall)
                    val time = if (reference.openedAt > 0) DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(reference.openedAt)) else ""
                    Text(listOfNotNull(time.takeIf(String::isNotEmpty), reference.line.takeIf { it > 0 }?.let { "第 $it 行" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    Text(reference.resourceId, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
                HorizontalDivider()
            }
        }
    }
}
