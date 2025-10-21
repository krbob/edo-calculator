package net.bobinski.edocalculator.client.utils

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

    init {
        require(times > 0) { "times must be greater than zero" }
        require(!baseDelay.isNegative()) { "baseDelay cannot be negative" }
    }

    suspend fun <T> execute(block: suspend () -> T): T {
        var last: Throwable? = null

        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: ClientRequestException) {
                if (e.shouldRetry()) {
                    delay(calculateDelay(attempt))
                    last = e
                } else {
                    throw e
                }
            }
        }

        throw last ?: IllegalStateException("Retry failed after $times attempts")
    }

    private fun ClientRequestException.shouldRetry(): Boolean {
        return response.status in RETRIABLE_STATUS_CODES
    }

    private fun calculateDelay(attempt: Int): Duration {
        return baseDelay * 2.0.pow(attempt)
    }

    private companion object {
        private val RETRIABLE_STATUS_CODES = setOf(
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.ServiceUnavailable
        )
    }
}