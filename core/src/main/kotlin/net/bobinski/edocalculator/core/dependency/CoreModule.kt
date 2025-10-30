package net.bobinski.edocalculator.core.dependency

import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.core.serialization.AppJsonFactory
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.core.time.SystemCurrentTimeProvider
import org.koin.dsl.module

val CoreModule = module {
    single<Json> { AppJsonFactory.create() }
    single<CurrentTimeProvider> { SystemCurrentTimeProvider() }
}

