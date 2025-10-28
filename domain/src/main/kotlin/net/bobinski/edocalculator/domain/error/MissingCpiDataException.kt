package net.bobinski.edocalculator.domain.error

import kotlinx.datetime.YearMonth

class MissingCpiDataException(message: String) : RuntimeException(message) {

    companion object {
        fun forIncompleteYear(year: Int, expected: Int, actual: Int): MissingCpiDataException =
            MissingCpiDataException(
                "Missing CPI data for $year: expected $expected items, got $actual"
            )

        fun forMissingPeriods(year: Int, periods: Collection<Int>): MissingCpiDataException =
            MissingCpiDataException(
                "Missing CPI period data for $year: ${periods.joinToString(",")}"
            )

        fun forMonth(month: YearMonth): MissingCpiDataException =
            MissingCpiDataException("No CPI data for $month")
    }
}