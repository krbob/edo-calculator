package net.bobinski.edocalculator.inflation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.YearMonth
import net.bobinski.edocalculator.domain.InflationProvider
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusAttribute
import net.bobinski.edocalculator.inflation.api.GusIndicatorPoint
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal class GusInflationProvider internal constructor(private val api: GusApi) : InflationProvider {

    private val mc = MathContext.DECIMAL64

    override suspend fun getInflationMultiplier(start: YearMonth, end: YearMonth): BigDecimal = coroutineScope {
        if (start >= end) return@coroutineScope BigDecimal.ONE

        val months = (start..<end).toList()
        if (months.isEmpty()) return@coroutineScope BigDecimal.ONE

        val yearlyData = fetchInflationData(months, GusAttribute.MONTHLY)
        val monthlyMultipliers = yearlyData.toMonthlyMultipliers()

        return@coroutineScope months.fold(BigDecimal.ONE) { acc, ym ->
            val multiplier = monthlyMultipliers[ym]
                ?: throw MissingCpiDataException.forMonth(ym)
            acc.multiply(multiplier, mc)
        }.setScale(6, RoundingMode.HALF_EVEN)
    }

    override suspend fun getYearlyInflationMultiplier(month: YearMonth): BigDecimal = coroutineScope {
        val yearlyData = fetchInflationData(listOf(month), GusAttribute.ANNUAL)
        val yearlyMultipliers = yearlyData.toMonthlyMultipliers()

        return@coroutineScope yearlyMultipliers[month]
            ?.setScale(6, RoundingMode.HALF_EVEN)
            ?: throw MissingCpiDataException.forMonth(month)
    }

    private suspend fun fetchInflationData(
        months: List<YearMonth>,
        attribute: GusAttribute
    ): Map<Int, List<GusIndicatorPoint>> =
        coroutineScope {
            months
                .asSequence()
                .map { it.year }
                .distinct()
                .map { year ->
                    async(Dispatchers.IO) { year to api.fetchYearInflation(attribute, year) }
                }
                .toList()
                .awaitAll()
                .toMap()
        }

    private fun Map<Int, List<GusIndicatorPoint>>.toMonthlyMultipliers(): Map<YearMonth, BigDecimal> {
        val result = mutableMapOf<YearMonth, BigDecimal>()

        forEach { entry ->
            val year = entry.key
            entry.value
                .sortedBy { it.periodId }
                .forEachIndexed { idx: Int, point: GusIndicatorPoint ->
                    val month = idx + 1
                    val multiplier = point.value.divide(BigDecimal(100), mc)
                    result[YearMonth(year, month)] = multiplier
                }
        }

        return result
    }
}