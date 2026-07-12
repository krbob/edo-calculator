package net.bobinski.edocalculator

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.route.ApiErrorCode
import net.bobinski.edocalculator.route.ApiErrorResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class VersionedRoutingTest {

    @Test
    fun `versioned EDO route keeps the legacy contract available`() = testApplication {
        application { module() }

        val versioned = client.get("/v1/edo/value")
        val legacy = client.get("/edo/value")
        val versionedError = Json.decodeFromString<ApiErrorResponse>(versioned.bodyAsText())
        val legacyError = Json.decodeFromString<ApiErrorResponse>(legacy.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, versioned.status)
        assertEquals(HttpStatusCode.BadRequest, legacy.status)
        assertEquals(ApiErrorCode.INVALID_REQUEST, versionedError.errorCode)
        assertEquals(legacyError.error, versionedError.error)
        assertEquals(legacyError.retryable, versionedError.retryable)
        assertNotNull(versionedError.requestId)
        assertNotNull(legacyError.requestId)
    }

    @Test
    fun `versioned inflation routes are registered`() = testApplication {
        application { module() }

        val response = client.get("/v1/inflation/monthly")
        val error = Json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ApiErrorCode.INVALID_REQUEST, error.errorCode)
    }
}
