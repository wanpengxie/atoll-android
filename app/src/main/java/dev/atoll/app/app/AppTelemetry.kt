package dev.atoll.app.app

import kotlinx.serialization.json.*
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded, in-memory diagnostics and frame samples. No message payloads or credentials. */
class AppTelemetry(private val now: () -> Long = System::currentTimeMillis) {
    private val events = ArrayDeque<JsonObject>()
    private val frames = FrameSamples()
    private val scrollingFrames = FrameSamples()
    val scrolling = AtomicBoolean(false)
    val screen = AtomicReference("boot")
    private var owner: String? = null
    @Synchronized fun switchOwner(value: String?) {
        if (owner != null && value != owner) { events.clear(); frames.clear(); scrollingFrames.clear() }
        owner = value
    }
    @Synchronized fun event(type: String, level: String = "info", data: JsonObject = EnvelopeData) {
        events.addLast(buildJsonObject { put("ts", now()); put("type", type); put("level", level); put("data", safe(data)) })
        while (events.size > 500) events.removeFirst()
    }
    @Synchronized fun frame(durationNanos: Long, budgetNanos: Long, droppedReports: Int = 0) {
        if (durationNanos <= 0 || budgetNanos <= 0) return
        frames.add(durationNanos, budgetNanos, droppedReports)
        if (scrolling.get()) scrollingFrames.add(durationNanos, budgetNanos, droppedReports)
    }
    fun snapshot(): JsonObject {
        val copied = synchronized(this) { Triple(events.toList(), frames.copy(), scrollingFrames.copy()) }
        return buildJsonObject {
            put("events", JsonArray(copied.first)); put("frames", copied.second.json()); put("scrollingFrames", copied.third.json())
            put("frameSource", "android.view.FrameMetrics"); put("screen", screen.get())
        }
    }
    private fun safe(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (key.lowercase() in setOf("password", "token", "access_token", "api_key", "cookie", "cookies", "authorization")) JsonPrimitive("[redacted]") else safe(child)
        })
        is JsonArray -> JsonArray(value.take(20).map(::safe))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.take(2048)) else value
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
    companion object { private val EnvelopeData = JsonObject(emptyMap()) }
}
