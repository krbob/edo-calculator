package net.bobinski.edocalculator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.route.ApiErrorCode
import net.bobinski.edocalculator.route.ApiErrorResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ErrorHandlingTest {

    @Test
    fun `unknown route uses the structured error contract`() = testApplication {
        application { module() }

        val response = client.get("/v1/does-not-exist") {
            header(HttpHeaders.XRequestId, "missing-route-123")
        }
        val error = Json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals(ApiErrorCode.ROUTE_NOT_FOUND, error.errorCode)
        assertEquals(false, error.retryable)
        assertEquals("missing-route-123", error.requestId)
    }

    @Test
    fun `unsupported method still returns a structured framework error`() = testApplication {
        application { module() }

        val response = client.post("/v1/edo/value")
        val error = Json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        assertEquals(ApiErrorCode.METHOD_NOT_ALLOWED, error.errorCode)
    }

    @Test
    fun `unhandled exception uses internal error without leaking details`() = testApplication {
        application {
            module()
            routing {
                get("/test/unhandled") { error("secret implementation detail") }
            }
        }

        val response = client.get("/test/unhandled") {
            header(HttpHeaders.XRequestId, "unhandled-123")
        }
        val body = response.bodyAsText()
        val error = Json.decodeFromString<ApiErrorResponse>(body)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(ApiErrorCode.INTERNAL_ERROR, error.errorCode)
        assertEquals(false, error.retryable)
        assertEquals("unhandled-123", error.requestId)
        assertFalse(body.contains("secret implementation detail"))
    }
}
