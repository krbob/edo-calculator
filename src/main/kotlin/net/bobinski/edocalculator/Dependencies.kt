package net.bobinski.edocalculator

import io.ktor.server.application.*
import net.bobinski.edocalculator.client.dependency.ClientModule
import net.bobinski.edocalculator.core.dependency.CoreModule
import net.bobinski.edocalculator.domain.dependency.DomainModule
import net.bobinski.edocalculator.inflation.dependency.GusInflationModule
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun Application.configureDependencies() {
    install(Koin) {
        slf4jLogger()
        modules(CoreModule, ClientModule, DomainModule, GusInflationModule)
    }
}