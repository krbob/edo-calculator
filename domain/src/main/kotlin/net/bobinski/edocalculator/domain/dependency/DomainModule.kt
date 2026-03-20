package net.bobinski.edocalculator.domain.dependency

import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoHistoryUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateMonthlyInflationSeriesUseCase
import org.koin.dsl.module

val DomainModule = module {
    single { CalculateCumulativeInflationUseCase(inflationProvider = get(), currentTimeProvider = get()) }
    single { CalculateMonthlyInflationSeriesUseCase(inflationProvider = get(), currentTimeProvider = get()) }
    single { CalculateEdoValueUseCase(inflationProvider = get(), currentTimeProvider = get()) }
    single { CalculateEdoHistoryUseCase(calculateEdoValueUseCase = get(), currentTimeProvider = get()) }
}
