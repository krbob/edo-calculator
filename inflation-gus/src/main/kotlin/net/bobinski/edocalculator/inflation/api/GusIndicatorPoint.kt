package net.bobinski.edocalculator.inflation.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
internal data class GusIndicatorPoint(
    @SerialName("id-daty") val year: Int,
    @SerialName("id-okres") val periodId: Int,
    @Contextual @SerialName("wartosc") val value: BigDecimal
) {
    fun calendarMonthNumber(): Int? =
        (periodId - GUS_PERIOD_JANUARY + 1).takeIf { it in 1..12 }
}

internal const val GUS_PERIOD_JANUARY = 247
internal const val GUS_PERIOD_DECEMBER = 258
internal val GUS_MONTHLY_PERIOD_IDS: IntRange = GUS_PERIOD_JANUARY..GUS_PERIOD_DECEMBER
