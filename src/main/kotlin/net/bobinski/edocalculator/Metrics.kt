package net.bobinski.edocalculator

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
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
        PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    } onClose { registry ->
        registry?.close()
    }
    single<MeterRegistry> { get<PrometheusMeterRegistry>() }
}

fun Application.configureMetrics() {
    val prometheusRegistry = getKoin().get<PrometheusMeterRegistry>()
    prometheusRegistry.configureLowCardinalityHttpTags()

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
