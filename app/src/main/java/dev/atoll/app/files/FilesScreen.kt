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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.atoll.app.ui.components.*
import dev.atoll.app.ui.theme.AtollColors
import dev.atoll.app.ui.theme.AtollMono
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
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 44.dp) {
    Surface(modifier.fillMaxSize(), color = AtollColors.Ground) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                var devicesExpanded by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).background(AtollColors.Surface).border(1.dp, AtollColors.ControlLine, RoundedCornerShape(10.dp))
                        .clickable(enabled = state.devices.isNotEmpty()) { devicesExpanded = true }.padding(start = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Default.Devices, null, Modifier.size(16.dp), tint = AtollColors.Muted)
                        Text(state.devices.firstOrNull { it.id == state.deviceId }?.name ?: "未挂载设备", fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = AtollColors.Muted)
                    }
                    DropdownMenu(expanded = devicesExpanded, onDismissRequest = { devicesExpanded = false }) {
                        state.devices.forEach { device -> DropdownMenuItem(text = { Text(device.name + if (device.online == false) " · 离线" else "") }, onClick = { devicesExpanded = false; viewModel.navigate("", device.id) }) }
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showRecent = true }) { Text("最近阅读", fontSize = 13.sp, color = AtollColors.Body) }
                IconButton(onClick = { if (state.devices.isEmpty()) viewModel.initialize() else viewModel.refresh() }) { Icon(Icons.Default.Refresh, "刷新", tint = AtollColors.Muted) }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭文件", tint = AtollColors.Muted) }
            }
            if (state.upload != null) TransferLine("上传", state.upload!!)
            if (state.download != null) TransferLine("下载 ${state.downloadName}", state.download!!)
            if (state.error.isNotEmpty()) NoticeBanner(state.error, Tone.Danger, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            if (state.notice.isNotEmpty()) NoticeBanner(state.notice, Tone.Lagoon, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            if (state.selected != null) {
                FilePreviewPanel(state, viewModel::backPreview, viewModel::closePreview, download, viewModel::openLink, onAttach, Modifier.weight(1f))
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.navigate(FilePaths.parent(state.directory)) }, enabled = state.directory.isNotEmpty()) { Icon(Icons.Default.ArrowBack, "返回上级目录", tint = AtollColors.Muted) }
                    val parts = state.directory.trimEnd('/').split('/').filter { it.isNotEmpty() }
                    TextButton(onClick = { viewModel.navigate("") }) { Text(state.channelName, fontSize = 14.sp, color = if (parts.isEmpty()) AtollColors.Ink else AtollColors.Muted,
                        fontWeight = if (parts.isEmpty()) FontWeight.SemiBold else FontWeight.Normal) }
                    parts.forEachIndexed { index, name ->
                        Text("/", color = AtollColors.Faint)
                        TextButton(onClick = { viewModel.navigate(parts.take(index + 1).joinToString("/", postfix = "/")) }) {
                            Text(name, fontSize = 14.sp, color = if (index == parts.lastIndex) AtollColors.Ink else AtollColors.Muted,
                                fontWeight = if (index == parts.lastIndex) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                val channelRecent = recent.filter { it.channelId == viewModel.channelId }.take(8)
                if (channelRecent.isNotEmpty()) ChipRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("最近查看", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AtollColors.Muted)
                    channelRecent.forEach { reference -> TextChip(reference.name, onClick = { viewModel.open(reference) }) }
                }
                ChipRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    listOf(FileSort.NAME to "名称", FileSort.MODIFIED to "日期", FileSort.SIZE to "大小", FileSort.KIND to "种类").forEach { (key, label) ->
                        TextChip(label + if (state.sort == key) if (state.descending) " ↓" else " ↑" else "", selected = state.sort == key, onClick = { viewModel.sort(key) })
                    }
                }
                Box(Modifier.weight(1f)) {
                key(state.deviceId, state.directory) {
                    val position = remember { viewModel.scrollPosition() }
                    val list = rememberLazyListState(position.first, position.second)
                    val scrollDevice = state.deviceId
                    val scrollDirectory = state.directory
                    DisposableEffect(list) { onDispose { viewModel.rememberScroll(scrollDevice, scrollDirectory, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) } }
                    LazyColumn(state = list, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp)) {
                        itemsIndexed(state.entries, key = { _, entry -> entry.reference.resourceId }) { index, entry ->
                            val ref = entry.reference
                            val first = index == 0
                            val last = index == state.entries.lastIndex
                            val shape = RoundedCornerShape(topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
                                bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp)
                            Column(Modifier.fillMaxWidth().clip(shape).background(AtollColors.Surface)) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { if (entry.directory != null) viewModel.navigate(entry.directory) else viewModel.open(ref) }
                                    .padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(if (entry.directory != null) AtollColors.Subtle else AtollColors.LagoonSurface),
                                        contentAlignment = Alignment.Center) {
                                        if (entry.directory != null) Icon(Icons.Default.Folder, null, Modifier.size(19.dp), tint = AtollColors.Faint)
                                        else Text(ref.name.substringAfterLast('.', "").take(4).uppercase().ifEmpty { "FILE" }, fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold, fontFamily = AtollMono, color = AtollColors.LagoonText)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(ref.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                        Text((if (entry.directory != null) "文件夹" else fileSize(ref.size)) + if (entry.modifiedAt > 0) " · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.modifiedAt))}" else "",
                                            fontSize = 12.sp, color = AtollColors.Muted)
                                    }
                                    if (entry.directory == null) {
                                        if (onAttach != null) IconButton(onClick = { onAttach(ref) }) { Icon(Icons.Default.AttachFile, "选择 ${ref.name}", tint = AtollColors.Lagoon) }
                                        else IconButton(onClick = { download(ref) }, enabled = state.download == null) { Icon(Icons.Default.Download, "下载 ${ref.name}", tint = AtollColors.Muted) }
                                    }
                                    IconButton(onClick = { deleteTarget = ref }) { Icon(Icons.Default.DeleteOutline, "删除 ${ref.name}", tint = AtollColors.Muted) }
                                }
                                if (!last) HorizontalDivider(Modifier.padding(start = 62.dp), color = AtollColors.LineSoft)
                            }
                        }
                        if (state.busy) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), color = AtollColors.Muted) } }
                        if (!state.busy && state.entries.isEmpty()) item { Text(if (state.devices.isEmpty()) "本频道没有挂载设备" else "当前文件夹为空", Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            fontSize = 15.sp, color = AtollColors.Muted, textAlign = TextAlign.Center) }
                        if (state.next.isNotEmpty()) item { TextButton(onClick = viewModel::loadMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("载入更多", color = AtollColors.Lagoon) } }
                    }
                }
                Row(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallFloatingActionButton(onClick = { if (state.deviceId.isNotEmpty()) newFolder = true }, containerColor = AtollColors.Surface, contentColor = AtollColors.Ink,
                        shape = RoundedCornerShape(16.dp)) { Icon(Icons.Default.CreateNewFolder, "新建文件夹") }
                    ExtendedFloatingActionButton(onClick = { if (state.upload == null && state.deviceId.isNotEmpty()) upload.launch(arrayOf("*/*")) },
                        containerColor = AtollColors.Ink, contentColor = Color.White, shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Upload, null); Spacer(Modifier.width(8.dp)); Text("上传", fontWeight = FontWeight.SemiBold)
                    }
                }
                }
            }
        }
    }
    }
    if (newFolder) AlertDialog(onDismissRequest = { newFolder = false }, title = { Text("新建文件夹") }, text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("名称") }, singleLine = true) }, confirmButton = { TextButton(onClick = { viewModel.mkdir(folderName.trim()); folderName = ""; newFolder = false }, enabled = folderName.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = { newFolder = false }) { Text("取消") } })
    deleteTarget?.let { ref -> AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("删除文件") }, text = { Text("确定删除“${ref.name}”吗？文件夹必须为空。") }, confirmButton = { TextButton(onClick = { viewModel.delete(ref); deleteTarget = null }) { Text("删除", color = AtollColors.Danger) } }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } }) }
    if (showRecent) Dialog(onDismissRequest = { showRecent = false }) { Surface(shape = MaterialTheme.shapes.large, color = AtollColors.Surface) { RecentReadsPanel(recent.filter { it.channelId == viewModel.channelId }, { showRecent = false; viewModel.open(it) }, Modifier.heightIn(max = 600.dp)) } }
}

@Composable
private fun TransferLine(label: String, progress: TransferProgress) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).clip(RoundedCornerShape(12.dp)).background(AtollColors.RunningSurface)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label · ${fileSize(progress.bytes)}" + if (progress.total > 0) " / ${fileSize(progress.total)}" else "", fontSize = 13.sp, color = AtollColors.RunningText)
        val fraction = progress.fraction
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = AtollColors.Running, trackColor = AtollColors.RunningTrack)
        else LinearProgressIndicator(Modifier.fillMaxWidth(), color = AtollColors.Running, trackColor = AtollColors.RunningTrack)
    }
}
@Composable
fun RecentReadsPanel(recent: List<FileReference>, onOpen: (FileReference) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp)) {
        Text("最近阅读", style = MaterialTheme.typography.titleLarge)
        LazyColumn {
            items(recent.take(24), key = { it.key }) { ref ->
                Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onOpen(ref) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ref.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text((if (ref.line > 0) "第 ${ref.line} 行 · " else "") + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ref.openedAt)), fontSize = 12.sp, color = AtollColors.Muted)
                }
                HorizontalDivider(color = AtollColors.LineSoft)
            }
        }
        if (recent.isEmpty()) Text("还没有阅读过文件", Modifier.padding(vertical = 16.dp), fontSize = 15.sp, color = AtollColors.Muted)
    }
}
@Composable
fun FilePicker(viewModel: FilesViewModel, onPick: (FileReference) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        FilesScreen(viewModel, Modifier.fillMaxSize().systemBarsPadding(), onClose = onDismiss, onAttach = onPick)
    }
}
