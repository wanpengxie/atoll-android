package dev.atoll.app.compose

import androidx.lifecycle.ViewModel
import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID

data class ComposerUiState(
    val channelId: String = "", val selfId: String = "", val roster: List<ComposerActor> = emptyList(),
    val draft: ComposerDraft = ComposerDraft(), val delivery: Delivery = Delivery(emptyList(), ""),
    val writable: Boolean = false, val online: Boolean = false, val sending: Boolean = false,
    val uploading: Boolean = false, val error: String = "", val submissions: List<LocalSubmission> = emptyList(),
    val definitions: List<SlashDefinition> = SlashCommands.static, val parameters: AgentParameters? = null,
    val editorCommand: EditorCommand = EditorCommand(),
) {
    val targetAgent: ComposerActor? get() = delivery.recipients.filter { it.kind == "agent" }.singleOrNull()
    val canSend get() = writable && !sending && !uploading && (draft.text.isNotBlank() || draft.attachments.isNotEmpty()) && (draft.text.trim().startsWith('/') && !draft.text.trim().startsWith("//") || delivery.valid)
}
/** UI-facing state has one writer (the supplied UI dispatcher); I/O remains suspended/background. */
class ComposerViewModel(
    val ownerKey: String,
    private val wire: WireClient,
    private val outbox: OutboxRepository,
    private val uploader: AttachmentUploader,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    outboxDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val actorQueries: ActorQueries = WireActorQueries(wire),
    private val releaseResources: () -> Unit = {},
    private val serverBoot: String = "",
) : ViewModel(), AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeAccess = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val engine = OutboxEngine(wire, outbox, outboxDispatcher, now, mayTransmit = { writeAccess.value[it] == true && (serverBoot.isEmpty() || (wire.state.value as? WireState.Attached)?.receipt?.text("boot") == serverBoot) })
    private val mutable = MutableStateFlow(ComposerUiState())
    val state: StateFlow<ComposerUiState> = mutable.asStateFlow()
    val submissions = engine.submissions
    private val drafts = mutableMapOf<String, ComposerDraft>()
    private val modifiedDrafts = hashSetOf<String>()
    private val manifests = mutableMapOf<String, JsonObject>()
    private val parameters = mutableMapOf<String, AgentParameters>()
    private val probing = hashSetOf<String>()
    private val refreshing = hashSetOf<String>()
    private val draftWrites = Mutex()
    private var agentFilter = emptySet<String>()
    private var recentAgentId = ""
    init {
        scope.launch {
            try { outbox.restoreDrafts().forEach { (channel, draft) -> if (channel !in modifiedDrafts) drafts[channel] = draft }; rebuild(drafts[mutable.value.channelId] ?: mutable.value.draft) }
            catch (error: Exception) { report(error) }
        }
        scope.launch { wire.state.collect { connection ->
            mutable.value = mutable.value.copy(online = connection is WireState.Attached)
            if (connection is WireState.Attached) { probeAgents(); refreshTarget() }
        } }
        scope.launch { engine.submissions.collect { values -> mutable.value = mutable.value.copy(submissions = values.filter { it.channelId == mutable.value.channelId }) } }
    }
    fun setChannel(channelId: String, selfId: String, roster: List<ComposerActor>, writable: Boolean = true, agentFilter: Set<String> = emptySet(), recentAgentId: String = "") {
        val previous = mutable.value
        setChannelWritable(channelId, writable)
        if (previous.channelId.isNotEmpty()) drafts[previous.channelId] = previous.draft
        this.agentFilter = agentFilter.toSet(); this.recentAgentId = recentAgentId
        mutable.value = previous.copy(channelId = channelId, selfId = selfId, roster = roster.toList(), writable = writable, sending = if (previous.channelId == channelId) previous.sending else false, error = "", submissions = submissions.value.filter { it.channelId == channelId })
        val draft = drafts[channelId] ?: ComposerDraft()
        rebuild(draft)
        if (previous.channelId != channelId) commandEditor(draft.text)
        probeAgents(); refreshTarget()
    }
    /** Host validates all restored channels from current membership/access facts. */
    fun setChannelWritable(channelId: String, writable: Boolean) {
        writeAccess.value = writeAccess.value + (channelId to writable)
        if (mutable.value.channelId == channelId) mutable.value = mutable.value.copy(writable = writable)
        scope.launch { try { engine.refresh() } catch (error: Exception) { report(error) } }
    }
    fun updateText(text: String) { changed(mutable.value.draft.copy(text = text), persist = false) }
    /** Echo from the native writer; never reset its text, selection or composing spans. */
    fun onEditorText(text: String) { changed(mutable.value.draft.copy(text = text), persist = false, external = false) }
    fun replaceEditorText(text: String, selectionStart: Int = text.length, selectionEnd: Int = selectionStart) {
        changed(mutable.value.draft.copy(text = text), persist = false, external = false)
        commandEditor(text, selectionStart, selectionEnd)
    }
    private fun commandEditor(text: String, start: Int = text.length, end: Int = start) {
        mutable.value = mutable.value.copy(editorCommand = EditorCommand(mutable.value.editorCommand.version + 1, text, start, end))
    }
    fun addMention(actor: ComposerActor, query: MentionQuery? = null) {
        val draft = mutable.value.draft
        changed(draft.copy(text = if (query != null) draft.text.removeRange(query.start.coerceAtMost(draft.text.length), query.end.coerceAtMost(draft.text.length)) else draft.text,
            recipients = (draft.recipients + actor).distinctBy { it.id }), selection = query?.start)
        refreshTarget()
    }
    fun removeMention(id: String) { changed(mutable.value.draft.copy(recipients = mutable.value.draft.recipients.filter { it.id != id })); refreshTarget() }
    fun selectAgent(id: String) { changed(mutable.value.draft.copy(manualAgentId = id)); refreshTarget() }
    fun setReply(reply: ReplyTarget?) { changed(mutable.value.draft.copy(reply = reply)); refreshTarget() }
    fun addAttachments(files: List<ComposerAttachment>) { if (files.any { it.channelId != mutable.value.channelId }) { mutable.value = mutable.value.copy(error = "文件属于另一个频道"); return }; changed(mutable.value.draft.copy(attachments = (mutable.value.draft.attachments + files).distinctBy { it.resourceId })) }
    fun removeAttachment(id: String) { changed(mutable.value.draft.copy(attachments = mutable.value.draft.attachments.filter { it.resourceId != id })) }
    fun clearError() { mutable.value = mutable.value.copy(error = "") }
    private fun changed(draft: ComposerDraft, persist: Boolean = true, external: Boolean = true, selection: Int? = null) {
        val channel = mutable.value.channelId
        if (draft == mutable.value.draft) return
        val textChanged = draft.text != mutable.value.draft.text
        modifiedDrafts.add(channel); drafts[channel] = draft; rebuild(draft)
        if (external && textChanged) commandEditor(draft.text, selection ?: draft.text.length)
        if (persist && channel.isNotEmpty()) scope.launch { try { draftWrites.withLock { outbox.putDraft(channel, draft) } } catch (error: Exception) { report(error) } }
    }
    private fun rebuild(draft: ComposerDraft) {
        val current = mutable.value
        val delivery = ComposerDelivery.resolve(draft, current.roster, agentFilter, recentAgentId)
        val target = delivery.recipients.filter { it.kind == "agent" }.singleOrNull()
        val key = "${current.channelId}\u0000${target?.id.orEmpty()}"
        mutable.value = current.copy(draft = draft, delivery = delivery, definitions = SlashCommands.definitions(manifests[key]), parameters = parameters[key])
    }
    fun send(editorText: String = mutable.value.draft.text) {
        val current = mutable.value
        if (current.sending || current.uploading || !current.writable || editorText != current.draft.text) return
        val draft = current.draft.copy(text = editorText)
        if (draft.text.isBlank() && draft.attachments.isEmpty()) return
        mutable.value = current.copy(sending = true, error = "")
        scope.launch {
            try {
                val command = SlashCommands.parse(draft.text, current.definitions)
                if (command is ParsedCommand.Command && command.definition.name == "model" && command.payload.isEmpty()) {
                    throw ComposerFailure("composer_model_picker", "从右下角选择模型和推理强度")
                }
                val frames = if (command is ParsedCommand.Command) listOf(SlashCommands.frame(current.channelId, UUID.randomUUID().toString(), command, draft, current.targetAgent, current.online, manifests["${current.channelId}\u0000${current.targetAgent?.id.orEmpty()}"])) else
                    ComposerDelivery.messageFrames(current.channelId, draft, current.delivery, { UUID.randomUUID().toString() }, now() + 24 * 60 * 60 * 1000)
                val accepted = frames.map { frame -> LocalSubmission(frame.text("id"), current.channelId, frame, (frame["payload"] as? JsonObject)?.text("text").orEmpty(), current.delivery.label, createdAt = now()) }
                engine.accept(accepted)
                if (mutable.value.channelId == current.channelId && mutable.value.draft == current.draft) changed(ComposerDraft(manualAgentId = draft.manualAgentId))
                if (current.delivery.recipients.size == 1 && current.targetAgent != null) recentAgentId = current.targetAgent!!.id
            } catch (error: Exception) { if (mutable.value.channelId == current.channelId) report(error) }
            finally { if (mutable.value.channelId == current.channelId) mutable.value = mutable.value.copy(sending = false) }
        }
    }
    fun retry(id: String) { scope.launch { try { engine.retry(id) } catch (error: Exception) { report(error) } } }
    fun cancel(id: String) { scope.launch { try { engine.cancel(id) } catch (error: Exception) { report(error) } } }
    fun reconcileLanded(channelId: String, ids: Set<String>) { scope.launch { engine.reconcileLanded(channelId, ids) } }
    fun upload(files: List<UploadFile>) {
        if (files.isEmpty() || mutable.value.uploading) return
        val channel = mutable.value.channelId
        if (!mutable.value.online || !mutable.value.writable) { mutable.value = mutable.value.copy(error = "连接可用且频道可写时才能上传附件"); return }
        mutable.value = mutable.value.copy(uploading = true, error = "")
        scope.launch {
            try {
                for (file in files) {
                    val attachment = uploader.upload(channel, file)
                    val draft = drafts[channel] ?: ComposerDraft()
                    val next = draft.copy(attachments = (draft.attachments + attachment).distinctBy { it.resourceId })
                    drafts[channel] = next
                    if (mutable.value.channelId == channel) changed(next) else draftWrites.withLock { outbox.putDraft(channel, next) }
                }
            } catch (error: Exception) { if (mutable.value.channelId == channel) report(error) }
            finally { mutable.value = mutable.value.copy(uploading = false) }
        }
    }
    private fun probeAgents() {
        val current = mutable.value
        if (!current.online || !current.writable) return
        current.roster.filter { it.kind == "agent" }.forEach { actor ->
            val key = "${current.channelId}\u0000${actor.id}"
            if (key in manifests || !probing.add(key)) return@forEach
            scope.launch {
                try { manifests[key] = actorQueries.request(current.channelId, actor.id, "actor.describe", Envelope.EMPTY); if (mutable.value.channelId == current.channelId) rebuild(mutable.value.draft) }
                catch (_: Exception) { /* Refresh remains available when the agent comes back. */ }
                finally { probing.remove(key) }
            }
        }
    }
    private fun refreshTarget() { mutable.value.targetAgent?.let { if ("${mutable.value.channelId}\u0000${it.id}" !in parameters) refreshParameters(it.id) } }
    fun refreshParameters(actorId: String = mutable.value.targetAgent?.id.orEmpty()) {
        val current = mutable.value
        if (!current.online || actorId.isEmpty()) return
        val key = "${current.channelId}\u0000$actorId"
        if (!refreshing.add(key)) return
        parameters[key] = (parameters[key] ?: AgentParameters(actorId)).copy(loading = true, error = ""); rebuild(current.draft)
        scope.launch {
            try {
                supervisorScope {
                    val options = async { runCatching { actorQueries.request(current.channelId, actorId, "agent.options", Envelope.EMPTY) } }
                    val context = async { runCatching { actorQueries.request(current.channelId, actorId, "agent.context", Envelope.EMPTY) } }
                    val optionResult = options.await(); val contextResult = context.await()
                    var value = AgentParameterParser.options(actorId, optionResult.getOrNull() ?: Envelope.EMPTY, manifests[key])
                    contextResult.getOrNull()?.let { value = AgentParameterParser.context(value, it) }
                    value = value.copy(error = if (optionResult.isFailure && value.choices.isEmpty()) optionResult.exceptionOrNull()?.message.orEmpty() else contextResult.exceptionOrNull()?.message.orEmpty())
                    parameters[key] = value
                }
            } catch (error: Exception) { parameters[key] = AgentParameters(actorId, error = error.message.orEmpty()) }
            finally { refreshing.remove(key); if (mutable.value.channelId == current.channelId) rebuild(mutable.value.draft) }
        }
    }
    fun selectModel(choice: ModelChoice) {
        val current = mutable.value
        val actor = current.targetAgent ?: return
        if (!current.online || current.parameters?.switching == true) return
        val key = "${current.channelId}\u0000${actor.id}"
        parameters[key] = (parameters[key] ?: AgentParameters(actor.id)).copy(switching = true, error = ""); rebuild(current.draft)
        scope.launch {
            try { actorQueries.request(current.channelId, actor.id, "agent.select", buildJsonObject { put("model", choice.model); if (choice.effort.isNotEmpty()) put("effort", choice.effort) }); parameters[key] = parameters.getValue(key).copy(current = choice, switching = false) }
            catch (error: Exception) { parameters[key] = parameters.getValue(key).copy(switching = false, error = error.message.orEmpty()) }
            finally { if (mutable.value.channelId == current.channelId) rebuild(mutable.value.draft) }
        }
    }
    /** Integration forwards usage from later completed agent.ask terminals. */
    fun updateUsage(channelId: String, actorId: String, usage: JsonObject) {
        val key = "$channelId\u0000$actorId"
        val baseline = parameters[key] ?: return
        parameters[key] = AgentParameterParser.context(baseline, usage)
        if (mutable.value.channelId == channelId) rebuild(mutable.value.draft)
    }
    private fun report(error: Exception) { if (error !is CancellationException) mutable.value = mutable.value.copy(error = error.message ?: "操作失败") }
    override fun onCleared() { close() }
    override fun close() { if (scope.isActive) { scope.cancel(); engine.close(); releaseResources() } }
}
