package net.bobinski.edocalculator.core.dependency

import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.core.serialization.AppJson
import org.koin.dsl.module

val CoreModule = module {
    single<Json> { AppJson }
}