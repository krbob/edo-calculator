package net.bobinski.edocalculator.client.dependency

import io.ktor.client.*
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.client.AppHttpClient
import org.koin.dsl.module
import org.koin.dsl.onClose

val ClientModule = module {
    single<HttpClient>(createdAtStart = true) {
        AppHttpClient.create(get<Json>())
    } onClose { client ->
        client?.close()
    }
}