package dev.atoll.app.resources

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/** Advanced KV surface; the host mounts it alongside the existing Files screen. */
@Composable
fun ResourcesScreen(viewModel: ResourcesViewModel,modifier: Modifier=Modifier,onClose: (() -> Unit)?=null) {
    val state by viewModel.state.collectAsState()
    var id by remember(viewModel) { mutableStateOf("kv:demo") }
    var args by remember(viewModel) { mutableStateOf("{\"value\":\"hello\"}") }
    val clipboard=LocalClipboardManager.current
    LazyColumn(modifier.fillMaxSize(),contentPadding=PaddingValues(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("KV 资源",Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.titleLarge)
            onClose?.let { TextButton(onClick=it) { Text("关闭") } }
        } }
        if(!state.connected) item { Text("当前会话没有可用的资源操作连接。",color=MaterialTheme.colorScheme.error) }
        if(state.error.isNotEmpty()) item { Text(state.error,color=MaterialTheme.colorScheme.error) }
        item { OutlinedTextField(id,{ id=it },label={ Text("资源 ID") },modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=state.busy.isEmpty()) }
        item { OutlinedTextField(args,{ args=it },label={ Text("Args JSON") },modifier=Modifier.fillMaxWidth(),minLines=4,maxLines=8,enabled=state.busy.isEmpty()) }
        resourceOperations.entries.chunked(3).forEach { operations -> item {
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) { operations.forEach { (op,label) ->
                OutlinedButton(onClick={ viewModel.run(op,id,args) },modifier=Modifier.weight(1f),
                    enabled=state.connected && state.busy.isEmpty() && (op !in resourceWrites || state.writable)) {
                    Text(if(state.busy==op) "处理中…" else label,color=if(op=="delete") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            } }
        } }
        if(state.result!=null) {
            item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                Text("最近结果",Modifier.padding(vertical=12.dp),style=MaterialTheme.typography.titleMedium)
                TextButton(onClick={ clipboard.setText(AnnotatedString(state.resultText)) }) { Text("复制 JSON") }
            } }
            itemsIndexed(state.resultLines) { _,line -> SelectionContainer {
                Text(line,Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)
            } }
        }
    }
}
