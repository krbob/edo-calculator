package net.bobinski.edocalculator.domain

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
}