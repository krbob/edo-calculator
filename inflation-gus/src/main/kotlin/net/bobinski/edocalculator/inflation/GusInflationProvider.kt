package net.bobinski.edocalculator.inflation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import net.bobinski.edocalculator.domain.InflationProvider
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusIndicatorPoint
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal class GusInflationProvider internal constructor(private val api: GusApi) : InflationProvider {

    private val mc = MathContext.DECIMAL64

    override suspend fun getInflationMultiplier(start: YearMonth, end: YearMonth): BigDecimal = coroutineScope {
        if (start >= end) return@coroutineScope BigDecimal.ONE

        val months = start..end.minusMonth()
        val years = months.map { it.year }.distinct()

        val byYear: Map<Int, List<GusIndicatorPoint>> = years
            .map { year ->
                async(Dispatchers.IO) {
                    year to api.fetchYearInflation(year)
                }
            }
            .awaitAll()
            .toMap()

        val monthlyMultipliers = byYear.toMonthlyMultipliers()

        return@coroutineScope months.fold(BigDecimal.ONE) { acc, ym ->
            val multiplier = monthlyMultipliers[ym]
                ?: throw MissingCpiDataException.forMonth(ym)
            acc.multiply(multiplier, mc)
        }.setScale(6, RoundingMode.HALF_EVEN)
    }

    private fun Map<Int, List<GusIndicatorPoint>>.toMonthlyMultipliers(): Map<YearMonth, BigDecimal> =
        entries.asSequence()
            .flatMap { (_, points) ->
                points
                    .sortedBy { it.periodId }
                    .mapIndexed { index, point ->
                        val month = YearMonth(point.year, index + 1)
                        val multiplier = point.value.divide(BigDecimal(100), mc)
                        month to multiplier
                    }
            }
            .toMap()
}