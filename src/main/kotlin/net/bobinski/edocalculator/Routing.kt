package net.bobinski.edocalculator

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoHistoryUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateMonthlyInflationSeriesUseCase
import net.bobinski.edocalculator.route.edoRoute
import net.bobinski.edocalculator.route.fallbackErrorRoute
import net.bobinski.edocalculator.route.healthRoute
import net.bobinski.edocalculator.route.inflationRoute
import net.bobinski.edocalculator.route.metricsRoute
import net.bobinski.edocalculator.route.readinessRoute
import org.koin.ktor.ext.getKoin

fun Application.configureRouting() {
    val koin = getKoin()
    val readinessCheck = {
        koin.get<Json>()
        koin.get<InflationProvider>()
        koin.get<CalculateCumulativeInflationUseCase>()
        koin.get<CalculateMonthlyInflationSeriesUseCase>()
        koin.get<CalculateEdoValueUseCase>()
        koin.get<CalculateEdoHistoryUseCase>()
        koin.get<PrometheusMeterRegistry>()
        Unit
    }

    routing {
        healthRoute()
        readinessRoute(readinessCheck)
        metricsRoute(koin.get())
        inflationRoute()
        edoRoute()
        route("/v1") {
            inflationRoute()
            edoRoute()
        }
        fallbackErrorRoute()
    }
}
