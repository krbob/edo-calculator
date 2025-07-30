package net.bobinski.edocalculator.client


import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.cache.*
import io.ktor.client.plugins.cache.storage.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.serialization.kotlinx.json.*
import java.nio.file.Files
import java.nio.file.Paths

val AppHttpClient = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(AppJson)
    }
    install(HttpCache) {
        val cacheFile = Files.createDirectories(Paths.get("/tmp/cache")).toFile()
        publicStorage(FileStorage(cacheFile))
    }
    install(Logging) {
        level = LogLevel.INFO
    }
}