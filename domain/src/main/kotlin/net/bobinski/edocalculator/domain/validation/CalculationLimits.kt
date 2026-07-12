package net.bobinski.edocalculator.domain.validation

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number
import java.math.BigDecimal

object CalculationLimits {
    const val MIN_CPI_YEAR = 2010
    const val MIN_EDO_PURCHASE_YEAR = 2000
    const val MAX_INFLATION_MONTHS = 360
    const val MAX_EDO_HISTORY_POINTS = 4_000
    const val MAX_DECIMAL_PRECISION = 18
    const val MAX_DECIMAL_SCALE = 6
    const val MIN_DECIMAL_SCALE = -12

    val MAX_PRINCIPAL: BigDecimal = BigDecimal("1000000000000")
    val MAX_RATE_PERCENT: BigDecimal = BigDecimal("1000")

    fun requireSupportedInflationRange(start: YearMonth, endExclusive: YearMonth) {
        require(start.year >= MIN_CPI_YEAR) {
            "Start year must be $MIN_CPI_YEAR or later."
        }

        val months = monthsBetween(start, endExclusive)
        require(months <= MAX_INFLATION_MONTHS) {
            "Inflation range must not exceed $MAX_INFLATION_MONTHS months."
        }
    }

    fun requireSupportedEdoInputs(
        purchaseDate: LocalDate,
        firstPeriodRate: BigDecimal,
        margin: BigDecimal,
        principal: BigDecimal
    ) {
        require(purchaseDate.year >= MIN_EDO_PURCHASE_YEAR) {
            "Purchase year must be $MIN_EDO_PURCHASE_YEAR or later."
        }
        requireSupportedDecimal(principal, "Principal", MAX_PRINCIPAL)
        requireSupportedDecimal(firstPeriodRate, "First period rate", MAX_RATE_PERCENT)
        requireSupportedDecimal(margin, "Margin", MAX_RATE_PERCENT)
    }

    fun requireSupportedHistoryRange(start: LocalDate, endInclusive: LocalDate) {
        val points = start.toEpochDays()
            .let { startEpoch -> endInclusive.toEpochDays() - startEpoch + 1L }
        require(points <= MAX_EDO_HISTORY_POINTS) {
            "EDO history must not exceed $MAX_EDO_HISTORY_POINTS daily points."
        }
    }

    private fun requireSupportedDecimal(value: BigDecimal, label: String, maximum: BigDecimal) {
        require(
            value.precision() <= MAX_DECIMAL_PRECISION &&
                value.scale() in MIN_DECIMAL_SCALE..MAX_DECIMAL_SCALE
        ) {
            "$label exceeds supported precision or scale."
        }
        require(value <= maximum) {
            "$label must not exceed ${maximum.toPlainString()}."
        }
    }

    private fun monthsBetween(start: YearMonth, endExclusive: YearMonth): Long =
        (endExclusive.year.toLong() - start.year.toLong()) * 12L +
            endExclusive.month.number.toLong() - start.month.number.toLong()
}
