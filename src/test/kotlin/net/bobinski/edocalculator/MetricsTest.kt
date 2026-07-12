package net.bobinski.edocalculator

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MetricsTest {

    @Test
    fun `Prometheus endpoint exposes bounded HTTP request metrics without scrape self-noise`() = testApplication {
        application {
            module()
            routing {
                get("/test/metrics-unhandled") { error("metrics test failure") }
            }
        }

        client.get("/healthz")
        client.get("/v1/edo/value") {
            header(HttpHeaders.XRequestId, REQUEST_ID)
        }
        client.get("/not-found-$HIGH_CARDINALITY_SEGMENT")
        client.post("/v1/edo/value")
        client.get("/test/metrics-unhandled")

        val firstScrape = client.get("/metrics")
        val secondScrape = client.get("/metrics")
        val body = secondScrape.bodyAsText()

        assertEquals(HttpStatusCode.OK, firstScrape.status)
        assertEquals(HttpStatusCode.OK, secondScrape.status)
        assertTrue(firstScrape.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/plain"))
        assertTrue(body.contains("edo_http_server_requests_seconds_count"))
        assertTrue(body.contains("edo_http_server_requests_seconds_bucket"))
        assertTrue(body.contains("route=\"/healthz\""))
        assertTrue(body.contains("route=\"/v1/edo/value\""))
        assertTrue(body.contains("status=\"200\""))
        assertTrue(body.contains("status=\"400\""))
        assertTrue(body.contains("status=\"404\""))
        assertTrue(body.contains("status=\"405\""))
        assertTrue(body.contains("status=\"500\""))
        assertTrue(body.contains("route=\"/{...}\""))
        val notFoundCount = body.lineSequence()
            .first { line ->
                line.startsWith("edo_http_server_requests_seconds_count{") &&
                    line.contains("route=\"/{...}\"") &&
                    line.contains("status=\"404\"")
            }
            .substringAfterLast(' ')
            .toDouble()
        assertEquals(1.0, notFoundCount)
        assertEquals(1.0, body.requestCount(status = "405", route = "/{...}"))
        assertEquals(1.0, body.requestCount(status = "500", route = "/test/metrics-unhandled"))
        assertFalse(body.contains(HIGH_CARDINALITY_SEGMENT))
        assertFalse(body.contains(REQUEST_ID))
        assertFalse(body.contains("requestId="))
        assertFalse(body.contains("address="))
        assertFalse(body.contains("throwable="))
        assertFalse(body.contains("route=\"/metrics\""))
        val activeRequests = body.lineSequence()
            .first { line -> line.startsWith("edo_http_server_requests_active ") }
            .substringAfterLast(' ')
            .toDouble()
        assertTrue(activeRequests >= 0.0)
    }

    private fun String.requestCount(status: String, route: String): Double =
        lineSequence()
            .first { line ->
                line.startsWith("edo_http_server_requests_seconds_count{") &&
                    line.contains("route=\"$route\"") &&
                    line.contains("status=\"$status\"")
            }
            .substringAfterLast(' ')
            .toDouble()

    @Test
    fun `HTTP meter filters remove host and throwable and bound custom methods`() {
        val registry = SimpleMeterRegistry()
        registry.configureLowCardinalityHttpTags()

        registry.counter(
            "test.requests",
            "method",
            HIGH_CARDINALITY_METHOD,
            "address",
            HIGH_CARDINALITY_ADDRESS,
            "throwable",
            HIGH_CARDINALITY_THROWABLE
        ).increment()

        val counter = requireNotNull(registry.find("test.requests").tag("method", "OTHER").counter())
        assertEquals(setOf("method"), counter.id.tags.map { tag -> tag.key }.toSet())
        assertFalse(registry.meters.any { meter -> meter.id.tags.any { tag -> tag.value == HIGH_CARDINALITY_METHOD } })
    }

    private companion object {
        const val REQUEST_ID = "metrics-test-request"
        const val HIGH_CARDINALITY_SEGMENT = "user-controlled-987654321"
        const val HIGH_CARDINALITY_METHOD = "USER-CONTROLLED-METHOD-987654321"
        const val HIGH_CARDINALITY_ADDRESS = "user-controlled-host.example:65535"
        const val HIGH_CARDINALITY_THROWABLE = "user.controlled.Exception987654321"
    }
}
