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

        val monthlyMultipliers: Map<YearMonth, BigDecimal> = byYear
            .flatMap { (year, points) ->
                points.mapIndexed { idx, p ->
                    val month = idx + 1
                    val ym = YearMonth(year, month)
                    val multiplier = p.value.divide(BigDecimal(100), mc)
                    ym to multiplier
                }
            }
            .toMap()

        months.fold(BigDecimal.ONE) { acc, ym ->
            val m = monthlyMultipliers[ym] ?: throw MissingCpiDataException("No CPI data for $ym")
            acc.multiply(m, mc)
        }.setScale(6, RoundingMode.HALF_EVEN)
    }
}