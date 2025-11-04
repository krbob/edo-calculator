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
        val firstPeriodRate = call.request.queryParameters["firstPeriodRate"]?.toBigDecimalOrNull()
        val margin = call.request.queryParameters["margin"]?.toBigDecimalOrNull()
        val principal = call.request.queryParameters["principal"]?.toBigDecimalOrNull() ?: BigDecimal(100)

        if (firstPeriodRate == null || margin == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "Query parameters 'firstPeriodRate' and 'margin' must be decimals."
            )
            return@get
        }

        val result = try {
            calculateEdoValueUseCase(
                purchaseDate = purchaseDate,
                firstPeriodRate = firstPeriodRate,
                margin = margin,
                principal = principal
            )
        } catch (e: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid request parameters.")
            return@get
        } catch (e: MissingCpiDataException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, e.message ?: "Missing CPI data.")
            return@get
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.InternalServerError, e.message ?: "Unexpected error occurred.")
            return@get
        }

        call.respond(
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

private suspend fun ApplicationCall.parsePurchaseDate(): LocalDate? {
    val year = request.queryParameters["purchaseYear"]?.toIntOrNull()
    val month = request.queryParameters["purchaseMonth"]?.toIntOrNull()
    val day = request.queryParameters["purchaseDay"]?.toIntOrNull()

    if (year == null || month == null || day == null) {
        respondError(
            HttpStatusCode.BadRequest,
            "Query parameters 'purchaseYear' and 'purchaseMonth' and 'purchaseDay' must be integers."
        )
        return null
    }

    return try {
        LocalDate(year = year, month = month, day = day)
    } catch (_: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, "Invalid day, month or year value.")
        null
    }
}
