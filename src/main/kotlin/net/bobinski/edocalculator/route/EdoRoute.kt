package net.bobinski.edocalculator.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import net.bobinski.edocalculator.core.time.toIsoString
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import org.koin.ktor.ext.inject
import java.math.BigDecimal

fun Route.edoRoute() {
    val calculateEdoValueUseCase: CalculateEdoValueUseCase by inject()

    get("/edo/value") {
        val purchaseDate = call.parsePurchaseDate() ?: return@get
        call.respondWithEdoValue(
            purchaseDate = purchaseDate,
            asOf = null,
            calculateEdoValueUseCase = calculateEdoValueUseCase
        )
    }

    get("/edo/value/at") {
        val purchaseDate = call.parsePurchaseDate() ?: return@get
        val asOfDate = call.parseAsOfDate() ?: return@get

        call.respondWithEdoValue(
            purchaseDate = purchaseDate,
            asOf = asOfDate,
            calculateEdoValueUseCase = calculateEdoValueUseCase
        )
    }
}

private suspend fun ApplicationCall.respondWithEdoValue(
    purchaseDate: LocalDate,
    asOf: LocalDate?,
    calculateEdoValueUseCase: CalculateEdoValueUseCase
) {
    val firstPeriodRate = request.queryParameters["firstPeriodRate"]?.toBigDecimalOrNull()
    val margin = request.queryParameters["margin"]?.toBigDecimalOrNull()
    val principal = request.queryParameters["principal"]?.toBigDecimalOrNull() ?: BigDecimal(100)

    if (firstPeriodRate == null || margin == null) {
        respondError(
            HttpStatusCode.BadRequest,
            "Query parameters 'firstPeriodRate' and 'margin' must be decimals."
        )
        return
    }

    val result = try {
        calculateEdoValueUseCase(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal,
            asOf = asOf
        )
    } catch (e: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid request parameters.")
        return
    } catch (e: MissingCpiDataException) {
        respondError(HttpStatusCode.ServiceUnavailable, e.message ?: "Missing CPI data.")
        return
    } catch (e: Exception) {
        respondError(HttpStatusCode.InternalServerError, e.message ?: "Unexpected error occurred.")
        return
    }

    respond(
        EdoResponse(
            purchaseDate = result.purchaseDate.toIsoString(),
            asOf = result.asOf.toIsoString(),
            firstPeriodRate = result.firstPeriodRate,
            margin = result.margin,
            principal = result.principal,
            edoValue = result.edoValue
        )
    )
}

@Serializable
data class EdoResponse(
    val purchaseDate: String,
    val asOf: String,
    @Contextual val firstPeriodRate: BigDecimal,
    @Contextual val margin: BigDecimal,
    @Contextual val principal: BigDecimal,
    val edoValue: EdoValue
)

private suspend fun ApplicationCall.parsePurchaseDate(): LocalDate? = parseDate(
    yearParam = "purchaseYear",
    monthParam = "purchaseMonth",
    dayParam = "purchaseDay",
    missingMessage = "Query parameters 'purchaseYear' and 'purchaseMonth' and 'purchaseDay' must be integers.",
    invalidMessage = "Invalid day, month or year value."
)

private suspend fun ApplicationCall.parseAsOfDate(): LocalDate? = parseDate(
    yearParam = "asOfYear",
    monthParam = "asOfMonth",
    dayParam = "asOfDay",
    missingMessage = "Query parameters 'asOfYear' and 'asOfMonth' and 'asOfDay' must be integers.",
    invalidMessage = "Invalid as-of day, month or year value."
)

private suspend fun ApplicationCall.parseDate(
    yearParam: String,
    monthParam: String,
    dayParam: String,
    missingMessage: String,
    invalidMessage: String
): LocalDate? {
    val year = request.queryParameters[yearParam]?.toIntOrNull()
    val month = request.queryParameters[monthParam]?.toIntOrNull()
    val day = request.queryParameters[dayParam]?.toIntOrNull()

    if (year == null || month == null || day == null) {
        respondError(HttpStatusCode.BadRequest, missingMessage)
        return null
    }

    return try {
        LocalDate(year = year, month = month, day = day)
    } catch (_: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, invalidMessage)
        null
    }
}
