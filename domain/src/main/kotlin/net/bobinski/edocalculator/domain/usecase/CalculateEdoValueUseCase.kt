package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.edo.EdoPeriodBreakdown
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

class CalculateEdoValueUseCase(
    private val inflationProvider: InflationProvider,
    private val currentTimeProvider: CurrentTimeProvider
) {

    private val mc = MathContext.DECIMAL64

    suspend operator fun invoke(
        purchaseDate: LocalDate,
        firstPeriodRate: BigDecimal,
        margin: BigDecimal,
        principal: BigDecimal = BigDecimal(100),
        asOf: LocalDate? = null
    ): Result {
        val currentDate = currentTimeProvider.localDate()
        val evaluationDate = asOf ?: currentDate

        if (asOf != null) {
            require(evaluationDate <= currentDate) { "As-of date must not be in the future." }
        }

        require(purchaseDate <= evaluationDate) { "As-of date must be on or after purchase date." }
        require(principal.signum() >= 0) { "Principal must not be negative." }
        require(firstPeriodRate.signum() >= 0) { "First period rate must not be negative." }
        require(margin.signum() >= 0) { "Margin must not be negative." }

        val normalizedPrincipal = principal.setScale(2, RoundingMode.HALF_UP)
        var currentPrincipal = normalizedPrincipal
        var totalInterest = BigDecimal.ZERO
        val periods = mutableListOf<EdoPeriodBreakdown>()
        var periodStart = purchaseDate

        for (periodIndex in 1..TOTAL_PERIODS) {
            if (evaluationDate < periodStart) {
                break
            }

            val periodEnd = periodStart.plus(1, DateTimeUnit.YEAR)
            val daysInPeriod = periodStart.daysUntil(periodEnd)
            val fullPeriod = evaluationDate >= periodEnd
            val daysElapsed = if (fullPeriod) {
                daysInPeriod
            } else {
                periodStart.daysUntil(evaluationDate)
            }

            val inflationFraction = if (periodIndex == 1) null else
                inflationFractionForPeriod(periodStart)

            val rateFraction = annualRateFraction(
                periodIndex = periodIndex,
                firstPeriodRate = firstPeriodRate,
                margin = margin,
                inflationFraction = inflationFraction
            )

            val interest = when {
                daysElapsed == 0 -> BigDecimal.ZERO
                fullPeriod -> currentPrincipal.multiply(rateFraction, mc)
                else -> {
                    val dailyRate = rateFraction.divide(BigDecimal(daysInPeriod), mc)
                    currentPrincipal.multiply(dailyRate, mc)
                        .multiply(BigDecimal(daysElapsed), mc)
                }
            }

            totalInterest = totalInterest.add(interest, mc)
            val valueAfter = currentPrincipal.add(interest, mc)

            periods += EdoPeriodBreakdown(
                index = periodIndex,
                startDate = periodStart.toString(),
                endDate = periodEnd.toString(),
                daysInPeriod = daysInPeriod,
                daysElapsed = daysElapsed,
                ratePercent = rateFraction.toPercent(),
                inflationPercent = inflationFraction?.toPercent(),
                interestAccrued = interest.setScale(2, RoundingMode.HALF_UP),
                value = valueAfter.setScale(2, RoundingMode.HALF_UP)
            )

            if (!fullPeriod) {
                break
            }

            currentPrincipal = valueAfter
            periodStart = periodEnd
        }

        val totalValue = normalizedPrincipal.add(totalInterest, mc).setScale(2, RoundingMode.HALF_UP)

        return Result(
            purchaseDate = purchaseDate,
            asOf = evaluationDate,
            firstPeriodRate = firstPeriodRate.setScale(2, RoundingMode.HALF_UP),
            margin = margin.setScale(2, RoundingMode.HALF_UP),
            principal = normalizedPrincipal,
            edoValue = EdoValue(
                totalValue = totalValue,
                totalAccruedInterest = totalInterest.setScale(2, RoundingMode.HALF_UP),
                periods = periods
            )
        )
    }

    private fun annualRateFraction(
        periodIndex: Int,
        firstPeriodRate: BigDecimal,
        margin: BigDecimal,
        inflationFraction: BigDecimal?
    ): BigDecimal {
        return if (periodIndex == 1) {
            firstPeriodRate.movePointLeft(2)
        } else {
            inflationFraction!!.add(margin.movePointLeft(2), mc)
        }
    }

    private suspend fun inflationFractionForPeriod(
        periodStart: LocalDate
    ): BigDecimal {
        val inflationMonth = YearMonth(periodStart.year, periodStart.month.ordinal + 1)
            .minus(2, DateTimeUnit.MONTH)

        val multiplier = inflationProvider.getYearlyInflationMultiplier(inflationMonth)

        val raw = multiplier.subtract(BigDecimal.ONE, mc)
        return if (raw.signum() < 0) BigDecimal.ZERO else raw
    }

    private fun BigDecimal.toPercent(): BigDecimal =
        multiply(BigDecimal(100), mc).setScale(2, RoundingMode.HALF_UP)

    data class Result(
        val purchaseDate: LocalDate,
        val asOf: LocalDate,
        val firstPeriodRate: BigDecimal,
        val margin: BigDecimal,
        val principal: BigDecimal,
        val edoValue: EdoValue
    )

    private companion object {
        private const val TOTAL_PERIODS = 10
    }
}
