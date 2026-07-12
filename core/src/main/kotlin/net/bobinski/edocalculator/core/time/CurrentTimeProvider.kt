package net.bobinski.edocalculator.core.time

import kotlinx.datetime.*
import kotlin.time.Clock
import kotlin.time.Instant

interface CurrentTimeProvider {
    fun instant(): Instant
    fun yearMonth(): YearMonth
    fun localDate(): LocalDate
}

private val EDO_BUSINESS_TIME_ZONE: TimeZone = TimeZone.of("Europe/Warsaw")

internal class SystemCurrentTimeProvider(
    private val timeZone: TimeZone = EDO_BUSINESS_TIME_ZONE,
    private val now: () -> Instant = { Clock.System.now() }
) : CurrentTimeProvider {
    override fun instant(): Instant = now()

    override fun yearMonth(): YearMonth {
        val now = instant().toLocalDateTime(timeZone)
        return YearMonth(now.year, now.month.number)
    }

    override fun localDate(): LocalDate {
        return instant().toLocalDateTime(timeZone).date
    }
}
