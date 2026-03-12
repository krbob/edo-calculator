package net.bobinski.edocalculator.client

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.cache.storage.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Paths

internal class AppHttpClient {

    companion object {
        fun create(json: Json) = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 10_000
                socketTimeoutMillis = 10_000
            }
            defaultRequest {
                accept(ContentType.Application.Json)
            }
            install(HttpCache) {
                val cacheFile = Files.createDirectories(Paths.get("/tmp", "edo-calculator", "http-cache")).toFile()
                publicStorage(FileStorage(cacheFile))
            }
            install(Logging) {
                level = LogLevel.INFO
                format = LoggingFormat.OkHttp
            }
        }
    }
}
