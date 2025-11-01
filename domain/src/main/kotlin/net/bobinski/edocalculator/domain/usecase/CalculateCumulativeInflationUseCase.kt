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

    suspend operator fun invoke(start: YearMonth, endExclusive: YearMonth? = null): Result {
        if (endExclusive != null) {
            require(endExclusive > start) {
                "End date must be after start date."
            }
            val multiplier = inflationProvider.getInflationMultiplier(start, endExclusive)
            return Result(
                from = start,
                untilExclusive = endExclusive,
                multiplier = multiplier
            )
        }

        var resolvedEndExclusive = currentTimeProvider.yearMonth()
        val multiplier = try {
            inflationProvider.getInflationMultiplier(start, resolvedEndExclusive)
        } catch (_: MissingCpiDataException) {
            resolvedEndExclusive = resolvedEndExclusive.minusMonth()
            inflationProvider.getInflationMultiplier(start, resolvedEndExclusive)
        }

        return Result(
            from = start,
            untilExclusive = resolvedEndExclusive,
            multiplier = multiplier
        )
    }

    data class Result(
        val from: YearMonth,
        val untilExclusive: YearMonth,
        val multiplier: BigDecimal
    )
}
