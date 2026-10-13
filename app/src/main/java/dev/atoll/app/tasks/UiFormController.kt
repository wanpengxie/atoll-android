package dev.atoll.app.tasks

import dev.atoll.app.core.*
import dev.atoll.app.model.TimelineSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

data class ExistingSecret(val exists: Boolean = false, val error: String = "")
data class UiFormsState(val forms: List<UiForm> = emptyList(), val busy: String = "", val error: String = "", val existing: Map<String,ExistingSecret> = emptyMap(), val notice: String = "") { val current: UiForm? get() = forms.firstOrNull() }
/** Host-owned for the authenticated screen, independent of which channel/tab is visible. */
class UiFormController(
    private val wire: WireClient,
    private val selfFor: (String)->String,
    private val sessionId: ()->String = { (wire.state.value as? WireState.Attached)?.receipt?.text("session").orEmpty() },
    private val now: ()->Long = System::currentTimeMillis,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(UiFormsState())
    val state: StateFlow<UiFormsState> = mutable.asStateFlow()
    private val handled = mutableSetOf<String>()
    private var expiry: Job? = null
    private var existing: Job? = null
    init { scope.launch(start=CoroutineStart.UNDISPATCHED) { wire.frames.collect { frame ->
        if (frame is DownFrame.Feed) {
            val envelope=frame.row.envelope
            if (envelope.kind=="request" && envelope.type=="ui.form") accept(envelope)
            else if (envelope.isTerminal) dismiss(envelope.parentId.ifEmpty { envelope.correlationId })
        }
    } } }
    fun scan(source: TimelineSource) {
        val scan=clientUiRequests(source,selfFor(source.channelId),now())
        scan.closed.forEach(::dismiss);scan.open.forEach(::accept)
    }
    private fun accept(envelope: Envelope) {
        if (envelope.id in handled || selfFor(envelope.channelId).isEmpty() || selfFor(envelope.channelId) !in envelope.audience) return
        val session=sessionId();if (session.isEmpty()) return
        handled.add(envelope.id)
        if (uiRequestAddressing(envelope,session)!="mine" || envelope.expiresAt>0 && envelope.expiresAt<=now()) return
        val form=runCatching { parseUiForm(envelope) }.getOrNull() ?: return
        mutable.value=mutable.value.copy(forms=mutable.value.forms+form)
        if (mutable.value.current?.id==form.id) loadExisting(form)
        scheduleExpiry()
    }
    private fun scheduleExpiry() {
        expiry?.cancel()
        val next=mutable.value.forms.map { it.envelope.expiresAt }.filter { it>0 }.minOrNull() ?: return
        expiry=scope.launch {
            delay((next-now()).coerceAtLeast(1))
            mutable.value.forms.filter { it.envelope.expiresAt>0 && it.envelope.expiresAt<=now() }.forEach { dismiss(it.id) }
            scheduleExpiry()
        }
    }
    private fun dismiss(id: String) {
        val previous=mutable.value.current
        if (mutable.value.forms.none { it.id==id }) return
        mutable.value=mutable.value.copy(forms=mutable.value.forms.filter { it.id!=id })
        if (previous?.id==id) { mutable.value=mutable.value.copy(error="",existing=emptyMap());mutable.value.current?.let(::loadExisting) }
    }
    private fun loadExisting(form: UiForm) {
        existing?.cancel();mutable.value=mutable.value.copy(existing=emptyMap())
        existing=scope.launch { supervisorScope { form.secret.forEach { (field,resource) -> launch {
            val result=try { statGlobal(form.channelId,resource) } catch (e:CancellationException) { throw e } catch (e:Exception) { ExistingSecret(error=e.message.orEmpty()) }
            if (mutable.value.current?.id==form.id) mutable.value=mutable.value.copy(existing=mutable.value.existing+(field to result))
        } } } }
    }
    private suspend fun resource(channel: String, op: String, id: String, args: JsonElement? = null): JsonObject {
        val receipt=wire.request(Up.RESOURCE,buildJsonObject { put("channel_id",channel);put("op",op);put("resource_id",id);args?.let { put("args",it) } })
        if (receipt.text("status")=="rejected") throw WireException(Up.RESOURCE,receipt.text("detail").ifEmpty { "rejected" },receipt.text("detail").ifEmpty { "资源操作被拒绝" })
        return receipt
    }
    private suspend fun statGlobal(channel: String, id: String): ExistingSecret = try {
        ExistingSecret(exists=resource(channel,"stat",id)["exists"]==JsonPrimitive(true))
    } catch (e:WireException) { if (e.code=="resource_not_found") ExistingSecret() else throw e }
    private suspend fun writeGlobal(channel: String, id: String, value: String) {
        val exists=resource(channel,"stat",id)["exists"]==JsonPrimitive(true)
        if (exists) { resource(channel,"write",id,JsonPrimitive(value));return }
        try { resource(channel,"create",id,JsonPrimitive(value)) }
        catch (e:WireException) { if (e.code in setOf("already_exists","conflict_exists")) resource(channel,"write",id,JsonPrimitive(value)) else throw e }
    }
    fun submitAsync(form: UiForm, values: JsonObject) { scope.launch { try { submit(form,values) } catch (e:CancellationException) { throw e } catch (_:Exception) { /* Error retained for retry. */ } } }
    fun cancelAsync(form: UiForm) { scope.launch { try { cancel(form) } catch (e:CancellationException) { throw e } catch (_:Exception) { /* Error retained for retry. */ } } }
    suspend fun submit(form: UiForm, values: JsonObject) {
        if (mutable.value.busy.isNotEmpty()) return
        mutable.value=mutable.value.copy(busy="submit",error="")
        val written=mutableListOf<String>()
        try {
            answerUiForm(form,values,writeSecret={ id,value -> writeGlobal(form.channelId,id,value);written.add(id) },resolve={ wire.request(Up.RESOLVE,it) })
            dismiss(form.id)
        } catch (e:CancellationException) { throw e }
        catch (e:Exception) {
            if (e is WireException && e.code in setOf("already_closed","request_not_found")) {
                dismiss(form.id)
                mutable.value=mutable.value.copy(notice=if (written.isEmpty()) "这张表单已经关闭（在别处答复了或已过期）。" else "key 已写入 ${written.joinToString("、")}，但这张表单已经关闭，agent 没收到回复。")
            } else { mutable.value=mutable.value.copy(error=e.message.orEmpty());throw e }
        } finally { mutable.value=mutable.value.copy(busy="") }
    }
    suspend fun cancel(form: UiForm) {
        if (mutable.value.busy.isNotEmpty()) return
        mutable.value=mutable.value.copy(busy="cancel",error="")
        try { wire.request(Up.RESOLVE,uiResolveError(form.envelope,"cancelled","用户取消了表单"));dismiss(form.id) }
        catch (e:CancellationException) { throw e }
        catch (e:WireException) { if (e.code in setOf("already_closed","request_not_found")) { dismiss(form.id);mutable.value=mutable.value.copy(notice="这张表单已经关闭（在别处答复了或已过期）。") } else { mutable.value=mutable.value.copy(error=e.message.orEmpty());throw e } }
        catch (e:Exception) { mutable.value=mutable.value.copy(error=e.message.orEmpty());throw e }
        finally { mutable.value=mutable.value.copy(busy="") }
    }
    fun dismissNotice() { mutable.value=mutable.value.copy(notice="") }
    override fun close() { scope.cancel() }
}
