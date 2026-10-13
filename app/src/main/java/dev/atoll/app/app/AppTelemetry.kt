package dev.atoll.app.app

import kotlinx.serialization.json.*
import dev.atoll.app.net.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded diagnostics; redacted warnings also have a checked-account session copy. */
class AppTelemetry(private val warningStore: KeyValueStore? = null,private val now: () -> Long = System::currentTimeMillis):AutoCloseable {
    private val events = ArrayDeque<JsonObject>()
    private val frames = FrameSamples()
    private val scrollingFrames = FrameSamples()
    val scrolling = AtomicBoolean(false)
    val screen = AtomicReference("boot")
    private var owner: String? = null
    private var hadOwner=false
    private val warnings=ArrayDeque<JsonObject>()
    private var revision=0L
    private var ownerGeneration=0L
    private var queuedGeneration:Long?=null
    private sealed interface Write { data class Save(val generation:Long):Write;data class Remove(val key:String):Write }
    private val writes=Channel<Write>(Channel.UNLIMITED)
    private val persistence=warningStore?.let { store ->
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        scope.launch {
            try { for(command in writes) when(command) {
                is Write.Remove -> runCatching { store.remove(command.key) }
                is Write.Save -> {
                    val snapshot=synchronized(this@AppTelemetry) {
                        if(queuedGeneration==command.generation) queuedGeneration=null
                        owner?.takeIf { ownerGeneration==command.generation }?.let { Triple(it,revision,JsonArray(warnings.toList())) }
                    } ?: continue
                    val encoded=snapshot.third.toString()
                    val current=synchronized(this@AppTelemetry) { owner==snapshot.first && revision==snapshot.second && ownerGeneration==command.generation }
                    if(current) runCatching { store.put(warningKey(snapshot.first),encoded) }
                }
            } } finally { scope.cancel() }
        }
    }
    private fun warningKey(account: String)="diagnostics.warnings:$account"
    private fun queueSave() { if(warningStore!=null && owner!=null && queuedGeneration!=ownerGeneration) { queuedGeneration=ownerGeneration;writes.trySend(Write.Save(ownerGeneration)) } }
    @Synchronized fun switchOwner(value: String?) {
        if(value==owner) return
        val previous=owner
        if(hadOwner) { events.clear();warnings.clear();frames.clear();scrollingFrames.clear() }
        if(previous!=null && warningStore!=null) writes.trySend(Write.Remove(warningKey(previous)))
        owner=value;revision++;ownerGeneration++
        // Restore only the initial checked account. Later logins start a new session.
        if(value!=null && !hadOwner) {
            val restored=runCatching {
                (warningStore?.get(warningKey(value))?.let { Json.parseToJsonElement(it) as? JsonArray }).orEmpty().takeLast(100).mapNotNull { raw ->
                    val row=raw as? JsonObject ?: return@mapNotNull null
                    val level=(row["level"] as? JsonPrimitive)?.content.orEmpty()
                    if(level !in setOf("warn","error")) return@mapNotNull null
                    buildJsonObject { put("ts",(row["ts"] as? JsonPrimitive)?.longOrNull ?: 0);put("type",(row["type"] as? JsonPrimitive)?.content.orEmpty().take(128));put("level",level);put("data",safe(row["data"] ?: EnvelopeData)) }
                }
            }.getOrDefault(emptyList())
            val current=events.toList();events.clear();restored.forEach(events::addLast);current.forEach(events::addLast)
            val recent=(restored+warnings.toList()).takeLast(100);warnings.clear();recent.forEach(warnings::addLast)
            while(events.size>500) events.removeFirst()
            hadOwner=true
            if(warnings.isNotEmpty()) queueSave()
        }
    }
    @Synchronized fun event(type: String, level: String = "info", data: JsonObject = EnvelopeData) {
        val row=buildJsonObject { put("ts",now());put("type",type.take(128));put("level",level);put("data",safe(data)) }
        events.addLast(row)
        while(events.size>500) events.removeFirst()
        if(level in setOf("warn","error")) {
            warnings.addLast(row);while(warnings.size>100) warnings.removeFirst();revision++;queueSave()
        }
    }
    override fun close() { writes.close() } // The writer drains pending saves/removals, then ends.
    @Synchronized fun frame(durationNanos: Long, budgetNanos: Long, droppedReports: Int = 0) {
        if (durationNanos <= 0 || budgetNanos <= 0) return
        frames.add(durationNanos, budgetNanos, droppedReports)
        if (scrolling.get()) scrollingFrames.add(durationNanos, budgetNanos, droppedReports)
    }
    fun snapshot(): JsonObject {
        val copied = synchronized(this) { Triple(events.toList(), frames.copy(), scrollingFrames.copy()) }
        return buildJsonObject {
            put("events", JsonArray(copied.first)); put("frames", copied.second.json()); put("scrollingFrames", copied.third.json())
            put("frameSource", "android.view.FrameMetrics"); put("frameScope", "activityWindow"); put("screen", screen.get())
        }
    }
    private fun safe(value: JsonElement,depth: Int=0):JsonElement = when {
        value is JsonPrimitive -> if(value.isString) JsonPrimitive(value.content.take(2048)) else value
        depth>=4 -> JsonPrimitive("[truncated]")
        value is JsonObject -> JsonObject(value.entries.take(50).associate { (key,child) -> key to
            if(SensitiveKey.containsMatchIn(key)) JsonPrimitive("[redacted]") else safe(child,depth+1) })
        value is JsonArray -> JsonArray(value.take(20).map { safe(it,depth+1) })
        else -> value
    }
    private class FrameSamples {
        var count = 0L; var janky = 0L; var dropped = 0L
        val samples = ArrayDeque<Long>()
        fun add(duration: Long, budget: Long, drops: Int) { count++; if (duration > budget) janky++; dropped += drops; samples.addLast(duration); while (samples.size > 2000) samples.removeFirst() }
        fun clear() { count = 0; janky = 0; dropped = 0; samples.clear() }
        fun copy() = FrameSamples().also { it.count = count; it.janky = janky; it.dropped = dropped; it.samples.addAll(samples) }
        fun json(): JsonObject {
            val ordered = samples.sorted()
            fun percentile(value: Double): Double? = if (ordered.isEmpty()) null else ordered[((ordered.size - 1) * value).toInt()] / 1_000_000.0
            return buildJsonObject { put("count", count); put("janky", janky); put("jankyPercent", if (count == 0L) JsonNull else JsonPrimitive(janky * 100.0 / count))
                put("droppedReports", dropped); put("percentileWindowSamples", ordered.size)
                put("p90Ms", percentile(.9)?.let(::JsonPrimitive) ?: JsonNull); put("p99Ms", percentile(.99)?.let(::JsonPrimitive) ?: JsonNull) }
        }
    }
    companion object { private val EnvelopeData = JsonObject(emptyMap());private val SensitiveKey=Regex("password|secret|token|credential|authorization|cookie|envelope|payload|body|content|text",RegexOption.IGNORE_CASE) }
}
