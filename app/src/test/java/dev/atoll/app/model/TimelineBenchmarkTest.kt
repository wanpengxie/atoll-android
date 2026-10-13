package dev.atoll.app.model

import dev.atoll.app.core.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Opt in: ATOLL_MODEL_BENCHMARK=1 ./gradlew testDebugUnitTest --rerun-tasks.
 * Fixtures/timers/trim are outside each measured apply + timeline operation.
 */
class TimelineBenchmarkTest {
    @Test fun liveProgressAndMineTimelineAt5000Rows() {
        assumeTrue(System.getenv("ATOLL_MODEL_BENCHMARK") == "1")
        val replica = ChannelReplica(CHANNEL, SELF)
        val initial = mutableListOf<Row>()
        repeat(1000) { index ->
            val seq = index * 5L + 1
            val id = "turn-$index"
            initial += row(seq, id, body = body("text" to "Inspect the repository and explain the change for task $index."))
            initial += response(seq + 1, id, "queued")
            initial += response(seq + 2, id, "processing")
            initial += response(seq + 3, id, "processing", extra = body("text" to "Reading files and checking tests."))
            initial += if (index == 999) response(seq + 4, id, "processing") else response(seq + 4, id, "completed", extra = body("text" to "The change is implemented and its tests passed.\n\n```kotlin\nval result = task.run()\n```"))
        }
        replica.apply(RowBatch(CHANNEL, initial, Origin.CACHE))
        val options = TimelineOptions(selfId = SELF, now = 1)
        replica.timeline(options)
        var seq = 5001L
        val samples = mutableListOf<Long>()
        // The target has a bounded realistic progress history rather than thousands
        // of artificial frames accumulated into one turn during warmup.
        repeat(20) { round ->
            val id = "active-$round"
            replica.add(row(seq++, id), response(seq++, id, "processing"))
            replica.trim(5000)
            replica.timeline(options)
            repeat(40) { iteration ->
                val batch = RowBatch(CHANNEL, listOf(response(seq++, id, "processing", extra = body("text" to "Checking implementation step $iteration"))), Origin.LIVE)
                assertTrue("benchmark must start at 5000 rows", replica.rowCount == 5000)
                val started = System.nanoTime()
                replica.apply(batch)
                val timeline = replica.timeline(options)
                val elapsed = System.nanoTime() - started
                assertTrue(timeline.items.isNotEmpty())
                if (round >= 5) samples += elapsed
                replica.trim(5000)
                replica.timeline(options) // publish the trimmed 5000-row window outside measurement
            }
            replica.add(response(seq++, id, "completed"))
            replica.trim(5000)
        }
        val sorted = samples.sorted()
        val meanMs = samples.average() / 1_000_000
        val p95Ms = sorted[(sorted.size * 0.95).toInt()] / 1_000_000.0
        println("MODEL_BENCHMARK rows=5000 warmup=200 samples=${samples.size} mean_ms=%.3f p95_ms=%.3f".format(java.util.Locale.ROOT, meanMs, p95Ms))
        assertTrue("mean $meanMs ms >= 2ms", meanMs < 2)
    }
}
