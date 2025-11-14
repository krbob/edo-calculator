package net.bobinski.edocalculator.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
interface CurrentTimeProvider {
    fun instant(): Instant
    fun yearMonth(): YearMonth
    fun localDate(): LocalDate
}

@OptIn(ExperimentalTime::class)
internal class SystemCurrentTimeProvider(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CurrentTimeProvider {
    override fun instant(): Instant = Clock.System.now()

    override fun yearMonth(): YearMonth {
        val now = instant().toLocalDateTime(timeZone)
        val monthNumber = now.month.ordinal + 1
        return YearMonth(now.year, monthNumber)
    }

    override fun localDate(): LocalDate {
        return instant().toLocalDateTime(timeZone).date
    }
}
