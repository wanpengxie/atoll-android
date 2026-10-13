package dev.atoll.app.net

import dev.atoll.app.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Read-only live check; authentication is read from the environment, never logged. */
class LiveSmokeTest {
    @Test fun authenticatesAttachesAndReadsHistory() = runBlocking {
        val url = System.getenv("ATOLL_URL").orEmpty()
        val token = System.getenv("ATOLL_TOKEN").orEmpty()
        assumeTrue("opt-in live smoke", url.isNotEmpty() && token.isNotEmpty())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
        }.build()
        val identity = IdentityClient(url, MemoryKeyValueStore(), client)
        assertTrue(identity.session().isNotEmpty())
        Wire(url, identity.client, "Android JVM smoke").use { wire ->
            wire.start()
            val attached = withTimeout(15_000) { wire.state.filterIsInstance<WireState.Attached>().first() }
            val memberships = attached.receipt["memberships"]!!.jsonArray
            assertTrue("automation principal has channel memberships", memberships.isNotEmpty())
            val historyMeta = attached.receipt["history_meta"] as? JsonArray
            val active = historyMeta?.firstOrNull { it.jsonObject["has_rows"] == JsonPrimitive(true) }?.jsonObject?.get("channel_id")
            val channel = active ?: memberships.first().jsonObject.getValue("channel_id")
            val page = withTimeout(15_000) { wire.history(buildJsonObject {
                put("channel_id", channel); put("generation", attached.generation)
                put("purpose", "initial-tail"); put("priority", "foreground"); put("limit", 10)
            }) }
            assertEquals(channel, page.end["channel_id"])
            assertTrue(page.rows.size <= 10)
            assertTrue(page.rows.zipWithNext().all { (a, b) -> a.seq < b.seq })
            assertTrue(page.scanLowSeq <= page.scanHighSeq)
        }
    }
}
