package dev.atoll.app.app

import dev.atoll.app.files.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant

fun workspaceReport(performance: Boolean, telemetry: AppTelemetry, context: JsonObject, now: Long): JsonObject = buildJsonObject {
    put("format", "atoll-android-report.v1"); put("kind", if (performance) "performance" else "diagnostics")
    put("createdAt", Instant.ofEpochMilli(now).toString()); put("context", context); put("telemetry", telemetry.snapshot())
}

suspend fun exportWorkspaceReport(services: WorkspaceServices, channel: String, cacheDir: File,
    performance: Boolean, telemetry: AppTelemetry, context: JsonObject, now: Long = System.currentTimeMillis()): FileReference {
    val bytes = withContext(Dispatchers.Default) { workspaceReport(performance, telemetry, context, now).toString().toByteArray(Charsets.UTF_8) }
    val repository = FileRepository(channel, services.wire, services.httpClient, services.server, cacheDir)
    val devices = repository.initialize()
    val device = devices.firstOrNull { it.default } ?: devices.firstOrNull() ?: error("本频道没有挂载设备")
    val name = "atoll-${if (performance) "perf" else "diagnostics"}-$now.json"
    return repository.upload(device, "", name, emptyList(), { ByteArrayInputStream(bytes) }, bytes.size.toLong(), "application/json")
}
