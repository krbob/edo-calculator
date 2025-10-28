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

        cache[year]?.takeIf { it.isFresh(year) }?.let { return it.data }

        return lock.withLock {
            cache[year]?.takeIf { it.isFresh(year) }?.let { return@withLock it.data }

            val refreshed = runCatching { delegate.fetchYearInflation(year) }
                .onFailure { error ->
                    cache[year]?.let { return@withLock it.data }
                    throw error
                }
                .getOrThrow()

            val entry = Entry(
                data = refreshed,
                storedAt = clock.now(),
                complete = refreshed.isComplete(year)
            )
            cache[year] = entry
            entry.data
        }
    }

    private fun nowYear(): Int = clock.now().toLocalDateTime(TimeZone.UTC).year

    private fun List<GusIndicatorPoint>.isComplete(year: Int): Boolean =
        filter { it.year == year }
            .map { it.periodId }
            .toSet()
            .size == 12

    private fun Entry.isFresh(year: Int): Boolean {
        if (complete && year < nowYear()) return true
        val age = clock.now() - storedAt
        return age < ttl
    }
}