package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.LocalDate
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.validation.CalculationLimits
import java.math.BigDecimal
import java.math.RoundingMode

class CalculateEdoHistoryUseCase(
    private val calculateEdoValueUseCase: CalculateEdoValueUseCase,
    private val currentTimeProvider: CurrentTimeProvider
) {
    suspend operator fun invoke(
        purchaseDate: LocalDate,
        firstPeriodRate: BigDecimal,
        margin: BigDecimal,
        principal: BigDecimal = BigDecimal(100),
        from: LocalDate? = null,
        to: LocalDate? = null
    ): Result {
        val currentDate = currentTimeProvider.localDate()
        val endDate = to ?: currentDate

        require(principal.signum() >= 0) { "Principal must not be negative." }
        require(firstPeriodRate.signum() >= 0) { "First period rate must not be negative." }
        require(margin.signum() >= 0) { "Margin must not be negative." }
        CalculationLimits.requireSupportedEdoInputs(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal
        )

        if (to != null) {
            require(endDate <= currentDate) { "To date must not be in the future." }
        }

        require(purchaseDate <= endDate) { "To date must be on or after purchase date." }

        val startDate = maxOf(from ?: purchaseDate, purchaseDate)
        require(startDate <= endDate) { "From date must not be after to date." }
        CalculationLimits.requireSupportedHistoryRange(startDate, endDate)

        val points = calculateEdoValueUseCase.dailyValues(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal,
            from = startDate,
            until = endDate
        ).map { value ->
            HistoryPoint(
                date = value.date,
                totalValue = value.totalValue,
                totalAccruedInterest = value.totalAccruedInterest
            )
        }.toList()

        return Result(
            purchaseDate = purchaseDate,
            from = startDate,
            until = endDate,
            firstPeriodRate = firstPeriodRate.withMinimumScale(PERCENT_SCALE),
            margin = margin.withMinimumScale(PERCENT_SCALE),
            principal = principal.setScale(2, RoundingMode.HALF_UP),
            points = points
        )
    }

    data class Result(
        val purchaseDate: LocalDate,
        val from: LocalDate,
        val until: LocalDate,
        val firstPeriodRate: BigDecimal,
        val margin: BigDecimal,
        val principal: BigDecimal,
        val points: List<HistoryPoint>
    )

    data class HistoryPoint(
        val date: LocalDate,
        val totalValue: BigDecimal,
        val totalAccruedInterest: BigDecimal
    )

    private fun BigDecimal.withMinimumScale(minimumScale: Int): BigDecimal =
        if (scale() < minimumScale) setScale(minimumScale) else this

    private companion object {
        private const val PERCENT_SCALE = 2
    }
}
