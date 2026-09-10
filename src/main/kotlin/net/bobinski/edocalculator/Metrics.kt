package net.bobinski.edocalculator

import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import net.bobinski.edocalculator.route.METRICS_PATH
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.ktor.ext.getKoin
import java.time.Duration

val MetricsModule = module {
    single<PrometheusMeterRegistry>(createdAtStart = true) {
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT).apply { configureLowCardinalityHttpTags() }
    } onClose { registry ->
        registry?.close()
    }
    single<MeterRegistry> { get<PrometheusMeterRegistry>() }
}

fun Application.configureMetrics() {
    val prometheusRegistry = getKoin().get<PrometheusMeterRegistry>()

    install(MicrometerMetrics) {
        registry = prometheusRegistry
        metricName = HTTP_SERVER_METRIC
        distinctNotRegisteredRoutes = false
        distributionStatisticConfig = DistributionStatisticConfig.builder()
            .percentilesHistogram(true)
            .maximumExpectedValue(Duration.ofSeconds(10).toNanos().toDouble())
            .serviceLevelObjectives(
                *HTTP_SLO_MILLISECONDS
                    .map { milliseconds -> Duration.ofMillis(milliseconds).toNanos().toDouble() }
                    .toDoubleArray()
            )
            .build()
    }

    // Keep a zero baseline before the first error without preallocating every
    // route/method/status histogram. Detailed timers remain available for diagnosis.
    val responses = HTTP_STATUS_CLASSES.associateWith { statusClass ->
        prometheusRegistry.counter("edo.http.responses", "status_class", statusClass)
    }
    install(createApplicationPlugin("HttpResponseCounters") {
        on(ResponseSent) { call ->
            if (call.request.path() !in OPERATIONAL_PATHS && !call.attributes.contains(RESPONSE_COUNTED)) {
                call.attributes.put(RESPONSE_COUNTED, true)
                val status = call.response.status()?.value ?: 500
                responses.getValue(statusClass(status)).increment()
            }
        }
    })
}

internal fun MeterRegistry.configureLowCardinalityHttpTags() {
    config()
        .meterFilter(MeterFilter.ignoreTags("address", "throwable"))
        .meterFilter(
            MeterFilter.replaceTagValues("method", { method ->
                method.takeIf(ALLOWED_HTTP_METHODS::contains) ?: "OTHER"
            })
        )
        .meterFilter(
            MeterFilter.deny { id ->
                id.name == HTTP_SERVER_METRIC && id.getTag("route") == METRICS_PATH
            }
        )
}

private const val HTTP_SERVER_METRIC = "edo.http.server.requests"
private val HTTP_SLO_MILLISECONDS = listOf(50L, 100L, 250L, 500L, 1_000L, 2_500L, 5_000L, 8_000L)
private val ALLOWED_HTTP_METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
private val OPERATIONAL_PATHS = setOf("/metrics", "/healthz", "/readyz")
private val RESPONSE_COUNTED = AttributeKey<Boolean>("http-response-counted")
private val HTTP_STATUS_CLASSES = listOf("1xx", "2xx", "3xx", "4xx", "429", "5xx", "other")

private fun statusClass(status: Int): String = when (status) {
    429 -> "429"
    in 100..599 -> "${status / 100}xx"
    else -> "other"
}
