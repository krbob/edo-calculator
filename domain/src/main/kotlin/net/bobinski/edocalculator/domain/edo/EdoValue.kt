package net.bobinski.edocalculator.domain.edo

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class EdoValue(
    @Contextual val totalValue: BigDecimal,
    @Contextual val totalAccruedInterest: BigDecimal,
    val periods: List<EdoPeriodBreakdown>
)