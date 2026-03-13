package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
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

        if (to != null) {
            require(endDate <= currentDate) { "To date must not be in the future." }
        }

        require(purchaseDate <= endDate) { "To date must be on or after purchase date." }

        val startDate = maxOf(from ?: purchaseDate, purchaseDate)
        require(startDate <= endDate) { "From date must not be after to date." }

        val points = mutableListOf<HistoryPoint>()
        var date = startDate
        while (date <= endDate) {
            val value = calculateEdoValueUseCase(
                purchaseDate = purchaseDate,
                firstPeriodRate = firstPeriodRate,
                margin = margin,
                principal = principal,
                asOf = date
            )

            points += HistoryPoint(
                date = value.asOf,
                totalValue = value.edoValue.totalValue,
                totalAccruedInterest = value.edoValue.totalAccruedInterest
            )
            date = date.plus(1, DateTimeUnit.DAY)
        }

        return Result(
            purchaseDate = purchaseDate,
            from = startDate,
            until = endDate,
            firstPeriodRate = firstPeriodRate.setScale(2, RoundingMode.HALF_UP),
            margin = margin.setScale(2, RoundingMode.HALF_UP),
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
}
