package net.bobinski.edocalculator.inflation.api

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
internal class CachingGusApi(
    private val delegate: GusApi,
    private val currentTimeProvider: CurrentTimeProvider,
    private val ttl: Duration = 1.hours
) : GusApi {

    private data class Entry(
        val data: List<GusIndicatorPoint>,
        val storedAt: Instant,
        val complete: Boolean
    )

    private data class CacheKey(val attribute: GusAttribute, val year: Int)

    private val cache = ConcurrentHashMap<CacheKey, Entry>()
    private val locks = ConcurrentHashMap<CacheKey, Mutex>()

    override suspend fun fetchYearInflation(attribute: GusAttribute, year: Int): List<GusIndicatorPoint> {
        val key = CacheKey(attribute, year)
        val lock = locks.computeIfAbsent(key) { Mutex() }

        cache[key]?.takeIf { it.isFresh(year) }?.let { return it.data }

        return lock.withLock {
            cache[key]?.takeIf { it.isFresh(year) }?.let { return@withLock it.data }

            val refreshed = runCatching { delegate.fetchYearInflation(attribute, year) }
                .onFailure { error ->
                    cache[key]?.let { return@withLock it.data }
                    throw error
                }
                .getOrThrow()

            val entry = Entry(
                data = refreshed,
                storedAt = currentTimeProvider.instant(),
                complete = refreshed.isComplete(year)
            )
            cache[key] = entry
            entry.data
        }
    }

    private fun List<GusIndicatorPoint>.isComplete(year: Int): Boolean =
        filter { it.year == year }
            .map { it.periodId }
            .toSet()
            .size == 12

    private fun Entry.isFresh(year: Int): Boolean {
        val nowDate = currentTimeProvider.localDate()
        val currentYear = nowDate.year
        if (complete && year < currentYear) return true

        val age = currentTimeProvider.instant() - storedAt

        if (year == currentYear) {
            val cachedRecords = data.count { it.year == year }
            val expectedRecords = expectedRecordsForCurrentYear(nowDate)
            if (cachedRecords >= expectedRecords) return true
            return age < ttl
        }

        return age < ttl
    }

    private fun expectedRecordsForCurrentYear(now: LocalDate): Int {
        return (now.month.number - 1).coerceIn(0, 12)
    }
}
