package dev.atoll.app.resources

import androidx.lifecycle.ViewModel
import dev.atoll.app.app.WorkspaceServices
import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

val resourceOperations=linkedMapOf("create" to "创建","read" to "读取","write" to "写入","stat" to "状态","list" to "列出","delete" to "删除")
internal val resourceWrites=setOf("create","write","delete")

/** Payloads match the web KV form; list deliberately has no resource_id. */
fun resourceFormPayload(channelId: String,op: String,resourceId: String,argsJson: String):JsonObject {
    require(channelId.isNotBlank() && op in resourceOperations) { "频道或资源操作无效" }
    require(op=="list" || resourceId.trim().isNotEmpty()) { "资源 ID 不能为空" }
    val args=if (op in setOf("create","write")) {
        val value=try { Json.parseToJsonElement(argsJson) } catch (_:Exception) { throw IllegalArgumentException("KV Args JSON 格式无效") }
        value as? JsonObject ?: throw IllegalArgumentException("KV Args 必须是 JSON 对象")
    } else null
    return buildJsonObject {
        put("channel_id",channelId);put("op",op)
        if(op!="list") put("resource_id",resourceId.trim())
        if(args!=null) put("args",args)
    }
}
data class ResourcesState(val connected: Boolean=false,val writable: Boolean=false,val busy: String="",
    val error: String="",val result: JsonObject?=null,val resultText: String="",val resultLines: List<String> = emptyList())

class ResourcesViewModel(val services: WorkspaceServices,val channelId: String,dispatcher: CoroutineDispatcher=Dispatchers.Main.immediate):ViewModel(),AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val mutable=MutableStateFlow(ResourcesState())
    val state: StateFlow<ResourcesState> = mutable.asStateFlow()
    init { scope.launch(start=CoroutineStart.UNDISPATCHED) { services.wire.state.collect { mutable.value=state.value.copy(connected=it is WireState.Attached) } } }
    fun context(writable: Boolean) { mutable.value=state.value.copy(writable=writable) }
    fun run(op: String,resourceId: String,argsJson: String):Job? {
        if(state.value.busy.isNotEmpty()) return null
        val payload=try {
            check(state.value.connected) { "当前连接不可用" }
            check(op !in resourceWrites || state.value.writable) { "当前频道不可写" }
            resourceFormPayload(channelId,op,resourceId,argsJson)
        } catch(e:Exception) { mutable.value=state.value.copy(error=e.message.orEmpty());return null }
        mutable.value=state.value.copy(busy=op,error="")
        return scope.launch {
            try {
                val result=services.wire.request(Up.RESOURCE,payload)
                check((result["status"] as? JsonPrimitive)?.content!="rejected") { (result["detail"] as? JsonPrimitive)?.content ?: "资源操作被拒绝" }
                val text=withContext(Dispatchers.Default) { Json { prettyPrint=true }.encodeToString(JsonObject.serializer(),result) }
                mutable.value=state.value.copy(result=result,resultText=text,resultLines=text.lines())
            } catch(e:CancellationException) { throw e }
            catch(e:Exception) { mutable.value=state.value.copy(error=e.message.orEmpty()) }
            finally { mutable.value=state.value.copy(busy="") }
        }
    }
    override fun close() { scope.cancel() }
    override fun onCleared() { close() }
}
