package net.bobinski.edocalculator

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import net.bobinski.edocalculator.route.cumulativeInflationRoute

fun Application.configureRouting() {
    routing {
        cumulativeInflationRoute()
    }
}
