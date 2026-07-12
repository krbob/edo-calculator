package net.bobinski.edocalculator.domain.usecase

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.edo.EdoPeriodBreakdown
import net.bobinski.edocalculator.domain.edo.EdoStatus
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import net.bobinski.edocalculator.domain.validation.CalculationLimits
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
        CalculationLimits.requireSupportedEdoInputs(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal
        )

        val maturityDate = purchaseDate.plus(TOTAL_PERIODS, DateTimeUnit.YEAR)
        val schedule = prepareSchedule(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal,
            through = evaluationDate
        )
        val snapshots = schedule.periods.map { period ->
            snapshot(schedule.normalizedPrincipal, period, evaluationDate)
        }
        val finalSnapshot = snapshots.last()

        return Result(
            purchaseDate = purchaseDate,
            asOf = evaluationDate,
            maturityDate = maturityDate,
            status = if (evaluationDate >= maturityDate) EdoStatus.MATURED else EdoStatus.ACTIVE,
            firstPeriodRate = firstPeriodRate.withMinimumScale(PERCENT_SCALE),
            margin = margin.withMinimumScale(PERCENT_SCALE),
            principal = schedule.normalizedPrincipal,
            edoValue = EdoValue(
                totalValue = finalSnapshot.totalValue.toMoney(),
                totalAccruedInterest = finalSnapshot.totalInterest.toMoney(),
                periods = schedule.periods.zip(snapshots) { period, periodSnapshot ->
                    period.toBreakdown(periodSnapshot)
                }
            )
        )
    }

    internal suspend fun dailyValues(
        purchaseDate: LocalDate,
        firstPeriodRate: BigDecimal,
        margin: BigDecimal,
        principal: BigDecimal,
        from: LocalDate,
        until: LocalDate
    ): Sequence<DailyValue> {
        require(purchaseDate <= from) { "History start date must be on or after purchase date." }
        require(from <= until) { "History start date must not be after end date." }

        val schedule = prepareSchedule(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal,
            through = until
        )

        return sequence {
            var date = from
            var periodIndex = schedule.periods.indexOfLast { period -> date >= period.startDate }
                .coerceAtLeast(0)

            while (date <= until) {
                while (
                    periodIndex < schedule.periods.lastIndex &&
                    date >= schedule.periods[periodIndex + 1].startDate
                ) {
                    periodIndex++
                }

                val periodSnapshot = snapshot(
                    normalizedPrincipal = schedule.normalizedPrincipal,
                    period = schedule.periods[periodIndex],
                    evaluationDate = date
                )
                yield(
                    DailyValue(
                        date = date,
                        totalValue = periodSnapshot.totalValue.toMoney(),
                        totalAccruedInterest = periodSnapshot.totalInterest.toMoney()
                    )
                )
                date = date.plus(1, DateTimeUnit.DAY)
            }
        }
    }

    private suspend fun prepareSchedule(
        purchaseDate: LocalDate,
        firstPeriodRate: BigDecimal,
        margin: BigDecimal,
        principal: BigDecimal,
        through: LocalDate
    ): AccrualSchedule {
        val normalizedPrincipal = principal.setScale(2, RoundingMode.HALF_UP)
        val periods = ArrayList<AccrualPeriod>(TOTAL_PERIODS)
        var currentPrincipal = normalizedPrincipal
        var totalInterest = BigDecimal.ZERO
        var periodStart = purchaseDate

        for (periodIndex in 1..TOTAL_PERIODS) {
            if (through < periodStart) {
                break
            }

            val periodEnd = periodStart.plus(1, DateTimeUnit.YEAR)
            val inflationFraction = if (periodIndex == 1) null else
                inflationFractionForPeriod(periodStart)
            val rateFraction = annualRateFraction(
                periodIndex = periodIndex,
                firstPeriodRate = firstPeriodRate,
                margin = margin,
                inflationFraction = inflationFraction
            )
            val fullPeriodInterest = currentPrincipal.multiply(rateFraction, mc)

            periods += AccrualPeriod(
                index = periodIndex,
                startDate = periodStart,
                endDate = periodEnd,
                daysInPeriod = periodStart.daysUntil(periodEnd),
                rateFraction = rateFraction,
                inflationFraction = inflationFraction,
                principalAtStart = currentPrincipal,
                totalInterestBefore = totalInterest,
                fullPeriodInterest = fullPeriodInterest
            )

            totalInterest = totalInterest.add(fullPeriodInterest, mc)
            currentPrincipal = currentPrincipal.add(fullPeriodInterest, mc)
            periodStart = periodEnd
        }

        check(periods.isNotEmpty()) { "Accrual schedule must contain the purchase period." }
        return AccrualSchedule(normalizedPrincipal, periods)
    }

    private fun snapshot(
        normalizedPrincipal: BigDecimal,
        period: AccrualPeriod,
        evaluationDate: LocalDate
    ): PeriodSnapshot {
        val fullPeriod = evaluationDate >= period.endDate
        val daysElapsed = if (fullPeriod) {
            period.daysInPeriod
        } else {
            period.startDate.daysUntil(evaluationDate)
        }
        val periodInterest = when {
            daysElapsed == 0 -> BigDecimal.ZERO
            fullPeriod -> period.fullPeriodInterest
            else -> {
                val dailyRate = period.rateFraction.divide(BigDecimal(period.daysInPeriod), mc)
                period.principalAtStart.multiply(dailyRate, mc)
                    .multiply(BigDecimal(daysElapsed), mc)
            }
        }

        val totalInterest = period.totalInterestBefore.add(periodInterest, mc)
        return PeriodSnapshot(
            daysElapsed = daysElapsed,
            periodInterest = periodInterest,
            periodValue = period.principalAtStart.add(periodInterest, mc),
            totalInterest = totalInterest,
            totalValue = normalizedPrincipal.add(totalInterest, mc)
        )
    }

    private fun AccrualPeriod.toBreakdown(snapshot: PeriodSnapshot): EdoPeriodBreakdown =
        EdoPeriodBreakdown(
            index = index,
            startDate = startDate.toString(),
            endDate = endDate.toString(),
            daysInPeriod = daysInPeriod,
            daysElapsed = snapshot.daysElapsed,
            ratePercent = rateFraction.toPercent(),
            inflationPercent = inflationFraction?.toPercent(),
            interestAccrued = snapshot.periodInterest.toMoney(),
            value = snapshot.periodValue.toMoney()
        )

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
        val inflationMonth = YearMonth(periodStart.year, periodStart.month.number)
            .minus(2, DateTimeUnit.MONTH)

        val multiplier = inflationProvider.getYearlyInflationMultiplier(inflationMonth)

        val raw = multiplier.subtract(BigDecimal.ONE, mc)
        return if (raw.signum() < 0) BigDecimal.ZERO else raw
    }

    private fun BigDecimal.toPercent(): BigDecimal =
        movePointRight(2).withMinimumScale(PERCENT_SCALE)

    private fun BigDecimal.withMinimumScale(minimumScale: Int): BigDecimal =
        if (scale() < minimumScale) setScale(minimumScale) else this

    private fun BigDecimal.toMoney(): BigDecimal = setScale(MONEY_SCALE, RoundingMode.HALF_UP)

    internal data class DailyValue(
        val date: LocalDate,
        val totalValue: BigDecimal,
        val totalAccruedInterest: BigDecimal
    )

    data class Result(
        val purchaseDate: LocalDate,
        val asOf: LocalDate,
        val maturityDate: LocalDate,
        val status: EdoStatus,
        val firstPeriodRate: BigDecimal,
        val margin: BigDecimal,
        val principal: BigDecimal,
        val edoValue: EdoValue
    )

    private data class AccrualSchedule(
        val normalizedPrincipal: BigDecimal,
        val periods: List<AccrualPeriod>
    )

    private data class AccrualPeriod(
        val index: Int,
        val startDate: LocalDate,
        val endDate: LocalDate,
        val daysInPeriod: Int,
        val rateFraction: BigDecimal,
        val inflationFraction: BigDecimal?,
        val principalAtStart: BigDecimal,
        val totalInterestBefore: BigDecimal,
        val fullPeriodInterest: BigDecimal
    )

    private data class PeriodSnapshot(
        val daysElapsed: Int,
        val periodInterest: BigDecimal,
        val periodValue: BigDecimal,
        val totalInterest: BigDecimal,
        val totalValue: BigDecimal
    )

    private companion object {
        private const val MONEY_SCALE = 2
        private const val PERCENT_SCALE = 2
        private const val TOTAL_PERIODS = 10
    }
}
