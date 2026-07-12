package net.bobinski.edocalculator.client.utils

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryTooManyRequestsTest {

    @Test
    fun `should succeed on first try`() = runTest {
        val retry = RetryTooManyRequests(times = 1, baseDelay = 10.milliseconds)
        val result = retry.execute { "success" }
        assertEquals("success", result)
    }

    @Test
    fun `should retry on 429 and then succeed`() = runTest {
        var attempt = 0
        val retry = RetryTooManyRequests(times = 3, baseDelay = 1.milliseconds)

        val result = retry.execute {
            if (attempt++ < 1) throw ClientRequestException(mockResponse(HttpStatusCode.TooManyRequests), "test")
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempt)
    }

    @Test
    fun `should retry on 503 and then throw after max attempts`() = runTest {
        var attempt = 0
        val retry = RetryTooManyRequests(times = 3, baseDelay = 1.milliseconds)

        val exception = assertThrows(ServerResponseException::class.java) {
            runBlocking {
                retry.execute {
                    attempt++
                    throw ServerResponseException(mockResponse(HttpStatusCode.ServiceUnavailable), "test")
                }
            }
        }

        assertEquals(3, attempt)
        assertEquals(HttpStatusCode.ServiceUnavailable, exception.response.status)
    }

    @Test
    fun `should retry on 500 and then succeed`() = runTest {
        var attempt = 0
        val retry = RetryTooManyRequests(times = 3, baseDelay = 1.milliseconds)

        val result = retry.execute {
            if (attempt++ < 1) {
                throw ServerResponseException(mockResponse(HttpStatusCode.InternalServerError), "test")
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempt)
    }

    @Test
    fun `should not retry on other status codes`() = runTest {
        var attempt = 0
        val retry = RetryTooManyRequests(times = 3, baseDelay = 1.milliseconds)

        val exception = assertThrows(ClientRequestException::class.java) {
            runBlocking {
                retry.execute {
                    attempt++
                    throw ClientRequestException(mockResponse(HttpStatusCode.BadRequest), "test")
                }
            }
        }

        assertEquals(1, attempt)
        assertEquals(HttpStatusCode.BadRequest, exception.response.status)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `caps Retry-After delay and reports the scheduled retry reason`() = runTest {
        var attempt = 0
        val reasons = mutableListOf<RetryReason>()
        val retry = RetryTooManyRequests(
            times = 2,
            baseDelay = 100.milliseconds,
            maxDelay = 1.seconds,
            onRetry = reasons::add
        )

        val result = retry.execute {
            if (attempt++ == 0) {
                throw ClientRequestException(
                    mockResponse(HttpStatusCode.TooManyRequests, retryAfter = "60"),
                    "test"
                )
            }
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(1_000, testScheduler.currentTime)
        assertEquals(listOf(RetryReason.RATE_LIMITED), reasons)
    }

    private fun mockResponse(status: HttpStatusCode, retryAfter: String? = null): HttpResponse {
        val response = mockk<HttpResponse>(relaxed = true)
        every { response.status } returns status
        if (retryAfter != null) {
            every { response.headers } returns headersOf(HttpHeaders.RetryAfter, retryAfter)
        }
        return response
    }
}
