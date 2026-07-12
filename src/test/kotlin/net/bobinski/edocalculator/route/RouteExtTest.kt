package net.bobinski.edocalculator.route

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds

class RouteExtTest {

    @Test
    fun `request cancellation is propagated`() = runTest {
        val call = mockk<ApplicationCall>()
        val cancellation = CancellationException("client disconnected")

        try {
            call.handleUseCaseCall<Unit> { throw cancellation }
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
            return@runTest
        }

        fail("Expected request cancellation to be propagated")
    }

    @Test
    fun `service budget timeout becomes retryable provider unavailable response`() = testApplication {
        application {
            module()
            routing {
                get("/test/operation-timeout") {
                    call.handleUseCaseCall<Unit>(operationTimeout = 1.milliseconds) {
                        awaitCancellation()
                    }
                }
            }
        }

        val response = client.get("/test/operation-timeout")
        val error = Json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals(ApiErrorCode.CPI_PROVIDER_UNAVAILABLE, error.errorCode)
        assertTrue(error.retryable)
        assertEquals("CPI operation exceeded the 8-second service budget.", error.error)
    }
}
