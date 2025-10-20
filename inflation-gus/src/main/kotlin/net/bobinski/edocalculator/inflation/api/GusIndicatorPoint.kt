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
)