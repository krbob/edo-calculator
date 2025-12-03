package net.bobinski.edocalculator.route

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.YearMonth
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import net.bobinski.edocalculator.core.time.toIsoString
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import org.koin.ktor.ext.inject
import java.math.BigDecimal
import java.nio.channels.UnresolvedAddressException

fun Route.inflationRoute() {
    val calculateCumulativeInflationUseCase: CalculateCumulativeInflationUseCase by inject()

    get("/inflation/since") {
        val month = call.request.queryParameters["month"]?.toIntOrNull()
        val year = call.request.queryParameters["year"]?.toIntOrNull()

        if (month == null || year == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "Query parameters 'month' and 'year' must be integers."
            )
            return@get
        }

        val start = try {
            YearMonth(year, month)
        } catch (_: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, "Invalid month or year value.")
            return@get
        }

        val result = try {
            calculateCumulativeInflationUseCase(start)
        } catch (e: UnresolvedAddressException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "Unable to reach CPI provider.")
            return@get
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
            InflationResponse(
                from = result.from.toIsoString(),
                until = result.untilExclusive.toIsoString(),
                multiplier = result.multiplier
            )
        )
    }

    get("/inflation/between") {
        val startMonth = call.request.queryParameters["startMonth"]?.toIntOrNull()
        val startYear = call.request.queryParameters["startYear"]?.toIntOrNull()
        val endMonth = call.request.queryParameters["endMonth"]?.toIntOrNull()
        val endYear = call.request.queryParameters["endYear"]?.toIntOrNull()

        if (startMonth == null || startYear == null || endMonth == null || endYear == null) {
            call.respondError(
                HttpStatusCode.BadRequest,
                "Query parameters 'startMonth', 'startYear', 'endMonth', and 'endYear' must be integers."
            )
            return@get
        }

        val start = try {
            YearMonth(startYear, startMonth)
        } catch (_: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, "Invalid start month or year value.")
            return@get
        }

        val endExclusive = try {
            YearMonth(endYear, endMonth)
        } catch (_: IllegalArgumentException) {
            call.respondError(HttpStatusCode.BadRequest, "Invalid end month or year value.")
            return@get
        }

        val result = try {
            calculateCumulativeInflationUseCase(start, endExclusive)
        } catch (e: UnresolvedAddressException) {
            call.respondError(HttpStatusCode.ServiceUnavailable, "Unable to reach CPI provider.")
            return@get
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
            InflationResponse(
                from = result.from.toIsoString(),
                until = result.untilExclusive.toIsoString(),
                multiplier = result.multiplier
            )
        )
    }
}

@Serializable
data class InflationResponse(
    val from: String,
    val until: String,
    @Contextual val multiplier: BigDecimal
)
