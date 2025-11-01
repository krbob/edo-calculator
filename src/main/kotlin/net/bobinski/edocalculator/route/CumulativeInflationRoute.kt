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

fun Route.cumulativeInflationRoute() {
    val calculateCumulativeInflationUseCase: CalculateCumulativeInflationUseCase by inject()

    get("/inflation/since") {
        val month = call.request.queryParameters["month"]?.toIntOrNull()
        val year = call.request.queryParameters["year"]?.toIntOrNull()

        if (month == null || year == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Query parameters 'month' and 'year' must be integers.")
            )
            return@get
        }

        val start = try {
            YearMonth(year, month)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid month or year value."))
            return@get
        }

        val result = try {
            calculateCumulativeInflationUseCase(start)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            return@get
        } catch (e: MissingCpiDataException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
            return@get
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            return@get
        }

        call.respond(
            CumulativeInflationResponse(
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
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "Query parameters 'startMonth', 'startYear', 'endMonth', and 'endYear' must be integers.")
            )
            return@get
        }

        val start = try {
            YearMonth(startYear, startMonth)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid start month or year value."))
            return@get
        }

        val endExclusive = try {
            YearMonth(endYear, endMonth)
        } catch (_: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid end month or year value."))
            return@get
        }

        val result = try {
            calculateCumulativeInflationUseCase(start, endExclusive)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to e.message))
            return@get
        } catch (e: MissingCpiDataException) {
            call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to e.message))
            return@get
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to e.message))
            return@get
        }

        call.respond(
            CumulativeInflationResponse(
                from = result.from.toIsoString(),
                until = result.untilExclusive.toIsoString(),
                multiplier = result.multiplier
            )
        )
    }
}

@Serializable
data class CumulativeInflationResponse(
    val from: String,
    val until: String,
    @Contextual val multiplier: BigDecimal
)
