package net.bobinski.edocalculator.client

import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

class RetryTooManyRequests(
    private val times: Int = 3,
    private val baseDelay: Duration = 100.milliseconds
) {

    suspend fun <T> execute(block: suspend () -> T): T {
        var last: Throwable? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: ClientRequestException) {
                if (e.response.status in setOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
                    delay(baseDelay * 2.0.pow(attempt))
                    last = e
                } else {
                    throw e
                }
            }
        }

        throw last ?: IllegalStateException("Retry failed after $times attempts")
    }
}