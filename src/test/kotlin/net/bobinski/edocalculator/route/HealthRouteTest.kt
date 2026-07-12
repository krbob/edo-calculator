package net.bobinski.edocalculator.route

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import net.bobinski.edocalculator.module
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HealthRouteTest {
    @Test
    fun `responds with ok`() {
        testApplication {
            application {
                routing { healthRoute() }
            }

            val response = client.get("/healthz")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ok", response.bodyAsText())
        }
    }

    @Test
    fun `responds ready when dependency check succeeds`() {
        testApplication {
            application {
                routing { readinessRoute {} }
            }

            val response = client.get("/readyz")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ready", response.bodyAsText())
        }
    }

    @Test
    fun `responds service unavailable without leaking dependency failure`() {
        testApplication {
            application {
                routing { readinessRoute { error("secret dependency details") } }
            }

            val response = client.get("/readyz")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            assertEquals("not ready", response.bodyAsText())
        }
    }

    @Test
    fun `application is ready when production dependency graph resolves`() {
        testApplication {
            application { module() }

            val response = client.get("/readyz")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("ready", response.bodyAsText())
        }
    }
}
