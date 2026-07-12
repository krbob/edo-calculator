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
                connectTimeoutMillis = GUS_CONNECT_TIMEOUT_MILLIS
                requestTimeoutMillis = GUS_REQUEST_TIMEOUT_MILLIS
                socketTimeoutMillis = GUS_SOCKET_TIMEOUT_MILLIS
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

internal const val GUS_CONNECT_TIMEOUT_MILLIS = 2_000L
internal const val GUS_REQUEST_TIMEOUT_MILLIS = 3_000L
internal const val GUS_SOCKET_TIMEOUT_MILLIS = 3_000L
