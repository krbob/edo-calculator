package net.bobinski.edocalculator.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import net.bobinski.edocalculator.domain.error.CpiProviderUnavailableException
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import org.slf4j.LoggerFactory
import java.nio.channels.UnresolvedAddressException

@PublishedApi
internal val routeLogger = LoggerFactory.getLogger("net.bobinski.edocalculator.route")

suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
    respond(status, mapOf("error" to message))
}

suspend inline fun <T> ApplicationCall.handleUseCaseCall(block: suspend () -> T): T? {
    return try {
        block()
    } catch (e: CpiProviderUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, e.message ?: "Unable to reach CPI provider.")
        null
    } catch (e: UnresolvedAddressException) {
        respondError(HttpStatusCode.ServiceUnavailable, "Unable to reach CPI provider.")
        null
    } catch (e: IllegalArgumentException) {
        respondError(HttpStatusCode.BadRequest, e.message ?: "Invalid request parameters.")
        null
    } catch (e: MissingCpiDataException) {
        respondError(HttpStatusCode.ServiceUnavailable, e.message ?: "Missing CPI data.")
        null
    } catch (e: Exception) {
        routeLogger.error("Unexpected error", e)
        respondError(HttpStatusCode.InternalServerError, "Unexpected error occurred.")
        null
    }
}
