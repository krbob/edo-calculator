package net.bobinski.edocalculator

import io.ktor.server.application.*
import io.ktor.server.routing.*
import net.bobinski.edocalculator.route.edoRoute
import net.bobinski.edocalculator.route.healthRoute
import net.bobinski.edocalculator.route.inflationRoute

fun Application.configureRouting() {
    routing {
        healthRoute()
        inflationRoute()
        edoRoute()
    }
}
