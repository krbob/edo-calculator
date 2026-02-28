package net.bobinski.edocalculator.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.datetime.YearMonth
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import org.koin.ktor.ext.inject
import java.math.BigDecimal

fun Route.inflationRoute() {
    val calculateCumulativeInflationUseCase: CalculateCumulativeInflationUseCase by inject()

    get("/inflation/since") {
        val start = call.parseYearMonth(
            yearParam = "year",
            monthParam = "month",
            missingMessage = "Query parameters 'month' and 'year' must be integers.",
            invalidMessage = "Invalid month or year value."
        ) ?: return@get

        val result = call.handleUseCaseCall {
            calculateCumulativeInflationUseCase(start)
        } ?: return@get

        call.respond(
            InflationResponse(
                from = result.from.toString(),
                until = result.untilExclusive.toString(),
                multiplier = result.multiplier
            )
        )
    }

    get("/inflation/between") {
        val start = call.parseYearMonth(
            yearParam = "startYear",
            monthParam = "startMonth",
            missingMessage = "Query parameters 'startMonth', 'startYear', 'endMonth', and 'endYear' must be integers.",
            invalidMessage = "Invalid start month or year value."
        ) ?: return@get

        val endExclusive = call.parseYearMonth(
            yearParam = "endYear",
            monthParam = "endMonth",
            missingMessage = "Query parameters 'startMonth', 'startYear', 'endMonth', and 'endYear' must be integers.",
            invalidMessage = "Invalid end month or year value."
        ) ?: return@get

        val result = call.handleUseCaseCall {
            calculateCumulativeInflationUseCase(start, endExclusive)
        } ?: return@get

        call.respond(
            InflationResponse(
                from = result.from.toString(),
                until = result.untilExclusive.toString(),
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

private suspend fun ApplicationCall.parseYearMonth(
    yearParam: String,
    monthParam: String,
    missingMessage: String,
    invalidMessage: String
): YearMonth? {
    val year = request.queryParameters[yearParam]?.toIntOrNull()
    val month = request.queryParameters[monthParam]?.toIntOrNull()

    if (year == null || month == null) {
        respondError(HttpStatusCode.BadRequest, missingMessage)
        return null
    }

    return try {
        YearMonth(year, month)
    } catch (_: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, invalidMessage)
        null
    }
}
