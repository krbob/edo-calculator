package net.bobinski.edocalculator

import io.ktor.server.application.*
import io.ktor.server.plugins.calllogging.*
import org.slf4j.event.*

fun Application.configureMonitoring() {
    install(CallLogging) {
        level = Level.INFO
    }
}
