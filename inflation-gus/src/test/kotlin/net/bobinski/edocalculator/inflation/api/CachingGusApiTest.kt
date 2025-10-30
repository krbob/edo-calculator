package net.bobinski.edocalculator.inflation.api

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class CachingGusApiTest {

    @Test
    fun `complete past year is cached forever`() = runTest {
        val year = 2022
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                year to { pts(year, 12) }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        val a = api.fetchYearInflation(year)
        val b = api.fetchYearInflation(year)

        time.advance(365.days)
        val c = api.fetchYearInflation(year)

        assertEquals(1, delegate.calls.getValue(year))
        assertEquals(12, a.size)
        assertSame(a, b)
        assertSame(a, c)
    }

    @Test
    fun `current year uses ttl and refreshes after it`() = runTest {
        val year = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        var version = 1
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                year to { pts(year, 5, base = if (version == 1) "100.0" else "101.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 5.minutes)

        val a = api.fetchYearInflation(year)
        assertEquals(1, delegate.calls.getValue(year))
        assertEquals(BigDecimal("100.0"), a.first().value)

        time.advance(4.minutes + 59.seconds)
        val b = api.fetchYearInflation(year)
        assertSame(a, b)
        assertEquals(1, delegate.calls.getValue(year))

        time.advance(2.seconds)
        version = 2
        val c = api.fetchYearInflation(year)
        assertEquals(2, delegate.calls.getValue(year))
        assertEquals(BigDecimal("101.0"), c.first().value)
    }

    @Test
    fun `stale-if-error returns old data when refresh fails`() = runTest {
        val year = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                year to { pts(year, 3, "100.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        val first = api.fetchYearInflation(year)
        assertEquals(1, delegate.calls.getValue(year))

        time.advance(2.minutes)
        delegate.throwOn += year

        val second = api.fetchYearInflation(year)
        assertEquals(2, delegate.calls.getValue(year))
        assertSame(first, second)
        assertEquals(3, second.size)
    }

    @Test
    fun `only one refresh runs per year under concurrency`() = runTest {
        val year = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                year to { pts(year, 6, "100.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        api.fetchYearInflation(year)
        assertEquals(1, delegate.calls.getValue(year))

        time.advance(2.minutes)

        (1..20).map { async { api.fetchYearInflation(year) } }.awaitAll()

        assertEquals(2, delegate.calls.getValue(year))
    }

    @Test
    fun `past year cached, current year refreshes independently`() = runTest {
        val past = 2023
        val cur = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                past to { pts(past, 12, "100.0") },
                cur to { pts(cur, 4, "100.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        api.fetchYearInflation(past)
        api.fetchYearInflation(cur)
        assertEquals(1, delegate.calls.getValue(past))
        assertEquals(1, delegate.calls.getValue(cur))

        time.advance(2.minutes)
        api.fetchYearInflation(past)
        api.fetchYearInflation(cur)

        assertEquals(1, delegate.calls.getValue(past))
        assertEquals(2, delegate.calls.getValue(cur))
    }
}

private class CountingGusApi(
    private val responses: MutableMap<Int, () -> List<GusIndicatorPoint>>
) : GusApi {
    val calls = mutableMapOf<Int, Int>().withDefault { 0 }
    var throwOn: MutableSet<Int> = mutableSetOf()

    override suspend fun fetchYearInflation(year: Int): List<GusIndicatorPoint> {
        calls[year] = calls.getValue(year) + 1
        if (year in throwOn) error("boom $year")
        return responses[year]?.invoke()
            ?: error("no response for $year")
    }
}

private fun pts(year: Int, count: Int, base: String = "100.0"): List<GusIndicatorPoint> =
    (1..count).map { i ->
        GusIndicatorPoint(
            year = year,
            periodId = 240 + i,
            value = BigDecimal(base)
        )
    }