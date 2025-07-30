package net.bobinski.edocalculator.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RateLimiterTest {
    private val window: Duration = 100.milliseconds

    @Test
    fun `allows up to limit within window`() = runTest {
        val limiter = RateLimiter(limit = 3, window = window)
        var counter = 0

        repeat(3) {
            launch { limiter.limit { counter++ } }
        }
        delay(20.milliseconds)

        assertEquals(3, counter)
    }

    @Test
    fun `blocks when limit exceeded`() = runTest {
        val limiter = RateLimiter(limit = 2, window = window)
        val results = mutableListOf<Int>()

        val t0 = System.currentTimeMillis()
        (1..3).map {
            async { limiter.limit { results.add(it) } }
        }.awaitAll()
        val t1 = System.currentTimeMillis()

        assertEquals(listOf(1, 2, 3), results)
        assertTrue(t1 - t0 >= window.inWholeMilliseconds)
    }

    @Test
    fun `resets window correctly`() = runTest {
        val limiter = RateLimiter(limit = 1, window = window)
        var counter = 0

        limiter.limit { counter++ }
        val t0 = System.currentTimeMillis()
        async { limiter.limit { counter++ } }.await()
        val t1 = System.currentTimeMillis()

        assertEquals(2, counter)
        assertTrue(t1 - t0 >= window.inWholeMilliseconds)
    }

    @Test
    fun `events are rate limited over multiple windows`() = runTest {
        val limiter = RateLimiter(limit = 2, window = window)
        val eventTimes = mutableListOf<Long>()

        val start = System.currentTimeMillis()
        (1..5).map {
            async { limiter.limit { eventTimes.add(System.currentTimeMillis() - start) } }
        }.awaitAll()

        assertTrue(eventTimes[0] < 20)
        assertTrue(eventTimes[1] < 20)

        assertTrue(eventTimes[2] in 100..140)
        assertTrue(eventTimes[3] in 100..140)

        assertTrue(eventTimes[4] in 200..260)
    }
}