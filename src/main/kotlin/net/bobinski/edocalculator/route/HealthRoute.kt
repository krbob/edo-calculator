package net.bobinski.edocalculator.route

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.healthRoute() {
    get("/healthz") {
        call.respondText("ok", ContentType.Text.Plain)
    }
}
