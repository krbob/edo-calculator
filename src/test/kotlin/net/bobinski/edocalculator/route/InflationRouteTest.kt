package net.bobinski.edocalculator.route

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.datetime.YearMonth
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.core.dependency.CoreModule
import net.bobinski.edocalculator.domain.error.CpiProviderUnavailableException
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.domain.usecase.CalculateCumulativeInflationUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateMonthlyInflationSeriesUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal

class InflationRouteTest {

    @Test
    fun `responds with bad request when query parameters are missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val response = client.get("/inflation/since")
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
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val response = client.get("/inflation/since") {
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
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val start = YearMonth(2020, 5)
            val result = CalculateCumulativeInflationUseCase.Result(
                from = start,
                untilExclusive = YearMonth(2024, 2),
                multiplier = BigDecimal("1.234")
            )

            coEvery { useCase.invoke(start) } returns result

            val response = client.get("/inflation/since") {
                parameter("year", start.year.toString())
                parameter("month", "5")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<InflationResponse>(response.bodyAsText())
            assertEquals(start.toString(), body.from)
            assertEquals(result.untilExclusive.toString(), body.until)
            assertEquals(result.multiplier, body.multiplier)
            coVerify(exactly = 1) { useCase.invoke(start) }
        }
    }

    @Test
    fun `responds with bad request when use case throws IllegalArgumentException`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            coEvery { useCase.invoke(any()) } throws IllegalArgumentException("Start date in the future")

            val response = client.get("/inflation/since") {
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
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            coEvery { useCase.invoke(any()) } throws MissingCpiDataException("No CPI data")

            val response = client.get("/inflation/since") {
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
    fun `responds with service unavailable when CPI provider cannot be reached`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            coEvery { useCase.invoke(any()) } throws CpiProviderUnavailableException()

            val response = client.get("/inflation/since") {
                parameter("year", "2024")
                parameter("month", "6")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Unable to reach CPI provider.", body["error"])
        }
    }

    @Test
    fun `responds with internal server error when unexpected exception is thrown`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            coEvery { useCase.invoke(any()) } throws IllegalStateException("boom")

            val response = client.get("/inflation/since") {
                parameter("year", "2024")
                parameter("month", "6")
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Unexpected error occurred.", body["error"])
        }
    }

    @Test
    fun `range endpoint returns bad request when parameters missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val response = client.get("/inflation/between")
            val json = GlobalContext.get().get<Json>()

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'startMonth', 'startYear', 'endMonth', and 'endYear' must be integers.",
                body["error"]
            )
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `range endpoint returns bad request when start date invalid`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val response = client.get("/inflation/between") {
                parameter("startYear", "2024")
                parameter("startMonth", "13")
                parameter("endYear", "2025")
                parameter("endMonth", "2")
            }
            val json = GlobalContext.get().get<Json>()

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Invalid start month or year value.", body["error"])
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `range endpoint returns cumulative inflation when data available`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val start = YearMonth(2021, 2)
            val endExclusive = YearMonth(2023, 9)
            val result = CalculateCumulativeInflationUseCase.Result(
                from = start,
                untilExclusive = endExclusive,
                multiplier = BigDecimal("1.180")
            )
            coEvery { useCase.invoke(start, endExclusive) } returns result

            val response = client.get("/inflation/between") {
                parameter("startYear", start.year.toString())
                parameter("startMonth", "2")
                parameter("endYear", endExclusive.year.toString())
                parameter("endMonth", "9")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<InflationResponse>(response.bodyAsText())
            assertEquals(start.toString(), body.from)
            assertEquals(endExclusive.toString(), body.until)
            assertEquals(result.multiplier, body.multiplier)
            coVerify(exactly = 1) { useCase.invoke(start, endExclusive) }
        }
    }

    @Test
    fun `range endpoint returns bad request when use case rejects range`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val start = YearMonth(2024, 5)
            val endExclusive = YearMonth(2024, 5)
            coEvery { useCase.invoke(start, endExclusive) } throws IllegalArgumentException("End date must be after start date.")

            val response = client.get("/inflation/between") {
                parameter("startYear", "2024")
                parameter("startMonth", "5")
                parameter("endYear", "2024")
                parameter("endMonth", "5")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("End date must be after start date.", body["error"])
        }
    }

    @Test
    fun `range endpoint returns service unavailable when CPI data missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val start = YearMonth(2022, 1)
            val endExclusive = YearMonth(2024, 1)
            coEvery { useCase.invoke(start, endExclusive) } throws MissingCpiDataException("No CPI data")

            val response = client.get("/inflation/between") {
                parameter("startYear", "2022")
                parameter("startMonth", "1")
                parameter("endYear", "2024")
                parameter("endMonth", "1")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("No CPI data", body["error"])
        }
    }

    @Test
    fun `range endpoint returns internal server error for unexpected exception`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>()
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val start = YearMonth(2020, 1)
            val endExclusive = YearMonth(2021, 1)
            coEvery { useCase.invoke(start, endExclusive) } throws IllegalStateException("boom")

            val response = client.get("/inflation/between") {
                parameter("startYear", "2020")
                parameter("startMonth", "1")
                parameter("endYear", "2021")
                parameter("endMonth", "1")
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Unexpected error occurred.", body["error"])
        }
    }

    @Test
    fun `monthly endpoint returns monthly inflation series when request is valid`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>()

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val start = YearMonth(2025, 12)
            val endExclusive = YearMonth(2026, 3)
            val result = CalculateMonthlyInflationSeriesUseCase.Result(
                from = start,
                untilExclusive = endExclusive,
                points = listOf(
                    CalculateMonthlyInflationSeriesUseCase.MonthlyInflationPoint(
                        month = YearMonth(2025, 12),
                        multiplier = BigDecimal("1.002")
                    ),
                    CalculateMonthlyInflationSeriesUseCase.MonthlyInflationPoint(
                        month = YearMonth(2026, 1),
                        multiplier = BigDecimal("1.003")
                    )
                )
            )
            coEvery { monthlyUseCase.invoke(start, endExclusive) } returns result

            val response = client.get("/inflation/monthly") {
                parameter("startYear", "2025")
                parameter("startMonth", "12")
                parameter("endYear", "2026")
                parameter("endMonth", "3")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<MonthlyInflationSeriesResponse>(response.bodyAsText())
            assertEquals("2025-12", body.from)
            assertEquals("2026-03", body.until)
            assertEquals(listOf("2025-12", "2026-01"), body.points.map { it.month })
            assertEquals(listOf(BigDecimal("1.002"), BigDecimal("1.003")), body.points.map { it.multiplier })
            coVerify(exactly = 1) { monthlyUseCase.invoke(start, endExclusive) }
        }
    }

    @Test
    fun `monthly endpoint returns bad request when parameters missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase, monthlyUseCase)

            val response = client.get("/inflation/monthly")
            val json = GlobalContext.get().get<Json>()

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'startMonth', 'startYear', 'endMonth', and 'endYear' must be integers.",
                body["error"]
            )
            coVerify { monthlyUseCase wasNot Called }
        }
    }

    @Test
    fun `monthly endpoint returns service unavailable when CPI data is missing`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>()

        testApplication {
            configureApp(useCase, monthlyUseCase)

            coEvery { monthlyUseCase.invoke(any(), any()) } throws MissingCpiDataException("No CPI data for 2026-02")

            val response = client.get("/inflation/monthly") {
                parameter("startYear", "2025")
                parameter("startMonth", "12")
                parameter("endYear", "2026")
                parameter("endMonth", "3")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("No CPI data for 2026-02", body["error"])
        }
    }

    @Test
    fun `monthly endpoint returns bad request when use case rejects range`() {
        val useCase = mockk<CalculateCumulativeInflationUseCase>(relaxed = true)
        val monthlyUseCase = mockk<CalculateMonthlyInflationSeriesUseCase>()

        testApplication {
            configureApp(useCase, monthlyUseCase)

            coEvery { monthlyUseCase.invoke(any(), any()) } throws IllegalArgumentException("End month must not be in the future.")

            val response = client.get("/inflation/monthly") {
                parameter("startYear", "2025")
                parameter("startMonth", "12")
                parameter("endYear", "2027")
                parameter("endMonth", "1")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("End month must not be in the future.", body["error"])
        }
    }

    private fun ApplicationTestBuilder.configureApp(
        useCase: CalculateCumulativeInflationUseCase,
        monthlyUseCase: CalculateMonthlyInflationSeriesUseCase
    ) {
        application {
            configureKoin(useCase, monthlyUseCase)
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { inflationRoute() }
        }
    }

    private fun Application.configureKoin(
        useCase: CalculateCumulativeInflationUseCase,
        monthlyUseCase: CalculateMonthlyInflationSeriesUseCase
    ) {
        install(Koin) {
            modules(
                CoreModule,
                module {
                    single { useCase }
                    single { monthlyUseCase }
                }
            )
        }
    }
}
