package dev.atoll.app.tasks

import dev.atoll.app.core.WireState
import dev.atoll.app.core.Row
import dev.atoll.app.core.Envelope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owned by the signed-in workspace, shared across channel models, never persisted. */
class AutomationSession(boot: String) {
    private var world=boot
    private val firings=linkedMapOf<Pair<String,String>,Envelope>()
    var generation: Long=0
        private set
    private val mutable=MutableStateFlow<List<AutomationRecord>>(emptyList())
    val records: StateFlow<List<AutomationRecord>> = mutable.asStateFlow()

    /** Reconnects in the same world preserve records. Logout and a new boot invalidate them. */
    fun observe(connection: WireState): Boolean {
        when (connection) {
            is WireState.Attached -> {
                val boot=(connection.receipt["boot"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                if (boot!=world) { world=boot;clear();return true }
            }
            WireState.SignedOut -> { clear();return true }
            else -> Unit
        }
        return false
    }
    fun clear() { generation++;firings.clear();mutable.value=emptyList() }
    internal fun add(record: AutomationRecord) {
        val fired=firings[record.channelId to "timer:${record.timerId}"]
        val value=if (fired==null) record else record.copy(state="fired",firedAt=fired.ts.takeIf { it>0 } ?: System.currentTimeMillis())
        mutable.value=mutable.value.filterNot { it.channelId==record.channelId && it.timerId==record.timerId }+value
    }
    /** The workspace forwards all live feed rows here, including background channels. */
    fun observeFiring(row: Row,selfId: String) {
        val e=row.envelope
        if(e.kind!="event" || !e.id.startsWith("timer:") || e.parentId.isNotEmpty() || e.correlationId!=e.id ||
            e.senderKind !in setOf("human","agent") || selfId.isEmpty() || e.senderId!=selfId || e.audience!=listOf(selfId)) return
        firings[row.channelId to e.id]=e
        if(firings.size>128) firings.remove(firings.keys.first())
        update(row.channelId) { if (it.state=="scheduled" && e.id=="timer:${it.timerId}") it.copy(state="fired",firedAt=e.ts.takeIf { t -> t>0 } ?: System.currentTimeMillis()) else it }
    }
    internal fun update(channelId: String, transform: (AutomationRecord)->AutomationRecord) {
        mutable.value=mutable.value.map { if (it.channelId==channelId) transform(it) else it }
    }
}
