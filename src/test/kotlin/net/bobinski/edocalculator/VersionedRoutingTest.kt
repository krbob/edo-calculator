package net.bobinski.edocalculator

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `versioned and legacy EDO routes preserve analytical principal semantics`() = testApplication {
        application { module() }

        suspend fun valueAt(path: String) = client.get(path) {
            parameter("purchaseYear", "2025")
            parameter("purchaseMonth", "1")
            parameter("purchaseDay", "1")
            parameter("asOfYear", "2025")
            parameter("asOfMonth", "1")
            parameter("asOfDay", "1")
            parameter("firstPeriodRate", "7.25")
            parameter("margin", "1.25")
            parameter("principal", "123.45")
        }

        val versioned = valueAt("/v1/edo/value/at")
        val legacy = valueAt("/edo/value/at")
        val body = Json.parseToJsonElement(versioned.bodyAsText()).jsonObject

        assertEquals(HttpStatusCode.OK, versioned.status)
        assertEquals(versioned.status, legacy.status)
        assertEquals(versioned.bodyAsText(), legacy.bodyAsText())
        assertEquals("123.45", body.getValue("principal").jsonPrimitive.content)
        assertEquals(
            "123.45",
            body.getValue("edoValue").jsonObject.getValue("totalValue").jsonPrimitive.content
        )
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
