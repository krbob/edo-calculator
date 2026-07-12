package net.bobinski.edocalculator

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.route.ApiErrorCode
import net.bobinski.edocalculator.route.ApiErrorResponse
import net.bobinski.edocalculator.route.healthRoute
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

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

    @Test
    fun `uses the response request id in structured errors`() = testApplication {
        application { module() }

        val response = client.get("/edo/value") {
            header(HttpHeaders.XRequestId, "contract-test-123")
        }
        val error = Json.decodeFromString<ApiErrorResponse>(response.bodyAsText())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("contract-test-123", response.headers[HttpHeaders.XRequestId])
        assertEquals("contract-test-123", error.requestId)
        assertEquals(ApiErrorCode.INVALID_REQUEST, error.errorCode)
        assertEquals(false, error.retryable)
    }

    @Test
    fun `renders request id from MDC in the production log pattern`() {
        val logbackConfig = checkNotNull(javaClass.getResource("/logback.xml")).readText()

        assertTrue(logbackConfig.contains("[requestId=%X{requestId}]"))
    }

    @Test
    fun `attaches request id to the actual access log event`() {
        val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            testApplication {
                application { module() }

                client.get("/edo/value") {
                    header(HttpHeaders.XRequestId, "mdc-event-123")
                }
            }

            val requestEvent = appender.list.firstOrNull { event ->
                event.mdcPropertyMap["requestId"] == "mdc-event-123" &&
                    event.formattedMessage.contains("/edo/value")
            }
            assertNotNull(requestEvent)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Test
    fun `does not emit access log noise for metrics scrapes`() {
        val logger = LoggerFactory.getLogger("io.ktor.test") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            testApplication {
                application { module() }

                client.get("/metrics")
            }

            assertTrue(appender.list.none { event -> event.formattedMessage.contains("GET - /metrics") })
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private companion object {
        val SAFE_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
