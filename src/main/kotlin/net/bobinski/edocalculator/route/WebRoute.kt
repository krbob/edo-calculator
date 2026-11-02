package net.bobinski.edocalculator.route

import io.ktor.server.application.call
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondResource
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.webRoute() {
    get("/") {
        call.respondResource("web/index.html")
    }
    staticResources("/ui", "web")
}
