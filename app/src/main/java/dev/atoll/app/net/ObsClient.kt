package dev.atoll.app.net

import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl

class ObsClient(baseUrl: String, private val client: OkHttpClient, private val onUnauthorized: (HttpFailure) -> Unit = {}) {
    private val base = baseUrl.toHttpUrl()
    suspend fun spaceChannels(parentId: String = ""): JsonObject = read("/obs/space/channels", parentId)
    suspend fun spacePrincipals(): JsonObject = read("/obs/space/principals")
    suspend fun spaceDaemons(): JsonObject = read("/obs/space/daemons")
    suspend fun spaceActorDescriptions(): JsonObject = read("/obs/space/actor-descriptions")
    suspend fun channelActors(channelId: String): JsonObject = channel(channelId, "actors")
    suspend fun channelDevices(channelId: String): JsonObject = channel(channelId, "devices")
    suspend fun channelProfile(channelId: String): JsonObject = channel(channelId, "profile")
    private suspend fun channel(id: String, kind: String): JsonObject {
        val url = base.newBuilder().encodedPath("/obs/channel").addPathSegment(id).addPathSegment(kind).build()
        return readUrl(url)
    }
    private suspend fun read(path: String, parentId: String = ""): JsonObject {
        val url = base.resolve(path)!!.newBuilder()
        if (parentId.isNotEmpty()) url.addQueryParameter("parent_id", parentId)
        return readUrl(url.build())
    }
    private suspend fun readUrl(url: HttpUrl): JsonObject = client.consume(Request.Builder().url(url).build()) { response ->
        try { response.requireSuccess() }
        catch (e: HttpFailure) { if (e.status == 401) onUnauthorized(e); throw e }
        Json.parseToJsonElement(response.body?.string() ?: "{}").jsonObject
    }
}
