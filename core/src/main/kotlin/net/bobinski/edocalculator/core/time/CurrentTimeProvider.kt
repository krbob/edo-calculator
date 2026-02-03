package net.bobinski.edocalculator.core.time

import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.Instant

interface CurrentTimeProvider {
    fun instant(): Instant
    fun yearMonth(): YearMonth
    fun localDate(): LocalDate
}

internal class SystemCurrentTimeProvider(
    private val timeZone: TimeZone = TimeZone.currentSystemDefault()
) : CurrentTimeProvider {
    override fun instant(): Instant = Clock.System.now()

    override fun yearMonth(): YearMonth {
        val now = instant().toLocalDateTime(timeZone)
        return YearMonth(now.year, now.month.number)
    }

    override fun localDate(): LocalDate {
        return instant().toLocalDateTime(timeZone).date
    }
}
