package dev.atoll.app.terminal

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in. Secrets are read from the environment and never logged. Creates only its own shell. */
class PtyLiveSmokeTest {
    @Test fun realNodePreservesShellAcrossDetachAndResizesDevicePty() = runBlocking<Unit> {
        val base = System.getenv("ATOLL_URL").orEmpty()
        val token = System.getenv("ATOLL_TOKEN").orEmpty()
        assumeTrue("Set ATOLL_URL and ATOLL_TOKEN for the live PTY smoke", base.isNotEmpty() && token.isNotEmpty())
        val channelId = System.getenv("ATOLL_CHANNEL").orEmpty().ifEmpty { "ba676224-ff3c-4975-904b-6473e4dba788" }
        val http = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }.build()
        val client = PtyClient(base, http, idleDelayMs = 1000)
        val output = Channel<ByteArray>(Channel.UNLIMITED)
        var handle: PtyHandle? = null
        val received = StringBuilder()
        suspend fun readUntil(pattern: Regex): MatchResult = withTimeout(20_000) {
            while (true) {
                pattern.find(received.toString())?.let { match ->
                    received.delete(0, match.range.last + 1)
                    return@withTimeout match
                }
                received.append(output.receive().toString(Charsets.UTF_8))
            }
            error("unreachable")
        }
        try {
            val first = client.attach(channelId, "local-device", 91, 17, onData = { output.trySend(it) }); handle = first
            val ready = withTimeout(20_000) { first.status.filterIsInstance<PtyStatus.Ready>().first() }
            assertTrue(ready.session.isNotEmpty())
            first.write("echo __FIRST_PID_${'$'}${'$'}__; stty size\r".toByteArray())
            val pid = readUntil(Regex("__FIRST_PID_(\\d+)__")).groupValues[1]
            readUntil(Regex("17 91"))
            first.detach()
            // Wait beyond socket-idle close as well as switching streams.
            delay(1400)
            val resumed = client.attach(channelId, "local-device", 109, 23, onData = { output.trySend(it) }); handle = resumed
            assertEquals(ready.session, withTimeout(20_000) { resumed.status.filterIsInstance<PtyStatus.Ready>().first() }.session)
            resumed.write("echo __RESUME_PID_${'$'}${'$'}__; stty size\r".toByteArray())
            assertEquals(pid, readUntil(Regex("__RESUME_PID_(\\d+)__")).groupValues[1])
            readUntil(Regex("23 109"))
            resumed.resize(87, 13)
            resumed.write("stty size\r".toByteArray())
            readUntil(Regex("13 87"))
        } finally {
            handle?.close()
            // Let the explicit close frame leave before closing the shared socket.
            delay(250); client.close(); output.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }
}
