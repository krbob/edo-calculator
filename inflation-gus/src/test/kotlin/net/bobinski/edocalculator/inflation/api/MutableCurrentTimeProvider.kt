package net.bobinski.edocalculator.inflation.api

import kotlinx.datetime.*
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class MutableCurrentTimeProvider(start: Instant) : CurrentTimeProvider {
    private var _now = start
    override fun instant(): Instant = _now
    fun advance(d: Duration) {
        _now += d
    }

    override fun yearMonth(): YearMonth {
        val now = _now.toLocalDateTime(TimeZone.UTC)
        val monthNumber = now.month.ordinal + 1
        return YearMonth(now.year, monthNumber)
    }
}

@OptIn(ExperimentalTime::class)
fun fixedNow(year: Int, month: Int = 6, day: Int = 15): Instant =
    LocalDateTime(year, month, day, 12, 0).toInstant(TimeZone.UTC)