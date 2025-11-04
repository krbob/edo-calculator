package net.bobinski.edocalculator.domain.edo

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class EdoPeriodBreakdown(
    val index: Int,
    val startDate: String,
    val endDate: String,
    val daysInPeriod: Int,
    val daysElapsed: Int,
    @Contextual val ratePercent: BigDecimal,
    @Contextual val inflationPercent: BigDecimal?,
    @Contextual val interestAccrued: BigDecimal,
    @Contextual val value: BigDecimal
)