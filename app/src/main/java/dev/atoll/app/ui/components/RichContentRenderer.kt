package dev.atoll.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.content.pm.ApplicationInfo
import android.webkit.*
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.atoll.app.R
import dev.atoll.app.ui.theme.AtollColors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** One attached offscreen WebView, never a list child. Cards draw cached bitmaps only. */
class RichContentRenderer(private val context: Context) {
    data class Result(val bitmap: Bitmap? = null, val error: String? = null)
    private val lock = Any()
    private val cache = LinkedHashMap<String, Result>(16, .75f, true)
    private var pixels = 0L
    private val queue = Mutex()
    private val assets = mutableMapOf<Int, String>()
    var active by mutableStateOf(false)
        private set
    var size by mutableStateOf(IntSize(1, 1))
        private set
    private var view: WebView? = null
    private var pageReady: CompletableDeferred<Unit>? = null
    private var closed = false
    private var pageLoaded = false
    private val loadedRuntimes = mutableSetOf<String>()
    private var renderId = 0L
    private fun key(kind: String, source: String, width: Int) = MessageDigest.getInstance("SHA-256")
        .digest("$kind:$width:$source".toByteArray()).joinToString("") { "%02x".format(it) }
    fun peek(kind: String, source: String, width: Int): Result? = synchronized(lock) { cache[key(kind, source, normalizedWidth(kind, width))] }
    @SuppressLint("SetJavaScriptEnabled")
    fun webView(): WebView = view ?: WebView(context).also { web ->
        view = web
        web.setBackgroundColor(android.graphics.Color.WHITE)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true; allowFileAccess = false; allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false; domStorageEnabled = false
            blockNetworkLoads = true; cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(false)
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun onPageFinished(view: WebView?, url: String?) { pageReady?.complete(Unit) }
        }
    }
    private suspend fun asset(id: Int): String = assets[id] ?: withContext(Dispatchers.IO) {
        GZIPInputStream(context.resources.openRawResource(id)).bufferedReader().use { it.readText() }
    }.also { assets[id] = it }
    private fun normalizedWidth(kind: String, width: Int) =
        if (kind == "math") width.coerceAtLeast((250 * context.resources.displayMetrics.density).toInt()) else width.coerceAtLeast(1)
    private suspend fun WebView.evaluate(script: String): String = suspendCancellableCoroutine { continuation ->
        evaluateJavascript(script) { if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(it)) }
    }
    private suspend fun loadPage(web: WebView) {
        if (pageLoaded) return
        pageReady = CompletableDeferred()
        web.loadDataWithBaseURL("https://atoll-render.invalid/", PAGE, "text/html", "UTF-8", null)
        pageReady!!.await()
        pageLoaded = true
    }
    private suspend fun loadRuntime(web: WebView, runtime: String, installation: String) {
        if (runtime in loadedRuntimes) return
        check(web.evaluate(installation) == "true") { "无法加载绘图运行时" }
        loadedRuntimes += runtime
    }
    suspend fun render(kind: String, source: String, width: Int): Result {
        val renderWidth = normalizedWidth(kind, width)
        peek(kind, source, width)?.let { return it }
        return queue.withLock {
            peek(kind, source, width)?.let { return@withLock it }
            val startedAt = SystemClock.elapsedRealtime()
            val runtime = if (kind == "mermaid") "mermaid" else "katex"
            val cold = runtime !in loadedRuntimes
            val result = try {
                // Decompression and encoding the large runtime never run on the UI thread.
                val installation = if (cold) {
                    val script = asset(if (runtime == "mermaid") R.raw.rich_mermaid else R.raw.rich_katex)
                    val css = if (runtime == "katex") asset(R.raw.rich_katex_style) else ""
                    withContext(Dispatchers.Default) {
                        """(()=>{const style=document.createElement('style');style.textContent=${JsonPrimitive(css)};document.head.appendChild(style);const script=document.createElement('script');script.textContent=${JsonPrimitive(script)};document.head.appendChild(script);${if (runtime == "mermaid") "mermaid.initialize({startOnLoad:false,securityLevel:'strict',suppressErrorRendering:true,theme:'neutral'});" else ""}return typeof window.$runtime!=='undefined';})()"""
                    }
                } else ""
                val id = ++renderId
                val command = withContext(Dispatchers.Default) { "window.renderItem($id,${JsonPrimitive(kind)},${JsonPrimitive(source)});" }
                withContext(Dispatchers.Main) {
                    withTimeout(15000) {
                        check(!closed)
                        active = true
                        val web = webView()
                        web.measure(View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY))
                        web.layout(0, 0, renderWidth, 1)
                        size = IntSize(renderWidth, 1)
                        loadPage(web)
                        loadRuntime(web, runtime, installation)
                        web.evaluate(command)
                        var ready: JsonObject? = null
                        while (ready == null) {
                            val value = web.evaluate("JSON.stringify(window.renderResult||null)")
                            if (value != "null") {
                                val decoded = Json.parseToJsonElement(value).jsonPrimitive.content
                                if (decoded != "null") ready = Json.parseToJsonElement(decoded).jsonObject
                            }
                            if (ready == null) delay(16)
                        }
                        ready["error"]?.jsonPrimitive?.content?.let { Result(error = it) } ?: run {
                            val density = context.resources.displayMetrics.density
                            val height = (ready["height"]!!.jsonPrimitive.int * density).toInt().coerceIn(1, 32768)
                            web.measure(View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            web.layout(0, 0, renderWidth, height)
                            size = IntSize(renderWidth, height)
                            // Wait for Chromium's current layout to be ready to draw, not a fixed delay.
                            suspendCancellableCoroutine<Unit> { continuation ->
                                web.postVisualStateCallback(id, object : WebView.VisualStateCallback() {
                                    override fun onComplete(requestId: Long) { if (continuation.isActive) continuation.resumeWith(kotlin.Result.success(Unit)) }
                                })
                            }
                            val bitmapWidth = if (kind == "math") (ready["width"]!!.jsonPrimitive.int * density).toInt().coerceIn(1, renderWidth) else renderWidth
                            val scale = minOf(1.0, kotlin.math.sqrt(8_000_000.0 / (bitmapWidth.toDouble() * height))).toFloat()
                            val bitmap = Bitmap.createBitmap((bitmapWidth * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(bitmap)
                            canvas.scale(scale, scale)
                            web.draw(canvas)
                            Result(bitmap = bitmap)
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) { Result(error = "绘图超时") }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { Result(error = error.message ?: "无法渲染这张图") }
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                Log.d("AtollRichRender", "kind=$kind cold=$cold ms=${SystemClock.elapsedRealtime() - startedAt} size=${result.bitmap?.width}x${result.bitmap?.height}")
            }
            synchronized(lock) {
                cache[key(kind, source, renderWidth)] = result
                pixels += result.bitmap?.let { it.width.toLong() * it.height } ?: 0
                while (cache.size > 48 || pixels > 8_000_000) {
                    val iterator = cache.entries.iterator()
                    val oldest = iterator.next().value
                    pixels -= oldest.bitmap?.let { it.width.toLong() * it.height } ?: 0
                    iterator.remove()
                }
            }
            result
        }
    }
    private companion object {
        val PAGE = """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; font-src data:; img-src data:"><style>html,body{margin:0;padding:0;color:#2e2419;background:white;font:14px sans-serif}#stage{padding:4px;box-sizing:border-box}svg{max-width:100%;height:auto}.katex-display{margin:0}</style><div id="stage"></div><script>
        window.renderItem=async function(id,kind,source){
          window.renderResult=null;window.activeRender=id;
          const stage=document.getElementById('stage');stage.replaceChildren();
          stage.style.display=kind==='math'?'inline-block':'block';stage.style.whiteSpace=kind==='math'?'nowrap':'normal';
          try{
            if(kind==='mermaid'){const result=await mermaid.render('diagram'+id,source);if(window.activeRender!==id)return;stage.innerHTML=result.svg;}
            else{katex.render(source,stage,{displayMode:kind==='math-display',throwOnError:true,strict:false,trust:false});}
            await document.fonts.ready;
            await new Promise(resolve=>requestAnimationFrame(resolve));
            if(window.activeRender===id){const rect=stage.getBoundingClientRect();window.renderResult={height:Math.ceil(rect.height),width:Math.ceil(rect.width)};}
          }catch(error){if(window.activeRender===id)window.renderResult={error:String(error.message||error)};}
        };</script>"""
    }
    fun close() { closed = true; pageReady?.cancel(); view?.destroy(); view = null; synchronized(lock) { cache.clear(); pixels = 0 }; assets.clear() }
}
internal val LocalRichRenderer = staticCompositionLocalOf<RichContentRenderer?> { null }
@Composable
fun RichContentHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val renderer = remember(context) { RichContentRenderer(context) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    CompositionLocalProvider(LocalRichRenderer provides renderer) {
        Box {
            content()
            if (renderer.active) {
                val density = LocalDensity.current
                AndroidView(factory = { renderer.webView() }, modifier = Modifier.requiredSize(
                    with(density) { renderer.size.width.toDp() }, with(density) { renderer.size.height.toDp() }).alpha(0f))
            }
        }
    }
}
@Composable
internal fun rememberRichResult(kind: String, source: String, width: Int): RichContentRenderer.Result? {
    val renderer = LocalRichRenderer.current
    var result by remember(kind, source, width, renderer) { mutableStateOf(renderer?.peek(kind, source, width)) }
    LaunchedEffect(kind, source, width, renderer) { if (result == null) result = renderer?.render(kind, source, width) }
    return result
}
@Composable
fun RichBitmap(kind: String, source: String, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val result = rememberRichResult(kind, source, width)
        val bitmap = result?.bitmap
        val naturalHeight = bitmap?.let { maxWidth * it.height.toFloat() / it.width }
        val placeholder = if (kind == "mermaid") 240.dp else if (kind == "math-display") 80.dp else 32.dp
        val height = naturalHeight?.coerceAtMost(600.dp) ?: placeholder
        var expanded by remember(kind, source) { mutableStateOf(false) }
        Column {
            val frame = if (result?.error != null) Modifier.heightIn(min = 44.dp) else Modifier.height(height)
            Box(Modifier.fillMaxWidth().then(frame).clipToBounds().then(if (bitmap != null) Modifier.clickable { expanded = true } else Modifier)) {
                when {
                    bitmap != null -> Image(bitmap.asImageBitmap(), if (kind == "mermaid") "Mermaid 图表" else "数学公式", Modifier.fillMaxWidth().height(naturalHeight!!), alignment = Alignment.TopCenter, contentScale = ContentScale.FillWidth)
                    result?.error != null -> Column(Modifier.padding(8.dp)) {
                        Text(if (kind == "mermaid") "图表语法有误" else "公式语法有误", color = AtollColors.Accent, fontSize = 12.sp)
                        Text(result.error, color = AtollColors.Muted, fontSize = 11.sp, maxLines = 8)
                    }
                    else -> Text(if (kind == "mermaid") "正在绘图…" else "正在渲染公式…", color = AtollColors.Muted, fontSize = 12.sp)
                }
            }
            if (naturalHeight != null && naturalHeight > height) TextButton({ expanded = true }) { Text("查看完整图表", fontSize = 12.sp) }
        }
        if (expanded && bitmap != null) Dialog({ expanded = false }, DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding()) {
                    TextButton({ expanded = false }) { Text("关闭") }
                    BoxWithConstraints(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Image(bitmap.asImageBitmap(), "完整图表", Modifier.fillMaxWidth().height(maxWidth * bitmap.height.toFloat() / bitmap.width), contentScale = ContentScale.FillWidth)
                    }
                }
            }
        }
    }
}
