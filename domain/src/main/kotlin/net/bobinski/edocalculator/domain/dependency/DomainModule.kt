package net.bobinski.edocalculator.domain.dependency

import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import org.koin.dsl.module

val DomainModule = module {
    single { CalculateCumulativeInflationUseCase(inflationProvider = get(), currentTimeProvider = get()) }
    single { CalculateEdoValueUseCase(inflationProvider = get(), currentTimeProvider = get()) }
}