package net.bobinski.edocalculator.core.time

import kotlinx.datetime.YearMonth

fun YearMonth.monthsUntil(endExclusive: YearMonth): List<YearMonth> {
    require(this <= endExclusive) { "Start $this must not be after end $endExclusive" }
    if (this == endExclusive) return emptyList()

    val months = ArrayList<YearMonth>()
    var current = this
    while (current < endExclusive) {
        months += current
        current = current.shiftBy(months = 1)
    }
    return months
}

private fun YearMonth.shiftBy(months: Int): YearMonth {
    if (months == 0) return this

    val zeroBasedMonth = month.ordinal
    val totalMonths = year * 12 + zeroBasedMonth + months
    val newYear = totalMonths / 12
    val newMonthNumber = totalMonths % 12 + 1
    return YearMonth(newYear, newMonthNumber)
}
