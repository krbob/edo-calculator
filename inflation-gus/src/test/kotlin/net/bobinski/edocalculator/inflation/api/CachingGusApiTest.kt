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
                key(year) to { pts(year, 12) }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        val a = api.fetchYearInflation(GusAttribute.MONTHLY, year)
        val b = api.fetchYearInflation(GusAttribute.MONTHLY, year)

        time.advance(365.days)
        val c = api.fetchYearInflation(GusAttribute.MONTHLY, year)

        assertEquals(1, delegate.calls.getValue(key(year)))
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
                key(year) to { pts(year, 5, base = if (version == 1) "100.0" else "101.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 5.minutes)

        val a = api.fetchYearInflation(GusAttribute.MONTHLY, year)
        assertEquals(1, delegate.calls.getValue(key(year)))
        assertEquals(BigDecimal("100.0"), a.first().value)

        time.advance(4.minutes + 59.seconds)
        val b = api.fetchYearInflation(GusAttribute.MONTHLY, year)
        assertSame(a, b)
        assertEquals(1, delegate.calls.getValue(key(year)))

        time.advance(2.seconds)
        version = 2
        val c = api.fetchYearInflation(GusAttribute.MONTHLY, year)
        assertEquals(2, delegate.calls.getValue(key(year)))
        assertEquals(BigDecimal("101.0"), c.first().value)
    }

    @Test
    fun `stale-if-error returns old data when refresh fails`() = runTest {
        val year = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                key(year) to { pts(year, 3, "100.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        val first = api.fetchYearInflation(GusAttribute.MONTHLY, year)
        assertEquals(1, delegate.calls.getValue(key(year)))

        time.advance(2.minutes)
        delegate.throwOn += key(year)

        val second = api.fetchYearInflation(GusAttribute.MONTHLY, year)
        assertEquals(2, delegate.calls.getValue(key(year)))
        assertSame(first, second)
        assertEquals(3, second.size)
    }

    @Test
    fun `only one refresh runs per year under concurrency`() = runTest {
        val year = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                key(year) to { pts(year, 6, "100.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        api.fetchYearInflation(GusAttribute.MONTHLY, year)
        assertEquals(1, delegate.calls.getValue(key(year)))

        time.advance(2.minutes)

        (1..20).map { async { api.fetchYearInflation(GusAttribute.MONTHLY, year) } }.awaitAll()

        assertEquals(2, delegate.calls.getValue(key(year)))
    }

    @Test
    fun `past year cached, current year refreshes independently`() = runTest {
        val past = 2023
        val cur = 2025
        val time = MutableCurrentTimeProvider(fixedNow(2025))
        val delegate = CountingGusApi(
            responses = mutableMapOf(
                key(past) to { pts(past, 12, "100.0") },
                key(cur) to { pts(cur, 4, "100.0") }
            )
        )
        val api = CachingGusApi(delegate = delegate, currentTimeProvider = time, ttl = 1.minutes)

        api.fetchYearInflation(GusAttribute.MONTHLY, past)
        api.fetchYearInflation(GusAttribute.MONTHLY, cur)
        assertEquals(1, delegate.calls.getValue(key(past)))
        assertEquals(1, delegate.calls.getValue(key(cur)))

        time.advance(2.minutes)
        api.fetchYearInflation(GusAttribute.MONTHLY, past)
        api.fetchYearInflation(GusAttribute.MONTHLY, cur)

        assertEquals(1, delegate.calls.getValue(key(past)))
        assertEquals(2, delegate.calls.getValue(key(cur)))
    }
}

private class CountingGusApi(
    private val responses: MutableMap<Pair<GusAttribute, Int>, () -> List<GusIndicatorPoint>>
) : GusApi {
    val calls = mutableMapOf<Pair<GusAttribute, Int>, Int>().withDefault { 0 }
    var throwOn: MutableSet<Pair<GusAttribute, Int>> = mutableSetOf()

    override suspend fun fetchYearInflation(attribute: GusAttribute, year: Int): List<GusIndicatorPoint> {
        val key = attribute to year
        calls[key] = calls.getValue(key) + 1
        if (key in throwOn) error("boom $attribute $year")
        return responses[key]?.invoke()
            ?: error("no response for $attribute $year")
    }
}

private fun key(year: Int) = GusAttribute.MONTHLY to year

private fun pts(year: Int, count: Int, base: String = "100.0"): List<GusIndicatorPoint> =
    (1..count).map { i ->
        GusIndicatorPoint(
            year = year,
            periodId = 240 + i,
            value = BigDecimal(base)
        )
    }