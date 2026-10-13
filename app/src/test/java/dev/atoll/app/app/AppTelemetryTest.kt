package dev.atoll.app.app

import kotlinx.serialization.json.*
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
