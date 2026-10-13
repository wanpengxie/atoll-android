package dev.atoll.app.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import dev.atoll.app.core.string
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Response consumers run on OkHttp's workers; cancellation interrupts body I/O. */
internal suspend fun <T> OkHttpClient.consume(request: Request, read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try { continuation.resume(read(it)) }
                catch (e: Exception) { continuation.resumeWithException(e) }
            }
        }
    })
}

class HttpFailure(val status: Int, val code: String, val detail: String) : Exception(detail.ifEmpty { "$code ($status)" })
internal fun Response.requireSuccess() {
    if (!isSuccessful && code != 304) {
        val json = runCatching { Json.parseToJsonElement(body?.string().orEmpty()).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        throw HttpFailure(code, json["code"].string().ifEmpty { "http_error" }, json["detail"].string())
    }
}
