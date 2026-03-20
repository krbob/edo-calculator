package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.YearMonth
import kotlinx.datetime.plus
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import java.math.BigDecimal

class CalculateMonthlyInflationSeriesUseCase(
    private val inflationProvider: InflationProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {
    suspend operator fun invoke(start: YearMonth, endExclusive: YearMonth): Result {
        val currentMonth = currentTimeProvider.yearMonth()

        require(start < currentMonth) {
            "Start month must be earlier than current month."
        }
        require(endExclusive > start) {
            "End date must be after start date."
        }
        require(endExclusive <= currentMonth) {
            "End month must not be in the future."
        }

        val points = (start..<endExclusive).map { month ->
            MonthlyInflationPoint(
                month = month,
                multiplier = inflationProvider.getInflationMultiplier(month, month.plus(1, DateTimeUnit.MONTH))
            )
        }

        return Result(
            from = start,
            untilExclusive = endExclusive,
            points = points
        )
    }

    data class Result(
        val from: YearMonth,
        val untilExclusive: YearMonth,
        val points: List<MonthlyInflationPoint>
    )

    data class MonthlyInflationPoint(
        val month: YearMonth,
        val multiplier: BigDecimal
    )
}
