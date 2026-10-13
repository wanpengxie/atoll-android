package dev.atoll.app.model

import dev.atoll.app.core.*
import java.util.TreeMap
import kotlinx.serialization.json.*

/** Single-writer fold. Call from the channel's serial background executor. */
class ChannelReplica(val channelId: String, val selfId: String = "") {
    private val rows = TreeMap<Long, Row>()
    private val byId = mutableMapOf<String, Envelope>()
    private val requests = mutableMapOf<String, Row>()
    private val responses = mutableMapOf<String, TreeMap<Long, Row>>()
    private val pointers = mutableMapOf<String, MutableSet<String>>()
    private val parentReferences = hashSetOf<String>()
    private val roots = mutableMapOf<String, String>()
    private val entries = mutableMapOf<String, TimelineEntry>()
    private val children = mutableMapOf<String, TreeMap<Long, String>>()
    private val timeline = TreeMap<Long, TimelineEntry>()
    private val narration = TreeMap<Long, Row>()
    private data class Closure(val response: Row, val request: Row?)
    private val closures = mutableMapOf<String, Closure>()
    private val presentation = TimelinePresentation()
    private var revision = 0L
    private var projectionVersion = 0L
    private var cachedSnapshot: ReplicaSnapshot? = null
    private var cachedTimeline: TimelineSnapshot? = null
    private var cachedOptions: TimelineOptions? = null
    private var cachedNow = Long.MIN_VALUE
    private val pendingChangedIds = linkedSetOf<String>()
    private var pendingStructure = true
    private var relation: ConversationRelationIndex? = null
    private val taskRows = TreeMap<Long, Row>()
    private var cachedTaskSteps: List<Envelope> = emptyList()
    private var cachedRunningTasks: List<BackgroundTask> = emptyList()
    private var tasksDirty = true
    private var cachedRestarts: Map<String, Long> = emptyMap()
    private var restartsDirty = true
    private val deadlines = TreeMap<Long, MutableSet<String>>()
    private val requestDeadlines = mutableMapOf<String, Long>()
    /** The UI can schedule its next expiry refresh at this instant; null means none. */
    var nextTimelineExpiryAt: Long? = null
        private set

    // Private live view is consumed synchronously. Published snapshots never expose it.
    private val source = object : TimelineSource {
        override val channelId get() = this@ChannelReplica.channelId
        override val revision get() = this@ChannelReplica.revision
        override val projectionVersion get() = this@ChannelReplica.projectionVersion
        override val lastSeq get() = this@ChannelReplica.rows.lastKeyOrZero()
        override val rows: Collection<Row> get() = this@ChannelReplica.rows.values
        override val timeline: Collection<TimelineEntry> get() = this@ChannelReplica.timeline.values
        override val narration: Collection<Row> get() = this@ChannelReplica.narration.values
        override val envelopesById: Map<String, Envelope> get() = byId
    }
    val rowCount: Int get() = rows.size

    fun apply(batch: RowBatch): ModelChange {
        require(batch.channelId == channelId) { "batch belongs to another channel" }
        var accepted = 0
        var structural = false
        val changed = linkedSetOf<String>()
        for (incoming in batch.rows) {
            if (invalidRow(incoming) || duplicateEnvelope(incoming.envelope)) continue
            val row = incoming.copy(envelope = Envelope(freezeJson(incoming.envelope.json) as JsonObject))
            val envelope = row.envelope
            rows[row.seq] = row
            accepted++
            revision++
            if (relation?.admit(envelope) == true) pendingStructure = true
            if (envelope.type == "agent.task") { taskRows[row.seq] = row; tasksDirty = true }
            if (!projectable(envelope)) continue
            if (envelope.id in parentReferences) pendingStructure = true
            if (envelope.parentId.isNotEmpty()) parentReferences.add(envelope.parentId)
            byId[envelope.id] = envelope
            projectionVersion++
            if (restartFact(envelope)) { restartsDirty = true; pendingStructure = true }
            when {
                envelope.visibility == "system" -> { narration[row.seq] = row; structural = true; changed.add(envelope.id) }
                envelope.kind == "request" && envelope.id.isNotEmpty() -> {
                    putRequest(row, changed)
                    reconcileClosure(envelope.id, changed)
                    structural = true
                }
                envelope.kind == "response" && envelope.parentId.isNotEmpty() -> {
                    responses.getOrPut(envelope.parentId) { TreeMap() }[row.seq] = row
                    reconcileClosure(envelope.parentId, changed)
                    refresh(envelope.parentId, changed)
                }
                else -> { timeline[row.seq] = TimelineEntry("standalone", row.seq, envelope = envelope); changed.add(envelope.id); structural = true }
            }
        }
        pendingChangedIds.addAll(changed)
        pendingStructure = pendingStructure || structural
        return ModelChange(accepted, java.util.Collections.unmodifiableSet(changed), structural, projectionVersion)
    }

    private fun invalidRow(row: Row) = row.channelId != channelId || row.seq <= 0 || rows.containsKey(row.seq)
    private fun duplicateEnvelope(envelope: Envelope): Boolean {
        if (envelope.id.isEmpty() || !byId.containsKey(envelope.id)) return false
        return !readmittingTrimmedRequest(envelope)
    }
    private fun readmittingTrimmedRequest(envelope: Envelope): Boolean {
        if (envelope.kind != "request") return false
        val request = requests[envelope.id]
        return request == null && closures.containsKey(envelope.id) || request != null && !rows.containsKey(request.seq)
    }
    private fun restartFact(envelope: Envelope): Boolean {
        val type = if (envelope.kind == "response") requests[envelope.parentId]?.envelope?.type else envelope.type
        return type in setOf("system.member.restart", "system.member.restart_all")
    }
    private fun noteDeadline(turn: Turn) {
        requestDeadlines.remove(turn.requestId)?.let { deadline ->
            deadlines[deadline]?.let { ids -> if (ids.remove(turn.requestId) && ids.isEmpty()) deadlines.remove(deadline) }
        }
        val deadline = turn.request.expiresAt.takeIf { it != 0L } ?: turn.request.body.number("expires_at")
        if (turn.terminal == null && deadline > 0) {
            requestDeadlines[turn.requestId] = deadline
            deadlines.getOrPut(deadline) { hashSetOf() }.add(turn.requestId)
        }
    }

    private fun rootOf(request: Envelope): String {
        var parent = request.parentId
        var root = ""
        val seen = mutableSetOf<String>()
        while (parent.isNotEmpty() && requests.containsKey(parent) && seen.add(parent)) {
            root = parent
            parent = requests.getValue(parent).envelope.parentId
        }
        return root.ifEmpty { request.id }
    }

    private fun putRequest(row: Row, changed: MutableSet<String>) {
        pendingStructure = true
        val id = row.envelope.id
        val previous = requests.put(id, row)
        previous?.envelope?.parentId?.let { pointers[it]?.remove(id) }
        val parent = row.envelope.parentId
        if (parent.isNotEmpty() && parent != id) pointers.getOrPut(parent) { linkedSetOf() }.add(id)
        val affected = linkedSetOf<String>()
        val queue = java.util.ArrayDeque<String>()
        queue.add(id)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!affected.add(current)) continue
            pointers[current]?.forEach(queue::addLast)
        }
        val affectedRoots = linkedSetOf<String>()
        for (current in affected) {
            val oldRoot = roots[current]
            if (oldRoot != null) {
                affectedRoots.add(oldRoot)
                entries[current]?.seq?.let { seq ->
                    if (oldRoot == current) timeline.remove(seq) else children[oldRoot]?.remove(seq)
                }
            }
        }
        for (current in affected) {
            val request = requests[current] ?: continue
            val root = rootOf(request.envelope)
            roots[current] = root
            entries[current] = entryFor(request)
            affectedRoots.add(root)
            if (root != current) children.getOrPut(root) { TreeMap() }[request.seq] = current
        }
        for (root in affectedRoots) publishRoot(root, changed)
    }

    private fun entryFor(request: Row): TimelineEntry {
        val id = request.envelope.id
        val raw = responses[id]?.values?.toList().orEmpty()
        val closure = closures[id]
        val proof = closure?.response
        // Same-seq conflicting rereads cannot overwrite retained lifecycle provenance.
        val combined = (if (proof != null && raw.none { matchesClosure(proof, it) }) listOf(proof) else emptyList()) + raw
        val ordered = combined.sortedBy { it.seq }
        val terminal = ordered.firstOrNull { it.envelope.isTerminal }
        val provisional = ordered.filterNot { it.envelope.isTerminal }
        val turn = Turn(request.envelope, request.seq, frozen(provisional), terminal?.envelope, terminal?.seq ?: 0,
            maxOf(request.seq, ordered.maxOfOrNull { it.seq } ?: 0), proof != null && terminal === proof)
        noteDeadline(turn)
        return TimelineEntry("turn", request.seq, turn = turn)
    }

    private fun refresh(id: String, changed: MutableSet<String>) {
        val request = requests[id] ?: return
        entries[id] = entryFor(request)
        publishRoot(roots[id] ?: id, changed)
    }

    private fun publishRoot(id: String, changed: MutableSet<String>) {
        changed.add(id)
        val entry = entries[id] ?: return
        if (roots[id] != id) return
        val thread = children[id]?.values?.mapNotNull { child -> entries[child]?.takeIf { roots[child] == id } }.orEmpty()
        timeline[entry.seq] = entry.copy(thread = frozen(thread))
    }

    private fun reconcileClosure(id: String, changed: MutableSet<String>) {
        var closure = closures[id] ?: return
        val raw = responses[id]?.values.orEmpty()
        val earlier = raw.firstOrNull { it.envelope.isTerminal && it.seq < closure.response.seq }
        if (earlier != null) {
            closure = Closure(compactTerminal(earlier), requests[id]?.let(::compactRequest) ?: closure.request)
            closures[id] = closure
        }
        val exact = raw.any { matchesClosure(closure.response, it) }
        val request = requests[id]
        if (exact && request != null && rows.containsKey(request.seq)) {
            closures.remove(id)
        } else if (request == null && closure.request != null && raw.any { it.envelope.isTerminal }) {
            putRequest(closure.request, changed)
            byId[id] = closure.request.envelope
        }
    }

    /** Soft row cap: open turns and their ancestors pin the lower boundary. */
    fun trim(maxRows: Int, keepOpenTurns: Boolean = true): Int {
        if (maxRows <= 0 || rows.size <= maxRows) return 0
        var cut = rows.keys.elementAt(rows.size - maxRows)
        if (keepOpenTurns) {
            for (entry in timeline.values.flatMap { listOf(it) + it.thread }) if (entry.turn != null && entry.turn.terminal == null) {
                var current = entry.turn!!.requestId
                val seen = mutableSetOf<String>()
                while (seen.add(current)) {
                    val request = requests[current] ?: break
                    if (rows.containsKey(request.seq)) cut = minOf(cut, request.seq)
                    val parent = request.envelope.parentId
                    if (parent.isEmpty() || !requests.containsKey(parent)) break
                    current = parent
                }
            }
        }
        val removed = rows.headMap(cut).values.toList()
        if (removed.isEmpty()) return 0
        for (entry in timeline.values.flatMap { listOf(it) + it.thread }) {
            val turn = entry.turn ?: continue
            val id = turn.requestId
            if (turn.terminal != null && (turn.requestSeq < cut || turn.terminalSeq < cut)) {
                retain(id, Row(channelId, turn.terminalSeq, turn.terminal), Row(channelId, turn.requestSeq, turn.request))
            }
        }
        for (row in removed) if (projectable(row.envelope) && row.envelope.isTerminal && row.envelope.parentId.isNotEmpty()) {
            retain(row.envelope.parentId, row, requests[row.envelope.parentId])
        }
        removed.forEach { rows.remove(it.seq) }
        revision++
        if (removed.any { projectable(it.envelope) }) projectionVersion++
        refold()
        relation = relation?.let { old -> ConversationRelationIndex(old.selfId).also { index -> rows.values.forEach { index.admit(it.envelope) } } }
        pendingStructure = true
        tasksDirty = true
        restartsDirty = true
        return removed.size
    }

    private fun retain(id: String, response: Row, request: Row?) {
        if ((closures[id]?.response?.seq ?: Long.MAX_VALUE) <= response.seq) return
        closures[id] = Closure(compactTerminal(response), request?.let(::compactRequest))
    }

    private fun refold() {
        byId.clear(); requests.clear(); responses.clear(); pointers.clear(); roots.clear(); entries.clear(); children.clear(); timeline.clear(); narration.clear()
        deadlines.clear(); requestDeadlines.clear(); taskRows.clear(); parentReferences.clear()
        val changed = mutableSetOf<String>()
        for (row in rows.values) {
            val envelope = row.envelope
            if (envelope.type == "agent.task") taskRows[row.seq] = row
            if (!projectable(envelope)) continue
            if (envelope.parentId.isNotEmpty()) parentReferences.add(envelope.parentId)
            byId[envelope.id] = envelope
            when {
                envelope.visibility == "system" -> narration[row.seq] = row
                envelope.kind == "request" && envelope.id.isNotEmpty() -> putRequest(row, changed)
                envelope.kind == "response" && envelope.parentId.isNotEmpty() -> responses.getOrPut(envelope.parentId) { TreeMap() }[row.seq] = row
                else -> timeline[row.seq] = TimelineEntry("standalone", row.seq, envelope = envelope)
            }
        }
        for (id in closures.keys.toList()) {
            reconcileClosure(id, changed)
            val closure = closures[id]
            if (closure != null && !byId.containsKey(id)) byId[id] = closure.request?.envelope ?: Envelope(buildJsonObject { put("id", id); put("kind", "request") })
        }
        requests.keys.toList().forEach { refresh(it, changed) }
    }

    fun snapshot(): ReplicaSnapshot {
        cachedSnapshot?.takeIf { it.revision == revision }?.let { return it }
        return ReplicaSnapshot(channelId, revision, projectionVersion, rows.lastKeyOrZero(), frozen(rows.values), frozen(timeline.values), frozen(narration.values), frozenMap(byId)).also { cachedSnapshot = it }
    }

    fun timeline(options: TimelineOptions = TimelineOptions(selfId = selfId)): TimelineSnapshot {
        val key = options.copy(now = 0)
        val expiryChanged = options.now < cachedNow || nextTimelineExpiryAt?.let { options.now >= it } == true
        cachedTimeline?.takeIf { it.revision == projectionVersion && cachedOptions == key && !expiryChanged }?.let { return it }
        if (relation?.selfId != options.selfId) {
            relation = ConversationRelationIndex(options.selfId).also { index -> rows.values.forEach { index.admit(it.envelope) } }
            pendingStructure = true
        }
        if (restartsDirty) { cachedRestarts = RequestLifecycle.memberRestarts(source); restartsDirty = false }
        if (tasksDirty) {
            cachedTaskSteps = frozen(taskRows.values.map { it.envelope })
            cachedRunningTasks = BackgroundTasks.runningBackgroundTasks(object : TimelineSource by source { override val rows: Collection<Row> get() = taskRows.values })
            tasksDirty = false
        }
        val facts = ProjectionFacts(if (options.scope == ConversationScope.MINE && options.selfId.isNotEmpty()) relation!!.ids else null, cachedRestarts, cachedTaskSteps, cachedRunningTasks)
        val contentOnly = !pendingStructure && cachedOptions == key && !expiryChanged
        val changedEntries = pendingChangedIds.mapNotNull { id -> entries[id]?.seq?.let { timeline[it] } }
        val result = presentation.project(source, options, facts = facts, changedEntries = if (contentOnly && changedEntries.size == pendingChangedIds.size) changedEntries else null)
        pendingChangedIds.clear()
        pendingStructure = false
        cachedOptions = key.copy(actorFilter = key.actorFilter.toSet(), timelinePlaced = key.timelinePlaced.toSet(), localEchoes = frozen(key.localEchoes))
        cachedTimeline = result
        cachedNow = options.now
        nextTimelineExpiryAt = deadlines.higherKey(options.now)
        return result
    }

    private fun compactTerminal(row: Row): Row {
        val body = row.envelope.body
        val nested = body["value"] as? JsonObject
        val compact = buildJsonObject {
            body["status"]?.let { put("status", it) }
            for (key in listOf("merged_into", "replaced_by", "preempted_by")) {
                val value = body[key] ?: nested?.get(key)
                if (value != null && value != JsonNull && value != JsonPrimitive("")) put(key, value)
            }
        }
        return withBody(row, compact)
    }
    private fun compactRequest(row: Row) = withBody(row, JsonObject(row.envelope.body.filterValues {
        it == JsonNull || it is JsonPrimitive && (!it.isString || it.content.length <= 4096)
    }))
    private fun withBody(row: Row, body: JsonObject): Row {
        val allowed = setOf("id", "parent_id", "correlation_id", "kind", "type", "ts", "sender", "audience", "visibility")
        return row.copy(envelope = Envelope(JsonObject(row.envelope.json.filterKeys { it in allowed } + ("payload" to buildJsonObject { put("body", body) }))))
    }
    private fun matchesClosure(proof: Row, raw: Row) = proof.seq == raw.seq && proof.envelope.id == raw.envelope.id && proof.envelope == compactTerminal(raw).envelope
    private fun projectable(envelope: Envelope): Boolean {
        val payload = envelope.json["payload"]
        return envelope.hasBody || envelope.visibility == "system" && (payload == null || payload == JsonNull || payload is JsonObject && payload.isEmpty())
    }
    private fun TreeMap<Long, Row>.lastKeyOrZero() = if (isEmpty()) 0L else lastKey()
}
