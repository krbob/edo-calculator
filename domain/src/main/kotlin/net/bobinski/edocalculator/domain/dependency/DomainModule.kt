package net.bobinski.edocalculator.domain.dependency

import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import org.koin.dsl.module

val DomainModule = module {
    single { CalculateCumulativeInflationUseCase(inflationProvider = get(), currentTimeProvider = get()) }
}