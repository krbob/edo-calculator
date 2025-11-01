package net.bobinski.edocalculator.route

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.datetime.YearMonth
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.core.dependency.CoreModule
import net.bobinski.edocalculator.core.time.toIsoString
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal

class CumulativeInflationRouteTest {

    @Test
    fun `responds with bad request when query parameters are missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/inflation/cumulative")
            val json = GlobalContext.get().get<Json>()

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'month' and 'year' must be integers.",
                body["error"]
            )
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with bad request when query parameters are out of range`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/inflation/cumulative") {
                parameter("year", "2024")
                parameter("month", "13")
            }
            val json = GlobalContext.get().get<Json>()

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Invalid month or year value.",
                body["error"]
            )
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with cumulative inflation data when request is valid`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()

        testApplication {
            configureApp(useCase)

            val start = YearMonth(2020, 5)
            val result = CalculateCumulativeInflationUseCase.Result(
                from = start,
                untilExclusive = YearMonth(2024, 2),
                multiplier = BigDecimal("1.234")
            )

            coEvery { useCase.invoke(start) } returns result

            val response = client.get("/inflation/cumulative") {
                parameter("year", start.year.toString())
                parameter("month", "5")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<CumulativeInflationResponse>(response.bodyAsText())
            assertEquals(start.toIsoString(), body.from)
            assertEquals(result.untilExclusive.toIsoString(), body.until)
            assertEquals(result.multiplier, body.multiplier)
            coVerify(exactly = 1) { useCase.invoke(start) }
        }
    }

    @Test
    fun `responds with bad request when use case throws IllegalArgumentException`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()

        testApplication {
            configureApp(useCase)

            coEvery { useCase.invoke(any()) } throws IllegalArgumentException("Start date in the future")

            val response = client.get("/inflation/cumulative") {
                parameter("year", "2024")
                parameter("month", "6")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Start date in the future", body["error"])
        }
    }

    @Test
    fun `responds with service unavailable when inflation data is missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()

        testApplication {
            configureApp(useCase)

            coEvery { useCase.invoke(any()) } throws MissingCpiDataException("No CPI data")

            val response = client.get("/inflation/cumulative") {
                parameter("year", "2024")
                parameter("month", "6")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("No CPI data", body["error"])
        }
    }

    @Test
    fun `responds with internal server error when unexpected exception is thrown`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()

        testApplication {
            configureApp(useCase)

            coEvery { useCase.invoke(any()) } throws IllegalStateException("boom")

            val response = client.get("/inflation/cumulative") {
                parameter("year", "2024")
                parameter("month", "6")
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("boom", body["error"])
        }
    }

    private fun ApplicationTestBuilder.configureApp(useCase: CalculateCumulativeInflationUseCase) {
        application {
            install(Koin) {
                modules(
                    CoreModule,
                    module {
                        single { useCase }
                    }
                )
            }
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { cumulativeInflationRoute() }
        }
    }
}
