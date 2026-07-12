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
