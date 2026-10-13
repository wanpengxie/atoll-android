package dev.atoll.app.tasks

import dev.atoll.app.core.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

internal class TaskTestWire: WireClient {
    override val state=MutableStateFlow<WireState>(WireState.Attached(1,buildJsonObject { put("session","screen") }))
    override val frames=MutableSharedFlow<DownFrame>(extraBufferCapacity=32)
    val requests=mutableListOf<Pair<String,JsonObject>>()
    var handle: suspend(String,JsonObject)->JsonObject = { type,payload ->
        if (type==Up.SUBMIT && payload.text("msg_type")=="actor.describe") {
            frames.emit(DownFrame.Feed("",Row(payload.text("channel_id"),10,Envelope(buildJsonObject {
                put("id","describe-response");put("channel_id",payload.text("channel_id"));put("kind","response");put("type","actor.describe");put("parent_id",payload.text("id"))
                put("payload",buildJsonObject { put("body",buildJsonObject { put("status","completed");put("value",buildJsonObject { put("words",buildJsonObject { put("task.create",buildJsonObject {});put("agent.ask",buildJsonObject {}) }) }) }) })
            }))))
        }
        buildJsonObject { put("id",payload.text("id"));put("timer_id","uuid-1") }
    }
    override suspend fun request(type: String,payload: JsonObject): JsonObject { requests.add(type to payload);return handle(type,payload) }
    override suspend fun history(payload: JsonObject)=HistoryPage(emptyList(),Envelope.EMPTY)
}
