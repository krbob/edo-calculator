package net.bobinski.edocalculator.route

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.routing.*

fun Route.fallbackErrorRoute() {
    route("/{...}") {
        handle {
            if (call.request.path() in REGISTERED_GET_PATHS) {
                call.respondError(
                    status = HttpStatusCode.MethodNotAllowed,
                    message = "Method not allowed.",
                    errorCode = ApiErrorCode.METHOD_NOT_ALLOWED
                )
            } else {
                call.respondError(
                    status = HttpStatusCode.NotFound,
                    message = "Route not found.",
                    errorCode = ApiErrorCode.ROUTE_NOT_FOUND
                )
            }
        }
    }
}

internal val REGISTERED_GET_PATHS = setOf(
    "/healthz",
    "/readyz",
    METRICS_PATH,
    "/edo/value",
    "/edo/value/at",
    "/edo/history",
    "/inflation/since",
    "/inflation/between",
    "/inflation/monthly",
    "/v1/edo/value",
    "/v1/edo/value/at",
    "/v1/edo/history",
    "/v1/inflation/since",
    "/v1/inflation/between",
    "/v1/inflation/monthly"
)
