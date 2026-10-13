package dev.atoll.app.net

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class ObsClientTest {
    @Test fun everyObservationEndpointUsesItsCorrectPath() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val obs = ObsClient(server.url("/").toString(), OkHttpClient())
            val calls = listOf<Pair<String, suspend () -> Unit>>(
                "/obs/space/channels" to { obs.spaceChannels(); Unit },
                "/obs/space/channels?parent_id=c%2F%20d" to { obs.spaceChannels("c/ d"); Unit },
                "/obs/space/principals" to { obs.spacePrincipals(); Unit },
                "/obs/space/daemons" to { obs.spaceDaemons(); Unit },
                "/obs/space/actor-descriptions" to { obs.spaceActorDescriptions(); Unit },
                "/obs/channel/c%2F%20d/profile" to { obs.channelProfile("c/ d"); Unit },
                "/obs/channel/c%2F%20d/actors" to { obs.channelActors("c/ d"); Unit },
                "/obs/channel/c%2F%20d/devices" to { obs.channelDevices("c/ d"); Unit },
            )
            for ((path, call) in calls) {
                server.enqueue(MockResponse().setBody("""{"items":[],"complete":true}"""))
                call()
                val request = server.takeRequest()
                assertEquals("GET", request.method); assertEquals(path, request.path)
            }
        }
    }
    @Test fun unauthorizedNotifiesSignOutAndPreservesServerError() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            var notified: HttpFailure? = null
            val obs = ObsClient(server.url("/").toString(), OkHttpClient()) { notified = it }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"unauthorized","detail":"session expired"}"""))
            val failure = runCatching { obs.spaceChannels() }.exceptionOrNull() as HttpFailure
            assertSame(failure, notified)
            assertEquals(401, failure.status); assertEquals("unauthorized", failure.code); assertEquals("session expired", failure.detail)
            notified = null
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"forbidden","detail":"private"}"""))
            assertEquals("forbidden", (runCatching { obs.channelDevices("c") }.exceptionOrNull() as HttpFailure).code)
            assertNull(notified)
        }
    }
}
