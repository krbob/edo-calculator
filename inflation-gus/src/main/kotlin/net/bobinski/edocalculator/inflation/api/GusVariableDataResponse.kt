package net.bobinski.edocalculator.inflation.api

import kotlinx.serialization.Contextual
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
internal data class GusVariableDataResponse(
    val data: List<GusVariableDataPoint>
)

@Serializable
internal data class GusVariableDataPoint(
    @SerialName("id-daty") val year: Int,
    @SerialName("id-okres") val periodId: Int,
    @SerialName("id-pozycja-2") val coicop2018Position: Int,
    @SerialName("id-pozycja-3") val householdPosition: Int,
    @SerialName("id-sposob-prezentacji-miara") val measureType: Int,
    @Contextual @SerialName("wartosc") val value: BigDecimal
)
