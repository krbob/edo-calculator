package net.bobinski.edocalculator.client.utils

import io.ktor.client.plugins.ResponseException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryTooManyRequests(
    private val times: Int = 3,
    private val baseDelay: Duration = 100.milliseconds
) {

    init {
        require(times > 0) { "times must be greater than zero" }
        require(!baseDelay.isNegative()) { "baseDelay cannot be negative" }
    }

    suspend fun <T> execute(block: suspend () -> T): T {
        var last: Throwable? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: ResponseException) {
                if (e.shouldRetry()) {
                    last = e
                    if (attempt < times - 1) {
                        delay(e.calculateDelay(attempt))
                    }
                } else {
                    throw e
                }
            }
        }

        throw last ?: IllegalStateException("Retry failed after $times attempts")
    }

    private fun ResponseException.shouldRetry(): Boolean {
        return response.status == HttpStatusCode.TooManyRequests ||
            response.status.value in 500..599
    }

    private fun ResponseException.calculateDelay(attempt: Int): Duration {
        val retryAfter = response.headers[HttpHeaders.RetryAfter]
            ?.toLongOrNull()
            ?.seconds

        return retryAfter ?: baseDelay * 2.0.pow(attempt)
    }
}
