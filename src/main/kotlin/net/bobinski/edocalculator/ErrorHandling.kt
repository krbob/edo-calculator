package net.bobinski.edocalculator

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import kotlinx.coroutines.CancellationException
import net.bobinski.edocalculator.route.ApiErrorCode
import net.bobinski.edocalculator.route.respondError

fun Application.configureErrorHandling() {
    install(StatusPages) {
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondError(
                status = status,
                message = "Route not found.",
                errorCode = ApiErrorCode.ROUTE_NOT_FOUND
            )
        }
        status(HttpStatusCode.MethodNotAllowed) { call, status ->
            call.respondError(
                status = status,
                message = "Method not allowed.",
                errorCode = ApiErrorCode.METHOD_NOT_ALLOWED
            )
        }
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            call.application.log.error("Unhandled application error", cause)
            call.respondError(
                status = HttpStatusCode.InternalServerError,
                message = "Unexpected error occurred.",
                errorCode = ApiErrorCode.INTERNAL_ERROR
            )
        }
    }
}
