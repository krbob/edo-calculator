package net.bobinski.edocalculator.inflation.dependency

import net.bobinski.edocalculator.domain.inflation.InflationProvider
import net.bobinski.edocalculator.inflation.GusInflationProvider
import net.bobinski.edocalculator.inflation.api.CachingGusApi
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusApiImpl
import net.bobinski.edocalculator.inflation.api.GusMetrics
import net.bobinski.edocalculator.inflation.api.MicrometerGusMetrics
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val GusInflationModule = module {
    single<GusMetrics> { MicrometerGusMetrics(registry = get()) }
    single<GusApi>(named("raw")) {
        GusApiImpl(client = get(), metrics = get(), currentTimeProvider = get())
    }
    single<GusApi>(createdAtStart = true) {
        CachingGusApi(
            delegate = get(named("raw")),
            currentTimeProvider = get(),
            metrics = get(),
            prefetchOnInit = false
        )
    }
    single { GusInflationProvider(api = get()) } bind InflationProvider::class
}
