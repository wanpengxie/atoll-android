package dev.atoll.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.webkit.*
import android.view.View
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
    private fun key(kind: String, source: String, width: Int) = MessageDigest.getInstance("SHA-256")
        .digest("$kind:$width:$source".toByteArray()).joinToString("") { "%02x".format(it) }
    fun peek(kind: String, source: String, width: Int): Result? = synchronized(lock) { cache[key(kind, source, width)] }
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
    suspend fun render(kind: String, source: String, width: Int): Result {
        peek(kind, source, width)?.let { return it }
        return queue.withLock {
            peek(kind, source, width)?.let { return@withLock it }
            val result = try {
                val script = asset(if (kind == "mermaid") R.raw.rich_mermaid else R.raw.rich_katex)
                val css = if (kind == "mermaid") "" else asset(R.raw.rich_katex_style)
                val encoded = JsonPrimitive(source).toString().replace("<", "\\u003c")
                val command = if (kind == "mermaid") "mermaid.initialize({startOnLoad:false,securityLevel:'strict',suppressErrorRendering:true,theme:'neutral'}); const r=await mermaid.render('diagram', $encoded); document.getElementById('stage').innerHTML=r.svg;" else
                    "katex.render($encoded,document.getElementById('stage'),{displayMode:${kind == "math-display"},throwOnError:true,strict:false,trust:false});"
                val html = withContext(Dispatchers.Default) { """<!doctype html><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; font-src data:; img-src data:"><style>$css html,body{margin:0;padding:0;color:#2e2419;background:white;font:14px sans-serif}#stage{padding:4px;box-sizing:border-box;${if (kind == "math") "display:inline-block;white-space:nowrap" else ""}}svg{max-width:100%;height:auto}.katex-display{margin:0}</style><div id="stage"></div><script>${script.replace("</script", "<\\/script")}</script><script>(async()=>{try{$command await document.fonts.ready; window.renderResult={height:Math.ceil(document.getElementById('stage').getBoundingClientRect().height),width:Math.ceil(document.getElementById('stage').getBoundingClientRect().width)};}catch(e){window.renderResult={error:String(e.message||e)};}})();</script>""" }
                withContext(Dispatchers.Main) {
                    check(!closed)
                    active = true
                    val web = webView()
                    val density = context.resources.displayMetrics.density
                    val renderWidth = if (kind == "math") width.coerceAtLeast((250 * density).toInt()) else width
                    web.measure(View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1, View.MeasureSpec.EXACTLY))
                    web.layout(0, 0, renderWidth, 1)
                    size = IntSize(renderWidth, 1)
                    pageReady = CompletableDeferred()
                    web.loadDataWithBaseURL("https://atoll-render.invalid/", html, "text/html", "UTF-8", null)
                    withTimeout(15000) {
                        pageReady!!.await()
                        var ready: JsonObject? = null
                        while (ready == null) {
                            val encodedResult = CompletableDeferred<String>()
                            web.evaluateJavascript("JSON.stringify(window.renderResult||null)") { encodedResult.complete(it) }
                            val value = encodedResult.await()
                            if (value != "null") {
                                val decoded = Json.parseToJsonElement(value).jsonPrimitive.content
                                if (decoded != "null") ready = Json.parseToJsonElement(decoded).jsonObject
                            }
                            if (ready == null) delay(32)
                        }
                        ready["error"]?.jsonPrimitive?.content?.let { Result(error = it) } ?: run {
                            val height = (ready["height"]!!.jsonPrimitive.int * density).toInt().coerceIn(1, 4096)
                            web.measure(View.MeasureSpec.makeMeasureSpec(renderWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            web.layout(0, 0, renderWidth, height)
                            size = IntSize(renderWidth, height)
                            delay(64)
                            val bitmapWidth = if (kind == "math") (ready["width"]!!.jsonPrimitive.int * density).toInt().coerceIn(1, renderWidth) else renderWidth
                            val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                            web.draw(Canvas(bitmap))
                            Result(bitmap = bitmap)
                        }
                    }
                }
            } catch (timeout: TimeoutCancellationException) { Result(error = "绘图超时") }
              catch (cancelled: CancellationException) { throw cancelled }
              catch (error: Exception) { Result(error = error.message ?: "无法渲染这张图") }
            synchronized(lock) {
                cache[key(kind, source, width)] = result
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
    fun close() { closed = true; pageReady?.cancel(); view?.destroy(); view = null; synchronized(lock) { cache.clear(); pixels = 0 }; assets.clear() }
}
private val LocalRichRenderer = staticCompositionLocalOf<RichContentRenderer?> { null }
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
fun RichBitmap(kind: String, source: String, modifier: Modifier = Modifier) {
    val renderer = LocalRichRenderer.current
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
        var result by remember(kind, source, width, renderer) { mutableStateOf(renderer?.peek(kind, source, width)) }
        LaunchedEffect(kind, source, width, renderer) {
            if (result == null) result = renderer?.render(kind, source, width)
        }
        // Stable media frame is retained across loading, source toggles and cache remounts.
        val height = if (kind == "mermaid") 240.dp else if (kind == "math-display") 80.dp else 32.dp
        Box(Modifier.fillMaxWidth().height(height)) {
            val current = result
            when {
                current?.bitmap != null -> Image(current.bitmap.asImageBitmap(), if (kind == "mermaid") "Mermaid 图表" else "数学公式", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                current?.error != null -> Column(Modifier.padding(8.dp)) {
                    Text(if (kind == "mermaid") "图表语法有误" else "公式语法有误", color = AtollColors.Accent, fontSize = 12.sp)
                    Text(current.error, color = AtollColors.Muted, fontSize = 11.sp, maxLines = 8)
                }
                else -> Text(if (kind == "mermaid") "正在绘图…" else "正在渲染公式…", color = AtollColors.Muted, fontSize = 12.sp)
            }
        }
    }
}
