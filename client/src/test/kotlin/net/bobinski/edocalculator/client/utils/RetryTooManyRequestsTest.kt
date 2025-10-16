package net.bobinski.edocalculator.client.utils

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

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

        val exception = assertThrows(ClientRequestException::class.java) {
            runBlocking {
                retry.execute {
                    attempt++
                    throw ClientRequestException(mockResponse(HttpStatusCode.ServiceUnavailable), "test")
                }
            }
        }

        assertEquals(3, attempt)
        assertEquals(HttpStatusCode.ServiceUnavailable, exception.response.status)
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

    private fun mockResponse(status: HttpStatusCode): HttpResponse {
        val response = mockk<HttpResponse>(relaxed = true)
        every { response.status } returns status
        return response
    }
}