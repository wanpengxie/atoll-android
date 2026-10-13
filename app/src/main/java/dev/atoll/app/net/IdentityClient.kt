package dev.atoll.app.net

import dev.atoll.app.core.string
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}
class MemoryKeyValueStore : KeyValueStore {
    private val values = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String) = values[key]
    override fun put(key: String, value: String) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
}

/** Cookies and their scope survive process restarts; expired cookies are discarded. */
class PersistentCookieJar(private val store: KeyValueStore) : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    init {
        store.get(KEY)?.let { saved ->
            runCatching {
                Json.parseToJsonElement(saved).jsonArray.forEach {
                    val p = it.jsonObject
                    Cookie.parse(p.getValue("url").jsonPrimitive.content.toHttpUrl(), p.getValue("cookie").jsonPrimitive.content)?.let(cookies::add)
                }
            }
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
        prune()
        return cookies.filter { it.matches(url) }
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (cookie in cookies) {
            this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            this.cookies.add(cookie)
        }
        prune()
        persist()
    }
    @Synchronized fun clear() { cookies.clear(); store.remove(KEY) }
    private fun prune() { if (cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }) persist() }
    private fun persist() {
        store.put(KEY, buildJsonArray {
            cookies.forEach { c -> add(buildJsonObject {
                put("url", HttpUrl.Builder().scheme(if (c.secure) "https" else "http").host(c.domain).build().toString())
                put("cookie", c.toString())
            }) }
        }.toString())
    }
    companion object { private const val KEY = "identity.cookies" }
}

class IdentityException(val status: Int, val code: String, val detail: String) : Exception(detail.ifEmpty { code })

/** Share [client] with Wire and file/pty clients to use the same authenticated session. */
class IdentityClient(baseUrl: String, store: KeyValueStore, httpClient: OkHttpClient = OkHttpClient()) {
    private val base = baseUrl.toHttpUrl()
    val cookieJar = PersistentCookieJar(store)
    val client: OkHttpClient = httpClient.newBuilder().cookieJar(cookieJar).build()
    suspend fun session(): JsonObject = call("session")
    suspend fun login(email: String, password: String): JsonObject = call("login", buildJsonObject {
        put("email", email); put("password", password)
    })
    suspend fun register(email: String, password: String, id: String = "", displayName: String = ""): JsonObject = call("register", buildJsonObject {
        put("email", email); put("password", password)
        if (id.isNotEmpty()) put("id", id)
        if (displayName.isNotEmpty()) put("display_name", displayName)
    })
    suspend fun logout(): JsonObject {
        val result = call("logout", post = true)
        cookieJar.clear()
        return result
    }
    private suspend fun call(action: String, body: JsonObject? = null, post: Boolean = body != null): JsonObject {
        val request = Request.Builder().url(base.resolve("/api/identity/$action")!!)
        if (post) request.post((body?.toString() ?: "").toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWithException(e) }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val parsed = runCatching { Json.parseToJsonElement(it.body?.string() ?: "{}").jsonObject }.getOrDefault(JsonObject(emptyMap()))
                            if (!it.isSuccessful) throw IdentityException(it.code, parsed["code"].string().ifEmpty { "unknown" }, parsed["detail"].string())
                            continuation.resume(parsed)
                        } catch (e: Exception) { continuation.resumeWithException(e) }
                    }
                }
            })
        }
    }
}
