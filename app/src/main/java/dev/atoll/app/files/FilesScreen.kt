package dev.atoll.app.files

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.atoll.app.net.TransferProgress
import java.text.DateFormat
import java.util.Date

@Composable
fun FilesScreen(viewModel: FilesViewModel, modifier: Modifier = Modifier, onClose: () -> Unit = {}, onAttach: ((FileReference) -> Unit)? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var newFolder by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<FileReference?>(null) }
    var showRecent by remember { mutableStateOf(false) }
    var saveReference by remember { mutableStateOf<FileReference?>(null) }
    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) viewModel.upload(context, uri) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> if (uri != null) saveReference?.let { viewModel.download(context, it, uri) } }
    val download: (FileReference) -> Unit = { ref ->
        if (state.download == null) {
            if (Build.VERSION.SDK_INT >= 29) viewModel.download(context, ref)
            else { saveReference = ref; save.launch(ref.name) }
        }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("文件 · ${state.channelName}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { showRecent = true }) { Text("最近阅读") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭文件") }
            }
            if (state.upload != null) TransferLine("上传", state.upload!!)
            if (state.download != null) TransferLine("下载 ${state.downloadName}", state.download!!)
            if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
            if (state.notice.isNotEmpty()) Text(state.notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            if (state.selected != null) {
                FilePreviewPanel(state, viewModel::backPreview, viewModel::closePreview, download, viewModel::openLink, onAttach, Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.navigate(FilePaths.parent(state.directory)) }, enabled = state.directory.isNotEmpty()) { Icon(Icons.Default.ArrowBack, "返回上级目录") }
                    val parts = state.directory.trimEnd('/').split('/').filter { it.isNotEmpty() }
                    TextButton(onClick = { viewModel.navigate("") }) { Text(state.channelName) }
                    parts.forEachIndexed { index, name ->
                        Text("›")
                        TextButton(onClick = { viewModel.navigate(parts.take(index + 1).joinToString("/", postfix = "/")) }) { Text(name) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    var devicesExpanded by remember { mutableStateOf(false) }
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { devicesExpanded = true }, enabled = state.devices.isNotEmpty()) {
                            Icon(Icons.Default.Devices, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                            Text(state.devices.firstOrNull { it.id == state.deviceId }?.name ?: "未挂载设备")
                        }
                        DropdownMenu(expanded = devicesExpanded, onDismissRequest = { devicesExpanded = false }) {
                            state.devices.forEach { device -> DropdownMenuItem(text = { Text(device.name + if (device.online == false) " · 离线" else "") }, onClick = { devicesExpanded = false; viewModel.navigate("", device.id) }) }
                        }
                    }
                    IconButton(onClick = { newFolder = true }, enabled = state.deviceId.isNotEmpty()) { Icon(Icons.Default.CreateNewFolder, "新建文件夹") }
                    IconButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = state.upload == null && state.deviceId.isNotEmpty()) { Icon(Icons.Default.Upload, "上传") }
                    IconButton(onClick = { if (state.devices.isEmpty()) viewModel.initialize() else viewModel.refresh() }) { Icon(Icons.Default.Refresh, "刷新") }
                }
                val channelRecent = recent.filter { it.channelId == viewModel.channelId }.take(8)
                if (channelRecent.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("最近查看", style = MaterialTheme.typography.labelMedium)
                    channelRecent.forEach { reference -> TextButton(onClick = { viewModel.open(reference) }) { Text(reference.name, maxLines = 1) } }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    listOf(FileSort.NAME to "名称", FileSort.MODIFIED to "日期", FileSort.SIZE to "大小", FileSort.KIND to "种类").forEach { (key, label) ->
                        TextButton(onClick = { viewModel.sort(key) }, modifier = Modifier.weight(1f)) { Text(label + if (state.sort == key) if (state.descending) " ↓" else " ↑" else "", maxLines = 1) }
                    }
                }
                HorizontalDivider()
                key(state.deviceId, state.directory) {
                    val position = remember { viewModel.scrollPosition() }
                    val list = rememberLazyListState(position.first, position.second)
                    val scrollDevice = state.deviceId
                    val scrollDirectory = state.directory
                    DisposableEffect(list) { onDispose { viewModel.rememberScroll(scrollDevice, scrollDirectory, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
                    LazyColumn(state = list, modifier = Modifier.weight(1f)) {
                        items(state.entries, key = { it.reference.resourceId }) { entry ->
                            val ref = entry.reference
                            Row(Modifier.fillMaxWidth().clickable { if (entry.directory != null) viewModel.navigate(entry.directory) else viewModel.open(ref) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (entry.directory != null) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, tint = if (entry.directory != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ref.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text((if (entry.directory != null) "文件夹" else fileSize(ref.size)) + if (entry.modifiedAt > 0) " · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modifiedAt))}" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (entry.directory == null) {
                                    if (onAttach != null) IconButton(onClick = { onAttach(ref) }) { Icon(Icons.Default.AttachFile, "选择 ${ref.name}") }
                                    else IconButton(onClick = { download(ref) }, enabled = state.download == null) { Icon(Icons.Default.Download, "下载 ${ref.name}") }
                                }
                                IconButton(onClick = { deleteTarget = ref }) { Icon(Icons.Default.DeleteOutline, "删除 ${ref.name}", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f))
                        }
                        if (state.busy) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) } }
                        if (!state.busy && state.entries.isEmpty()) item { Text(if (state.devices.isEmpty()) "本频道没有挂载设备" else "当前文件夹为空", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (state.next.isNotEmpty()) item { TextButton(onClick = viewModel::loadMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("载入更多") } }
                    }
                }
            }
        }
    }
    if (newFolder) AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("新建文件夹") }, text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("名称") }, singleLine = true) }, confirmButton = { TextButton(onClick = { viewModel.mkdir(folderName.trim()); folderName = ""; newFolder = false }, enabled = folderName.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = { newFolder = false }) { Text("取消") } })
    deleteTarget?.let { ref -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除文件") }, text = { Text("确定删除“${ref.name}”吗？文件夹必须为空。") }, confirmButton = { TextButton(onClick = { viewModel.delete(ref); deleteTarget = null }) { Text("删除", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }) }
    if (showRecent) Dialog(onDismissRequest = { showRecent = false }) { Surface(shape = MaterialTheme.shapes.large) { RecentReadsPanel(recent.filter { it.channelId == viewModel.channelId }, { showRecent = false; viewModel.open(it) }, Modifier.heightIn(max = 600.dp)) } }
}

@Composable
private fun TransferLine(label: String, progress: TransferProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text("$label · ${fileSize(progress.bytes)}" + if (progress.total > 0) " / ${fileSize(progress.total)}" else "", style = MaterialTheme.typography.labelMedium)
        val fraction = progress.fraction
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable
fun RecentReadsPanel(recent: List<FileReference>, onOpen: (FileReference) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp)) {
        Text("最近阅读", style = MaterialTheme.typography.titleMedium)
        LazyColumn {
            items(recent.take(24), key = { it.key }) { ref ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(ref) }.padding(vertical = 12.dp)) {
                    Text(ref.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (ref.line > 0) "第 ${ref.line} 行 · " else "") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ref.openedAt)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (recent.isEmpty()) Text("还没有阅读过文件", Modifier.padding(vertical = 16.dp))
    }
}
@Composable
fun FilePicker(viewModel: FilesViewModel, onPick: (FileReference) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FilesScreen(viewModel, Modifier.fillMaxSize().systemBarsPadding(), onClose = onDismiss, onAttach = onPick)
    }
}
