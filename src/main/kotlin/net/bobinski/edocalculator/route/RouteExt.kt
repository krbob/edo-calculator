package net.bobinski.edocalculator.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import net.bobinski.edocalculator.domain.error.CpiProviderUnavailableException
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import org.slf4j.LoggerFactory
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@PublishedApi
internal val routeLogger = LoggerFactory.getLogger("net.bobinski.edocalculator.route")

suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
    errorCode: ApiErrorCode = ApiErrorCode.INVALID_REQUEST,
    retryable: Boolean = false
) {
    respond(
        status,
        ApiErrorResponse(
            error = message,
            errorCode = errorCode,
            retryable = retryable,
            requestId = callId
        )
    )
}

suspend fun <T> ApplicationCall.handleUseCaseCall(
    operationTimeout: Duration = EDO_OPERATION_TIMEOUT,
    block: suspend () -> T
): T? {
    return try {
        withTimeout(operationTimeout) { block() }
    } catch (_: TimeoutCancellationException) {
        respondError(
            status = HttpStatusCode.ServiceUnavailable,
            message = "CPI operation exceeded the 8-second service budget.",
            errorCode = ApiErrorCode.CPI_PROVIDER_UNAVAILABLE,
            retryable = true
        )
        null
    } catch (e: CancellationException) {
        throw (e.cause as? CancellationException ?: e)
    } catch (e: CpiProviderUnavailableException) {
        respondError(
            status = HttpStatusCode.ServiceUnavailable,
            message = e.message ?: "Unable to reach CPI provider.",
            errorCode = ApiErrorCode.CPI_PROVIDER_UNAVAILABLE,
            retryable = true
        )
        null
    } catch (e: UnresolvedAddressException) {
        respondError(
            status = HttpStatusCode.ServiceUnavailable,
            message = "Unable to reach CPI provider.",
            errorCode = ApiErrorCode.CPI_PROVIDER_UNAVAILABLE,
            retryable = true
        )
        null
    } catch (e: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid request parameters.")
        null
    } catch (e: MissingCpiDataException) {
        respondError(
            status = HttpStatusCode.ServiceUnavailable,
            message = e.message ?: "Missing CPI data.",
            errorCode = ApiErrorCode.CPI_DATA_UNAVAILABLE,
            retryable = true
        )
        null
    } catch (e: Exception) {
        routeLogger.error("Unexpected error", e)
        respondError(
            status = HttpStatusCode.InternalServerError,
            message = "Unexpected error occurred.",
            errorCode = ApiErrorCode.INTERNAL_ERROR
        )
        null
    }
}

internal val EDO_OPERATION_TIMEOUT: Duration = 8.seconds

@Serializable
data class ApiErrorResponse(
    val error: String,
    val errorCode: ApiErrorCode,
    val retryable: Boolean,
    val requestId: String?
)

@Serializable
enum class ApiErrorCode {
    INVALID_REQUEST,
    ROUTE_NOT_FOUND,
    METHOD_NOT_ALLOWED,
    CPI_PROVIDER_UNAVAILABLE,
    CPI_DATA_UNAVAILABLE,
    INTERNAL_ERROR
}
