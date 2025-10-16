package net.bobinski.edocalculator.inflation.dependency

import net.bobinski.edocalculator.domain.InflationProvider
import net.bobinski.edocalculator.inflation.GusInflationProvider
import org.koin.core.module.dsl.factoryOf
import org.koin.dsl.bind
import org.koin.dsl.module

val GusInflationModule = module {
    factoryOf(::GusInflationProvider) bind InflationProvider::class
}