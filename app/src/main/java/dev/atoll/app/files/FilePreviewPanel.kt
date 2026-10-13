package dev.atoll.app.files

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.atoll.app.ui.components.Markdown
import androidx.compose.ui.platform.LocalUriHandler
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File

@Composable
fun FilePreviewPanel(state: FilesState, onBack: () -> Unit, onClose: () -> Unit, onDownload: (FileReference) -> Unit, onOpenLink: (String) -> Boolean, onAttach: ((FileReference) -> Unit)? = null, modifier: Modifier = Modifier) {
    val reference = state.selected ?: return
    val loaded = state.preview
    var source by rememberSaveable(reference.key) { mutableStateOf(false) }
    val rich = loaded?.kind in setOf(PreviewKind.MARKDOWN, PreviewKind.HTML)
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = if (state.canGoBack) onBack else onClose) { Text("‹ 返回") }
            Text(reference.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (onAttach != null) TextButton(onClick = { onAttach(reference) }) { Text("选择") }
            TextButton(onClick = { onDownload(reference) }, enabled = state.download == null) { Text("下载") }
        }
        if (rich || loaded?.text != null) Row(Modifier.padding(horizontal = 12.dp)) {
            if (rich) {
                TextButton(onClick = { source = false }) { Text("预览", color = if (!source) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
                TextButton(onClick = { source = true }) { Text("源码", color = if (source) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (loaded?.text != null) TextButton(onClick = { clipboard.setText(AnnotatedString(loaded.text)) }) { Text("复制") }
        }
        HorizontalDivider()
        when {
            state.previewLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.previewError.isNotEmpty() -> Column(Modifier.padding(24.dp)) {
                Text("预览暂不可用", style = MaterialTheme.typography.titleMedium)
                Text(state.previewError, Modifier.padding(vertical = 12.dp))
                OutlinedButton(onClick = { onDownload(reference) }) { Text("下载原文件") }
            }
            loaded != null -> when (loaded.kind) {
                PreviewKind.IMAGE -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), contentAlignment = Alignment.Center) { AsyncImage(model = loaded.file, contentDescription = reference.name, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp)) }
                PreviewKind.VIDEO, PreviewKind.AUDIO -> MediaPreview(loaded.file, Modifier.fillMaxSize())
                PreviewKind.PDF -> PdfPreview(loaded.file, Modifier.fillMaxSize())
                PreviewKind.HTML -> if (source) SourcePreview(loaded.text.orEmpty(), reference.line, Modifier.fillMaxSize()) else HtmlPreview(loaded.text.orEmpty(), Modifier.fillMaxSize())
                PreviewKind.MARKDOWN -> if (source) SourcePreview(loaded.text.orEmpty(), reference.line, Modifier.fillMaxSize()) else Markdown("file:${reference.key}", loaded.text.orEmpty(), Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), onLink = { href ->
                    if (!onOpenLink(href) && (href.startsWith("https://") || href.startsWith("http://"))) runCatching { uriHandler.openUri(href) }
                })
                else -> SourcePreview(loaded.text.orEmpty(), reference.line, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MediaPreview(file: File, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(file) { ExoPlayer.Builder(context).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file))); prepare() } }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = true; controllerShowTimeoutMs = 0 } }, modifier = modifier)
}

@Composable
private fun HtmlPreview(html: String, modifier: Modifier) {
    AndroidView(factory = { context -> WebView(context).apply {
        settings.javaScriptEnabled = false; settings.domStorageEnabled = false
        settings.allowFileAccess = false; settings.allowContentAccess = false
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
    } }, update = { if (it.tag != html) { it.tag = html; it.loadDataWithBaseURL(null, "<meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font:16px sans-serif;overflow-wrap:anywhere}img{max-width:100%}</style>$html", "text/html", "UTF-8", null) } }, onRelease = { it.destroy() }, modifier = modifier)
}

@Composable
private fun PdfPreview(file: File, modifier: Modifier) {
    var pageIndex by remember(file) { mutableIntStateOf(0) }
    var pages by remember(file) { mutableIntStateOf(0) }
    var error by remember(file) { mutableStateOf("") }
    val bitmap by produceState<Bitmap?>(null, file, pageIndex) {
        value = null
        try {
            value = withContext(Dispatchers.IO) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { renderer ->
                    pages = renderer.pageCount
                    renderer.openPage(pageIndex.coerceIn(0, (pages - 1).coerceAtLeast(0))).use { page ->
                        val width = minOf(page.width * 2, 1600)
                        val height = (page.height.toDouble() * width / page.width).toInt().coerceAtMost(2400)
                        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.WHITE); page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
                    }
                } }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "PDF 无法读取" }
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { pageIndex-- }, enabled = pageIndex > 0) { Text("上一页") }
            Text("${pageIndex + 1} / $pages")
            TextButton(onClick = { pageIndex++ }, enabled = pageIndex + 1 < pages) { Text("下一页") }
        }
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        val current = bitmap
        if (current != null) Image(current.asImageBitmap(), "PDF 第 ${pageIndex + 1} 页", Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp))
        else if (error.isEmpty()) CircularProgressIndicator(Modifier.padding(24.dp))
    }
}

@Composable
private fun SourcePreview(text: String, initialLine: Int, modifier: Modifier) {
    val lines by produceState<List<String>>(emptyList(), text) { value = withContext(Dispatchers.Default) { text.split('\n') } }
    var target by rememberSaveable(text) { mutableIntStateOf(initialLine) }
    var jump by rememberSaveable(text) { mutableStateOf(if (initialLine > 0) initialLine.toString() else "") }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(target, lines) { if (target in 1..lines.size) list.scrollToItem(target - 1) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(jump, { jump = it.filter(Char::isDigit) }, label = { Text("跳到行") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { target = jump.toIntOrNull() ?: 0; if (target in 1..lines.size) scope.launch { list.scrollToItem(target - 1) } }) { Text("跳转") }
            Text("${lines.size} 行", style = MaterialTheme.typography.labelSmall)
        }
        if (target > lines.size) Text("目标第 $target 行超出文件范围（共 ${lines.size} 行）", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            itemsIndexed(lines) { index, line ->
                Row(Modifier.fillMaxWidth().background(if (index + 1 == target) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(end = 16.dp, top = 2.dp, bottom = 2.dp)) {
                    Text((index + 1).toString(), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(60.dp).padding(end = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                    Text(remember(line) { highlightSource(line) }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
