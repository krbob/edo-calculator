package net.bobinski.edocalculator.inflation.dependency

import net.bobinski.edocalculator.domain.inflation.InflationProvider
import net.bobinski.edocalculator.inflation.GusInflationProvider
import net.bobinski.edocalculator.inflation.api.CachingGusApi
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusApiImpl
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
val GusInflationModule = module {
    single<GusApi>(named("raw")) { GusApiImpl(client = get(), currentTimeProvider = get()) }
    single<GusApi> { CachingGusApi(delegate = get(named("raw")), currentTimeProvider = get()) }
    single { GusInflationProvider(api = get()) } bind InflationProvider::class
}