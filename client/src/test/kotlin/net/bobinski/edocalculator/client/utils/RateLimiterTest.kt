package net.bobinski.edocalculator.client.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class RateLimiterTest {

    @Test
    fun `never exceeds rps in any 1s window`() = runTest {
        val rps = 5
        val limiter = RateLimiter(
            requestsPerSecond = rps,
            maxConcurrency = 64,
            now = { testScheduler.currentTime.milliseconds }
        )

        val startedAt = mutableListOf<Long>()

        val jobs = (1..40).map {
            async { limiter.limit { startedAt += testScheduler.currentTime } }
        }

        advanceUntilIdle()
        jobs.awaitAll()

        val stamps = startedAt.sorted()
        val horizon = (stamps.lastOrNull() ?: 0L) + 1000L

        fun countInWindow(startMs: Long): Int =
            stamps.count { it in startMs until (startMs + 1000) }

        for (t in 0..horizon step 50) {
            val c = countInWindow(t)
            assertTrue(c <= rps, "window @$t ms has $c > $rps starts")
        }
    }

    @Test
    fun `caps simultaneous executions to maxConcurrency`() = runTest {
        val limiter = RateLimiter(
            requestsPerSecond = 10_000,
            maxConcurrency = 3,
            now = { testScheduler.currentTime.milliseconds }
        )

        val concurrent = AtomicInteger(0)
        var peak = 0

        suspend fun work() {
            val now = concurrent.incrementAndGet()
            if (now > peak) peak = now
            delay(1000)
            concurrent.decrementAndGet()
        }

        val jobs = (1..8).map { async { limiter.limit { work() } } }

        advanceTimeBy(1000)
        advanceUntilIdle()
        jobs.awaitAll()

        assertEquals(3, peak, "concurrency exceeded gate limit")
    }

    @Test
    fun `calls arriving at rps are not overthrottled`() = runTest {
        val rps = 5
        val intervalMs = 1000 / rps
        val limiter = RateLimiter(
            requestsPerSecond = rps,
            maxConcurrency = 8,
            now = { testScheduler.currentTime.milliseconds }
        )

        val starts = MutableList(10) { -1L }

        repeat(starts.size) { i ->
            val job = async { limiter.limit { starts[i] = testScheduler.currentTime } }
            advanceTimeBy(intervalMs.toLong())
            job.await()
        }

        starts.forEachIndexed { i, t ->
            val arrival = i * intervalMs
            assertTrue(t <= arrival.toLong(), "start $i at $t ms later than arrival $arrival ms")
        }
        assertEquals(starts.size * intervalMs.toLong(), testScheduler.currentTime)
    }

    @Test
    fun `eventual progress within rps bound`() = runTest {
        val rps = 5
        val n = 25
        val limiter = RateLimiter(
            requestsPerSecond = rps,
            maxConcurrency = 5,
            now = { testScheduler.currentTime.milliseconds }
        )

        val done = Channel<Unit>(Channel.UNLIMITED)
        repeat(n) { launch { limiter.limit { done.trySend(Unit) } } }

        val upperBoundMs = (ceil(n / rps.toDouble()) * 1000).toLong()

        advanceTimeBy(upperBoundMs)
        advanceUntilIdle()

        var count = 0
        while (!done.isEmpty) {
            done.tryReceive().getOrNull()?.let { count++ }
        }
        assertEquals(n, count, "not all tasks finished within rps-derived bound")
    }
}