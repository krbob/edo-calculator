package net.bobinski.edocalculator.inflation.api

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import net.bobinski.edocalculator.core.dependency.CoreModule
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.stopKoin
import org.koin.test.KoinTest
import org.koin.test.get
import java.math.BigDecimal
import kotlin.test.assertFailsWith
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
class GusApiTest : KoinTest {

    @BeforeEach
    fun setup() {
        startKoin { modules(CoreModule) }
    }

    @AfterEach
    fun teardown() {
        stopKoin()
    }

    @Test
    fun `200 for current year allows partial data and normalizes`() = runTest {
        val client = http { req ->
            assertEquals("/api/1.1.0/indicators/indicator-data-indicator", req.url.encodedPath)
            assertEquals("639", req.url.parameters["id-wskaznik"])
            assertEquals("2012", req.url.parameters["id-rok"])
            assertEquals("pl", req.url.parameters["lang"])
            respond(payloadForYear(2012), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(2012)))
        val points = api.fetchYearInflation(2012)

        assertEquals(2, points.size)
        assertEquals(2012, points[0].year)
        assertEquals(247, points[0].periodId)
        assertEquals(BigDecimal("102.5"), points[0].value)
    }

    @Test
    fun `429 then 200 is retried and succeeds`() = runTest {
        var hits = 0
        val client = http {
            hits++
            if (hits == 1) respond("too many", HttpStatusCode.TooManyRequests)
            else respond(
                payloadForYear(2015), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(2015)))
        val points = api.fetchYearInflation(2015)

        assertEquals(2, points.size)
        assertEquals(2, hits)
    }

    @Test
    fun `past year with incomplete data throws MissingCpiDataException`() = runTest {
        val client = http {
            respond(
                payloadForYear(2015), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(2025)))

        assertFailsWith<MissingCpiDataException> {
            api.fetchYearInflation(2015)
        }
    }

    @Test
    fun `past year with complete data passes`() = runTest {
        val client = http {
            respond(
                fullPayloadForYear(2015), HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(2025)))

        val points = api.fetchYearInflation(2015)

        assertEquals(12, points.size)
        assertEquals((241..252).toList(), points.map { it.periodId })
    }

    @Test
    fun `duplicates by periodId are removed and result is sorted`() = runTest {
        val year = 2020
        val dupPayload = """
        [
          {"id-daty": $year, "id-okres": 248, "wartosc": 100.0},
          {"id-daty": $year, "id-okres": 247, "wartosc": 102.5},
          {"id-daty": $year, "id-okres": 247, "wartosc": 99.9}
        ]
    """.trimIndent()
        val client = http {
            respond(dupPayload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(year)))
        val points = api.fetchYearInflation(year)

        assertEquals(listOf(247, 248), points.map { it.periodId })
    }

    @Test
    fun `past year with gap in period id throws MissingCpiDataException`() = runTest {
        val year = 2018
        val payloadWithGap = """
        [
          {"id-daty": $year, "id-okres": 247, "wartosc": 101.0},
          {"id-daty": $year, "id-okres": 248, "wartosc": 102.0},
          {"id-daty": $year, "id-okres": 250, "wartosc": 103.0}
        ]
    """.trimIndent()

        val client = http {
            respond(payloadWithGap, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(2025)))

        assertFailsWith<MissingCpiDataException> {
            api.fetchYearInflation(year)
        }
    }

    @Test
    fun `current year with gap in period id throws MissingCpiDataException`() = runTest {
        val year = 2025
        val payloadWithGap = """
        [
          {"id-daty": $year, "id-okres": 300, "wartosc": 100.0},
          {"id-daty": $year, "id-okres": 302, "wartosc": 100.1}
        ]
    """.trimIndent()

        val client = http {
            respond(payloadWithGap, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val api = GusApiImpl(client = client, currentTimeProvider = MutableCurrentTimeProvider(fixedNow(year)))

        assertFailsWith<MissingCpiDataException> {
            api.fetchYearInflation(year)
        }
    }

    @Test
    fun `year below 2010 throws IllegalArgumentException`() = runTest {
        val api = GusApiImpl(
            client = http { error("should not be called") },
            currentTimeProvider = MutableCurrentTimeProvider(fixedNow(2025))
        )
        assertFailsWith<IllegalArgumentException> { api.fetchYearInflation(2009) }
    }
}

private fun KoinTest.http(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient =
    HttpClient(MockEngine) {
        engine {
            addHandler(handler)
        }
        install(ContentNegotiation) {
            json(get())
        }
    }

private fun payloadForYear(year: Int): String = """
[
  {"id-daty": $year, "id-okres": 248, "wartosc": 100.0},
  {"id-daty": $year, "id-okres": 247, "wartosc": 102.5}
]
""".trimIndent()

private fun fullPayloadForYear(year: Int): String =
    (1..12).joinToString(prefix = "[", postfix = "]", separator = ",") { i ->
        """{"id-daty": $year, "id-okres": ${240 + i}, "wartosc": ${100 + i / 10.0}}"""
    }