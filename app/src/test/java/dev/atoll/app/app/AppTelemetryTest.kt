package dev.atoll.app.app

import kotlinx.serialization.json.*
import dev.atoll.app.net.KeyValueStore
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.*
import org.junit.Test

class AppTelemetryTest {
    @Test fun concurrentWritersStayBoundedAndReportsCannotCarryCredentialFields() {
        val telemetry = AppTelemetry { 1 }
        val workers = (1..4).map {
            Thread {
                repeat(200) {
                    telemetry.event("event", data = buildJsonObject {
                        put("token", "test-only-secret")
                        put("nested", buildJsonObject { put("password", "test-only-password") })
                    })
                }
            }
        }.onEach(Thread::start)
        workers.forEach(Thread::join)
        val report = workspaceReport(true, telemetry, JsonObject(emptyMap()), 1)
        val captured = report["telemetry"]!!.jsonObject["events"]!!.jsonArray
        assertEquals(500, captured.size)
        assertFalse(report.toString().contains("test-only-secret")); assertFalse(report.toString().contains("test-only-password"))
        telemetry.switchOwner("one"); telemetry.switchOwner("two")
        assertEquals(0, telemetry.snapshot()["events"]!!.jsonArray.size)
    }
    @Test fun warningsHaveABoundedRedactedAccountCopyAndLogoutRemovesIt()=runBlocking<Unit> {
        val stored=ConcurrentHashMap<String,String>()
        val store=object:KeyValueStore {
            override fun get(key: String)=stored[key]
            override fun put(key: String,value: String) { stored[key]=value }
            override fun remove(key: String) { stored.remove(key) }
        }
        AppTelemetry(store) { 1 }.use { telemetry ->
            telemetry.switchOwner("account-one");telemetry.event("ordinary")
            delay(25);assertTrue(stored.isEmpty())
            repeat(125) { index -> telemetry.event("warning:$index","warn",buildJsonObject {
                put("token","test-token");put("secret_key","test-key");put("payload",buildJsonObject { put("text","private body") });put("reason","offline")
            }) }
            withTimeout(5000) { while(stored.values.none { raw -> (Json.parseToJsonElement(raw) as JsonArray).let { it.size==100 && it.last().jsonObject["type"]==JsonPrimitive("warning:124") } }) delay(10) }
            val copy=stored.values.single();assertFalse(copy.contains("test-token"));assertFalse(copy.contains("test-key"));assertFalse(copy.contains("private body"))
        }
        AppTelemetry(store) { 2 }.use { restored ->
            restored.switchOwner("account-two");assertTrue(restored.snapshot()["events"]!!.jsonArray.isEmpty())
        }
        AppTelemetry(store) { 3 }.use { restored ->
            restored.switchOwner("account-one");assertEquals(100,restored.snapshot()["events"]!!.jsonArray.size)
            restored.switchOwner(null);restored.event("identity.expired","warn")
            withTimeout(5000) { while(stored.isNotEmpty()) delay(10) }
            restored.switchOwner("account-one");assertTrue(restored.snapshot()["events"]!!.jsonArray.isEmpty())
            restored.event("new-session","warn")
            withTimeout(5000) { while(stored.values.none { it.contains("new-session") }) delay(10) }
            assertTrue(stored.values.all { !it.contains("warning:124") })
        }
    }
    @Test fun scrollingStatsAreSeparateFromWholeWindowAndEmptyPercentilesAreNull() {
        val telemetry = AppTelemetry()
        assertEquals(JsonNull, telemetry.snapshot()["frames"]!!.jsonObject["p99Ms"])
        telemetry.frame(10_000_000, 16_000_000)
        telemetry.scrolling.set(true)
        telemetry.frame(30_000_000, 16_000_000, 2)
        val stats = telemetry.snapshot()
        assertEquals(JsonPrimitive(2), stats["frames"]!!.jsonObject["count"])
        assertEquals(JsonPrimitive(1), stats["scrollingFrames"]!!.jsonObject["janky"])
        assertEquals(JsonPrimitive(100.0), stats["scrollingFrames"]!!.jsonObject["jankyPercent"])
    }
}
