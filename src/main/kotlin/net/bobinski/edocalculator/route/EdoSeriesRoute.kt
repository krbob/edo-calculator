package net.bobinski.edocalculator.route

import io.ktor.server.application.call
import io.ktor.server.response.respondResource
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.edoSeriesRoute() {
    get("/edo/series") {
        call.respondResource("web/series.json")
    }
}
