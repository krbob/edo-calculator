package net.bobinski.edocalculator.route

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import net.bobinski.edocalculator.core.dependency.CoreModule
import net.bobinski.edocalculator.domain.error.CpiProviderUnavailableException
import net.bobinski.edocalculator.domain.edo.EdoPeriodBreakdown
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.domain.usecase.CalculateEdoHistoryUseCase
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal
class EdoRouteTest {

    @Test
    fun `responds with bad request when purchase date parameters missing`() {
        val useCase = mockk<CalculateEdoValueUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value")
            val json = GlobalContext.get().get<Json>()

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'purchaseYear' and 'purchaseMonth' and 'purchaseDay' must be integers.",
                body["error"]
            )
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with bad request when purchase date invalid`() {
        val useCase = mockk<CalculateEdoValueUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2024")
                parameter("purchaseMonth", "2")
                parameter("purchaseDay", "31")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            val json = GlobalContext.get().get<Json>()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Invalid day, month or year value.", body["error"])
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with bad request when rates missing`() {
        val useCase = mockk<CalculateEdoValueUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
            }

            val json = GlobalContext.get().get<Json>()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'firstPeriodRate' and 'margin' must be decimals.",
                body["error"]
            )
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with bad request when principal is not a decimal`() {
        val useCase = mockk<CalculateEdoValueUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
                parameter("principal", "abc")
            }

            val json = GlobalContext.get().get<Json>()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Query parameter 'principal' must be a decimal.", body["error"])
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with bad request when as-of date parameters missing`() {
        val useCase = mockk<CalculateEdoValueUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value/at") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            val json = GlobalContext.get().get<Json>()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'asOfYear' and 'asOfMonth' and 'asOfDay' must be integers.",
                body["error"]
            )
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with bad request when as-of date invalid`() {
        val useCase = mockk<CalculateEdoValueUseCase>(relaxed = true)

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value/at") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("asOfYear", "2024")
                parameter("asOfMonth", "2")
                parameter("asOfDay", "31")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            val json = GlobalContext.get().get<Json>()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Invalid as-of day, month or year value.", body["error"])
            coVerify { useCase wasNot Called }
        }
    }

    @Test
    fun `responds with edo value when request is valid`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        val purchaseDate = LocalDate(2023, 1, 1)
        val asOf = LocalDate(2024, 6, 15)
        val period = EdoPeriodBreakdown(
            index = 1,
            startDate = purchaseDate.toString(),
            endDate = LocalDate(2024, 1, 1).toString(),
            daysInPeriod = 365,
            daysElapsed = 365,
            ratePercent = BigDecimal("7.25"),
            inflationPercent = null,
            interestAccrued = BigDecimal("7.25"),
            value = BigDecimal("107.25")
        )
        val expectedResult = CalculateEdoValueUseCase.Result(
            purchaseDate = purchaseDate,
            asOf = asOf,
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100"),
            edoValue = EdoValue(
                totalValue = BigDecimal("107.25"),
                totalAccruedInterest = BigDecimal("7.25"),
                periods = listOf(period)
            )
        )

        coEvery {
            useCase.invoke(
                purchaseDate = purchaseDate,
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                asOf = null
            )
        } returns expectedResult

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
                parameter("principal", "100")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<EdoResponse>(response.bodyAsText())
            assertEquals(expectedResult.purchaseDate.toString(), body.purchaseDate)
            assertEquals(expectedResult.asOf.toString(), body.asOf)
            assertEquals(expectedResult.firstPeriodRate, body.firstPeriodRate)
            assertEquals(expectedResult.margin, body.margin)
            assertEquals(expectedResult.principal, body.principal)
            assertEquals(expectedResult.edoValue.periods.size, body.edoValue.periods.size)
            coVerify(exactly = 1) {
                useCase.invoke(
                    purchaseDate = purchaseDate,
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("1.25"),
                    principal = BigDecimal("100"),
                    asOf = null
                )
            }
        }
    }

    @Test
    fun `responds with edo value when as-of date provided`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        val purchaseDate = LocalDate(2023, 1, 1)
        val asOf = LocalDate(2023, 6, 1)
        val period = EdoPeriodBreakdown(
            index = 1,
            startDate = purchaseDate.toString(),
            endDate = LocalDate(2024, 1, 1).toString(),
            daysInPeriod = 365,
            daysElapsed = 151,
            ratePercent = BigDecimal("7.25"),
            inflationPercent = null,
            interestAccrued = BigDecimal("3.00"),
            value = BigDecimal("103.00")
        )
        val expectedResult = CalculateEdoValueUseCase.Result(
            purchaseDate = purchaseDate,
            asOf = asOf,
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100"),
            edoValue = EdoValue(
                totalValue = BigDecimal("103.00"),
                totalAccruedInterest = BigDecimal("3.00"),
                periods = listOf(period)
            )
        )

        coEvery {
            useCase.invoke(
                purchaseDate = purchaseDate,
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                asOf = asOf
            )
        } returns expectedResult

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value/at") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("asOfYear", "2023")
                parameter("asOfMonth", "6")
                parameter("asOfDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
                parameter("principal", "100")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<EdoResponse>(response.bodyAsText())
            assertEquals(expectedResult.purchaseDate.toString(), body.purchaseDate)
            assertEquals(expectedResult.asOf.toString(), body.asOf)
            assertEquals(expectedResult.firstPeriodRate, body.firstPeriodRate)
            assertEquals(expectedResult.margin, body.margin)
            assertEquals(expectedResult.principal, body.principal)
            assertEquals(expectedResult.edoValue.periods.size, body.edoValue.periods.size)
            coVerify(exactly = 1) {
                useCase.invoke(
                    purchaseDate = purchaseDate,
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("1.25"),
                    principal = BigDecimal("100"),
                    asOf = asOf
                )
            }
        }
    }

    @Test
    fun `responds with bad request when use case rejects request`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        coEvery { useCase.invoke(any(), any(), any(), any(), any()) } throws IllegalArgumentException("Principal too small")

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Principal too small", body["error"])
        }
    }

    @Test
    fun `responds with service unavailable when CPI data is missing`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        coEvery { useCase.invoke(any(), any(), any(), any(), any()) } throws MissingCpiDataException("Missing CPI")

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Missing CPI", body["error"])
        }
    }

    @Test
    fun `responds with service unavailable when CPI provider cannot be reached`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        coEvery { useCase.invoke(any(), any(), any(), any(), any()) } throws CpiProviderUnavailableException()

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Unable to reach CPI provider.", body["error"])
        }
    }

    @Test
    fun `responds with generic message when unexpected exception is thrown`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        coEvery { useCase.invoke(any(), any(), any(), any(), any()) } throws IllegalStateException()

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals("Unexpected error occurred.", body["error"])
        }
    }

    @Test
    fun `uses default principal of 100 when not specified`() {
        val useCase = mockk<CalculateEdoValueUseCase>()
        val purchaseDate = LocalDate(2023, 1, 1)
        val asOf = LocalDate(2024, 6, 15)
        val period = EdoPeriodBreakdown(
            index = 1,
            startDate = purchaseDate.toString(),
            endDate = LocalDate(2024, 1, 1).toString(),
            daysInPeriod = 365,
            daysElapsed = 365,
            ratePercent = BigDecimal("7.25"),
            inflationPercent = null,
            interestAccrued = BigDecimal("7.25"),
            value = BigDecimal("107.25")
        )
        val expectedResult = CalculateEdoValueUseCase.Result(
            purchaseDate = purchaseDate,
            asOf = asOf,
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100"),
            edoValue = EdoValue(
                totalValue = BigDecimal("107.25"),
                totalAccruedInterest = BigDecimal("7.25"),
                periods = listOf(period)
            )
        )

        coEvery {
            useCase.invoke(
                purchaseDate = purchaseDate,
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                asOf = null
            )
        } returns expectedResult

        testApplication {
            configureApp(useCase)

            val response = client.get("/edo/value") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
                // principal intentionally omitted
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<EdoResponse>(response.bodyAsText())
            assertEquals(BigDecimal("100"), body.principal)
            coVerify(exactly = 1) {
                useCase.invoke(
                    purchaseDate = purchaseDate,
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("1.25"),
                    principal = BigDecimal("100"),
                    asOf = null
                )
            }
        }
    }

    @Test
    fun `responds with edo history when request is valid`() {
        val valueUseCase = mockk<CalculateEdoValueUseCase>(relaxed = true)
        val historyUseCase = mockk<CalculateEdoHistoryUseCase>()
        val purchaseDate = LocalDate(2023, 1, 1)
        val from = LocalDate(2023, 1, 2)
        val until = LocalDate(2023, 1, 4)
        val expectedResult = CalculateEdoHistoryUseCase.Result(
            purchaseDate = purchaseDate,
            from = from,
            until = until,
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100.00"),
            points = listOf(
                CalculateEdoHistoryUseCase.HistoryPoint(
                    date = from,
                    totalValue = BigDecimal("100.25"),
                    totalAccruedInterest = BigDecimal("0.25")
                ),
                CalculateEdoHistoryUseCase.HistoryPoint(
                    date = LocalDate(2023, 1, 3),
                    totalValue = BigDecimal("100.50"),
                    totalAccruedInterest = BigDecimal("0.50")
                ),
                CalculateEdoHistoryUseCase.HistoryPoint(
                    date = until,
                    totalValue = BigDecimal("100.75"),
                    totalAccruedInterest = BigDecimal("0.75")
                )
            )
        )

        coEvery {
            historyUseCase.invoke(
                purchaseDate = purchaseDate,
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                from = from,
                to = until
            )
        } returns expectedResult

        testApplication {
            configureApp(valueUseCase, historyUseCase)

            val response = client.get("/edo/history") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("fromYear", "2023")
                parameter("fromMonth", "1")
                parameter("fromDay", "2")
                parameter("toYear", "2023")
                parameter("toMonth", "1")
                parameter("toDay", "4")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            assertEquals(HttpStatusCode.OK, response.status)

            val json = GlobalContext.get().get<Json>()
            val body = json.decodeFromString<EdoHistoryResponse>(response.bodyAsText())
            assertEquals("2023-01-01", body.purchaseDate)
            assertEquals("2023-01-02", body.from)
            assertEquals("2023-01-04", body.until)
            assertEquals(3, body.points.size)
            assertEquals("2023-01-02", body.points.first().date)
            assertEquals(BigDecimal("100.75"), body.points.last().totalValue)
            coVerify(exactly = 1) {
                historyUseCase.invoke(
                    purchaseDate = purchaseDate,
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("1.25"),
                    principal = BigDecimal("100"),
                    from = from,
                    to = until
                )
            }
        }
    }

    @Test
    fun `responds with bad request when optional history date is partially provided`() {
        val valueUseCase = mockk<CalculateEdoValueUseCase>(relaxed = true)
        val historyUseCase = mockk<CalculateEdoHistoryUseCase>(relaxed = true)

        testApplication {
            configureApp(valueUseCase, historyUseCase)

            val response = client.get("/edo/history") {
                parameter("purchaseYear", "2023")
                parameter("purchaseMonth", "1")
                parameter("purchaseDay", "1")
                parameter("fromYear", "2023")
                parameter("fromMonth", "1")
                parameter("firstPeriodRate", "7.25")
                parameter("margin", "1.25")
            }

            val json = GlobalContext.get().get<Json>()
            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = json.decodeFromString<Map<String, String>>(response.bodyAsText())
            assertEquals(
                "Query parameters 'fromYear' and 'fromMonth' and 'fromDay' must be integers when provided.",
                body["error"]
            )
            coVerify { historyUseCase wasNot Called }
        }
    }

    private fun ApplicationTestBuilder.configureApp(
        useCase: CalculateEdoValueUseCase,
        historyUseCase: CalculateEdoHistoryUseCase = mockk(relaxed = true)
    ) {
        application {
            install(Koin) {
                modules(
                    CoreModule,
                    module {
                        single { useCase }
                        single { historyUseCase }
                    }
                )
            }
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { edoRoute() }
        }
    }
}
