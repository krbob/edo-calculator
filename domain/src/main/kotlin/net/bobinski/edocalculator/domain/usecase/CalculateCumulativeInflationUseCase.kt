package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.YearMonth
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.InflationProvider
import java.math.BigDecimal

class CalculateCumulativeInflationUseCase(
    private val inflationProvider: InflationProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    suspend operator fun invoke(start: YearMonth): Result {
        val endExclusive = currentTimeProvider.yearMonth()
        val multiplier = inflationProvider.getInflationMultiplier(start, endExclusive)

        return Result(
            from = start,
            untilExclusive = endExclusive,
            multiplier = multiplier
        )
    }

    data class Result(
        val from: YearMonth,
        val untilExclusive: YearMonth,
        val multiplier: BigDecimal
    )
}