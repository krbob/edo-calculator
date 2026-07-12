package net.bobinski.edocalculator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.bobinski.edocalculator.route.healthRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitoringTest {

    @Test
    fun `preserves a valid client request id`() = testApplication {
        application {
            configureMonitoring()
            routing { healthRoute() }
        }

        val response = client.get("/healthz") {
            header(HttpHeaders.XRequestId, "client-request_123")
        }

        assertEquals("client-request_123", response.headers[HttpHeaders.XRequestId])
    }

    @Test
    fun `replaces an unsafe client request id`() = testApplication {
        application {
            configureMonitoring()
            routing { healthRoute() }
        }

        val response = client.get("/healthz") {
            header(HttpHeaders.XRequestId, "request id with spaces")
        }
        val requestId = response.headers[HttpHeaders.XRequestId]

        assertNotEquals("request id with spaces", requestId)
        assertTrue(requestId?.matches(SAFE_REQUEST_ID_PATTERN) == true)
    }

    @Test
    fun `generates a request id when client omits it`() = testApplication {
        application {
            configureMonitoring()
            routing { healthRoute() }
        }

        val requestId = client.get("/healthz").headers[HttpHeaders.XRequestId]

        assertTrue(requestId?.matches(SAFE_REQUEST_ID_PATTERN) == true)
    }

    private companion object {
        val SAFE_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
