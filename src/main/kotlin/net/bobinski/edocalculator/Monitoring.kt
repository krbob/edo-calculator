package net.bobinski.edocalculator

import io.ktor.http.HttpHeaders
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.path
import net.bobinski.edocalculator.route.METRICS_PATH
import org.slf4j.event.*
import java.util.UUID

fun Application.configureMonitoring() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { requestId -> REQUEST_ID_PATTERN.matches(requestId) }
    }

    install(CallLogging) {
        level = Level.INFO
        callIdMdc("requestId")
        filter { call -> call.request.path() !in QUIET_PATHS }
    }
}

private val REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
private val QUIET_PATHS = setOf("/healthz", "/readyz", METRICS_PATH)
