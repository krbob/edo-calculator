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
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.usecase.CalculateEdoHistoryUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import org.koin.ktor.ext.inject
import java.math.BigDecimal

fun Route.edoRoute() {
    val calculateEdoValueUseCase: CalculateEdoValueUseCase by inject()
    val calculateEdoHistoryUseCase: CalculateEdoHistoryUseCase by inject()

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

    get("/edo/history") {
        val purchaseDate = call.parsePurchaseDate() ?: return@get
        val fromDate = when (
            val result = call.parseOptionalDate(
                yearParam = "fromYear",
                monthParam = "fromMonth",
                dayParam = "fromDay",
                missingMessage = "Query parameters 'fromYear' and 'fromMonth' and 'fromDay' must be integers when provided.",
                invalidMessage = "Invalid from day, month or year value."
            )
        ) {
            OptionalDateParseResult.Invalid -> return@get
            is OptionalDateParseResult.Parsed -> result.value
        }
        val toDate = when (
            val result = call.parseOptionalDate(
                yearParam = "toYear",
                monthParam = "toMonth",
                dayParam = "toDay",
                missingMessage = "Query parameters 'toYear' and 'toMonth' and 'toDay' must be integers when provided.",
                invalidMessage = "Invalid to day, month or year value."
            )
        ) {
            OptionalDateParseResult.Invalid -> return@get
            is OptionalDateParseResult.Parsed -> result.value
        }

        call.respondWithEdoHistory(
            purchaseDate = purchaseDate,
            from = fromDate,
            to = toDate,
            calculateEdoHistoryUseCase = calculateEdoHistoryUseCase
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
    val principal = parsePrincipal() ?: return

    if (firstPeriodRate == null || margin == null) {
        respondError(
            HttpStatusCode.BadRequest,
            "Query parameters 'firstPeriodRate' and 'margin' must be decimals."
        )
        return
    }

    val result = handleUseCaseCall {
        calculateEdoValueUseCase(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal,
            asOf = asOf
        )
    } ?: return

    respond(
        EdoResponse(
            purchaseDate = result.purchaseDate.toString(),
            asOf = result.asOf.toString(),
            firstPeriodRate = result.firstPeriodRate,
            margin = result.margin,
            principal = result.principal,
            edoValue = result.edoValue
        )
    )
}

private suspend fun ApplicationCall.parsePrincipal(): BigDecimal? {
    val rawPrincipal = request.queryParameters["principal"] ?: return BigDecimal(100)

    return rawPrincipal.toBigDecimalOrNull() ?: run {
        respondError(HttpStatusCode.BadRequest, "Query parameter 'principal' must be a decimal.")
        null
    }
}

private suspend fun ApplicationCall.respondWithEdoHistory(
    purchaseDate: LocalDate,
    from: LocalDate?,
    to: LocalDate?,
    calculateEdoHistoryUseCase: CalculateEdoHistoryUseCase
) {
    val firstPeriodRate = request.queryParameters["firstPeriodRate"]?.toBigDecimalOrNull()
    val margin = request.queryParameters["margin"]?.toBigDecimalOrNull()
    val principal = parsePrincipal() ?: return

    if (firstPeriodRate == null || margin == null) {
        respondError(
            HttpStatusCode.BadRequest,
            "Query parameters 'firstPeriodRate' and 'margin' must be decimals."
        )
        return
    }

    val result = handleUseCaseCall {
        calculateEdoHistoryUseCase(
            purchaseDate = purchaseDate,
            firstPeriodRate = firstPeriodRate,
            margin = margin,
            principal = principal,
            from = from,
            to = to
        )
    } ?: return

    respond(
        EdoHistoryResponse(
            purchaseDate = result.purchaseDate.toString(),
            from = result.from.toString(),
            until = result.until.toString(),
            firstPeriodRate = result.firstPeriodRate,
            margin = result.margin,
            principal = result.principal,
            points = result.points.map { point ->
                EdoHistoryPointResponse(
                    date = point.date.toString(),
                    totalValue = point.totalValue,
                    totalAccruedInterest = point.totalAccruedInterest
                )
            }
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

@Serializable
data class EdoHistoryResponse(
    val purchaseDate: String,
    val from: String,
    val until: String,
    @Contextual val firstPeriodRate: BigDecimal,
    @Contextual val margin: BigDecimal,
    @Contextual val principal: BigDecimal,
    val points: List<EdoHistoryPointResponse>
)

@Serializable
data class EdoHistoryPointResponse(
    val date: String,
    @Contextual val totalValue: BigDecimal,
    @Contextual val totalAccruedInterest: BigDecimal
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

private suspend fun ApplicationCall.parseOptionalDate(
    yearParam: String,
    monthParam: String,
    dayParam: String,
    missingMessage: String,
    invalidMessage: String
): OptionalDateParseResult {
    val rawYear = request.queryParameters[yearParam]
    val rawMonth = request.queryParameters[monthParam]
    val rawDay = request.queryParameters[dayParam]

    if (rawYear == null && rawMonth == null && rawDay == null) {
        return OptionalDateParseResult.Parsed(null)
    }

    return parseDate(
        yearParam = yearParam,
        monthParam = monthParam,
        dayParam = dayParam,
        missingMessage = missingMessage,
        invalidMessage = invalidMessage
    )?.let { OptionalDateParseResult.Parsed(it) } ?: OptionalDateParseResult.Invalid
}

private sealed interface OptionalDateParseResult {
    data object Invalid : OptionalDateParseResult
    data class Parsed(val value: LocalDate?) : OptionalDateParseResult
}

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
