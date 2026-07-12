package net.bobinski.edocalculator.route

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoute() {
    get("/healthz") {
        call.respondText("ok", ContentType.Text.Plain)
    }
}

fun Route.readinessRoute(checkDependencies: () -> Unit) {
    get("/readyz") {
        runCatching(checkDependencies).fold(
            onSuccess = {
                call.respondText("ready", ContentType.Text.Plain)
            },
            onFailure = { cause ->
                call.application.log.error("Readiness check failed: {}", cause.message)
                call.respondText(
                    text = "not ready",
                    contentType = ContentType.Text.Plain,
                    status = HttpStatusCode.ServiceUnavailable
                )
            }
        )
    }
}
