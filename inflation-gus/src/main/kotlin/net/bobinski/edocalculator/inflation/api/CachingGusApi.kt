package net.bobinski.edocalculator.inflation.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
internal class CachingGusApi(
    private val delegate: GusApi,
    private val clock: Clock = Clock.System,
    private val ttl: Duration = 1.hours
) : GusApi {

    private data class Entry(
        val data: List<GusIndicatorPoint>,
        val storedAt: Instant,
        val complete: Boolean
    )

    private val cache = ConcurrentHashMap<Int, Entry>()
    private val locks = ConcurrentHashMap<Int, Mutex>()

    override suspend fun fetchYearInflation(year: Int): List<GusIndicatorPoint> {
        val lock = locks.computeIfAbsent(year) { Mutex() }

        cache[year]?.let { e ->
            if (e.complete) return e.data
            if (!isExpired(year, e)) return e.data
        }

        return lock.withLock {
            cache[year]?.let { e ->
                if (e.complete) return@withLock e.data
                if (!isExpired(year, e)) return@withLock e.data
            }

            val fresh = runCatching { delegate.fetchYearInflation(year) }
                .onFailure { ex ->
                    cache[year]?.let { return@withLock it.data }
                    throw ex
                }
                .getOrThrow()

            cache[year] = Entry(
                data = fresh,
                storedAt = clock.now(),
                complete = isCompleteYear(year, fresh)
            )
            fresh
        }
    }

    private fun nowYear(): Int = clock.now().toLocalDateTime(TimeZone.UTC).year

    private fun isCompleteYear(year: Int, points: List<GusIndicatorPoint>): Boolean {
        val months = points
            .filter { it.year == year }
            .map { it.periodId }
            .toSet()
        return months.size == 12
    }

    private fun isExpired(year: Int, e: Entry): Boolean {
        if (e.complete && year < nowYear()) return false
        val age = clock.now() - e.storedAt
        return age >= ttl
    }
}