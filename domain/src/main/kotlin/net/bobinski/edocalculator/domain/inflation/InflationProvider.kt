package net.bobinski.edocalculator.domain.inflation

import kotlinx.datetime.YearMonth
import java.math.BigDecimal

interface InflationProvider {
    /**
     * Returns the cumulative inflation multiplier for the period
     * from [start] (inclusive) to [end] (exclusive).
     *
     * Example: if inflation was +10% total in this period, returns 1.10
     * If deflation: returns e.g. 0.97
     */
    suspend fun getInflationMultiplier(
        start: YearMonth,
        end: YearMonth
    ): BigDecimal

    /**
     * Returns the year-over-year inflation multiplier reported for [month].
     *
     * Example: if annual inflation for the month was +10%, returns 1.10
     * If annual deflation: returns e.g. 0.97
     */
    suspend fun getYearlyInflationMultiplier(month: YearMonth): BigDecimal
}