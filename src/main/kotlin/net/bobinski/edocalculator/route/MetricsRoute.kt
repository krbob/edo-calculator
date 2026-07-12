package net.bobinski.edocalculator.route

import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry

const val METRICS_PATH = "/metrics"

fun Route.metricsRoute(registry: PrometheusMeterRegistry) {
    get(METRICS_PATH) {
        call.respondText(
            text = registry.scrape(),
            contentType = PROMETHEUS_CONTENT_TYPE
        )
    }
}

private val PROMETHEUS_CONTENT_TYPE = ContentType.parse("text/plain; version=0.0.4; charset=utf-8")
