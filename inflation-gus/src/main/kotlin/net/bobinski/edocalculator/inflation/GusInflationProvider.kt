package net.bobinski.edocalculator.inflation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.YearMonth
import net.bobinski.edocalculator.domain.InflationProvider
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.core.time.monthsUntil
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusIndicatorPoint
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal class GusInflationProvider internal constructor(private val api: GusApi) : InflationProvider {

    private val mc = MathContext.DECIMAL64

    override suspend fun getInflationMultiplier(start: YearMonth, end: YearMonth): BigDecimal = coroutineScope {
        if (start >= end) return@coroutineScope BigDecimal.ONE

        val months = start.monthsUntil(end)
        if (months.isEmpty()) return@coroutineScope BigDecimal.ONE

        val yearlyData = fetchInflationData(months)
        val monthlyMultipliers = yearlyData.toMonthlyMultipliers()

        return@coroutineScope months.fold(BigDecimal.ONE) { acc, ym ->
            val multiplier = monthlyMultipliers[ym]
                ?: throw MissingCpiDataException.forMonth(ym)
            acc.multiply(multiplier, mc)
        }.setScale(6, RoundingMode.HALF_EVEN)
    }

    private suspend fun fetchInflationData(months: List<YearMonth>): Map<Int, List<GusIndicatorPoint>> =
        coroutineScope {
            months
                .asSequence()
                .map { it.year }
                .distinct()
                .map { year ->
                    async(Dispatchers.IO) { year to api.fetchYearInflation(year) }
                }
                .toList()
                .awaitAll()
                .toMap()
        }

    private fun Map<Int, List<GusIndicatorPoint>>.toMonthlyMultipliers(): Map<YearMonth, BigDecimal> {
        val result = mutableMapOf<YearMonth, BigDecimal>()

        values.forEach { pointList: List<GusIndicatorPoint> ->
            pointList
                .sortedBy { it.periodId }
                .forEach { point: GusIndicatorPoint ->
                    val month = point.toYearMonth()
                    val multiplier = point.value.divide(BigDecimal(100), mc)
                    result[month] = multiplier
                }
        }

        return result
    }

    private fun GusIndicatorPoint.toYearMonth(): YearMonth {
        val monthNumber = periodId.extractMonthNumber()
            ?: throw MissingCpiDataException.forInvalidPeriod(year = year, periodId = periodId)
        return YearMonth(year, monthNumber)
    }

    private fun Int.extractMonthNumber(): Int? {
        if (this in 1..12) return this

        val trailing = this % 100
        return trailing.takeIf { it in 1..12 }
    }
}
