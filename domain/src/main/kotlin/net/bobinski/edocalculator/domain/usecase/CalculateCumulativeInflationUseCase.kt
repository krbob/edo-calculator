package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.InflationProvider
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import java.math.BigDecimal

class CalculateCumulativeInflationUseCase(
    private val inflationProvider: InflationProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    suspend operator fun invoke(start: YearMonth): Result {
        var endExclusive = currentTimeProvider.yearMonth()
        val multiplier = try {
            inflationProvider.getInflationMultiplier(start, endExclusive)
        } catch (_: MissingCpiDataException) {
            endExclusive = endExclusive.minusMonth()
            inflationProvider.getInflationMultiplier(start, endExclusive)
        }

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