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
import net.bobinski.edocalculator.core.time.toIsoString
import net.bobinski.edocalculator.domain.edo.EdoPeriodBreakdown
import net.bobinski.edocalculator.domain.edo.EdoValue
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.domain.usecase.CalculateEdoValueUseCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import org.koin.ktor.ext.get
import org.koin.ktor.plugin.Koin
import java.math.BigDecimal
import java.nio.channels.UnresolvedAddressException

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
            startDate = purchaseDate.toIsoString(),
            endDate = LocalDate(2024, 1, 1).toIsoString(),
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
            assertEquals(expectedResult.purchaseDate.toIsoString(), body.purchaseDate)
            assertEquals(expectedResult.asOf.toIsoString(), body.asOf)
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
            startDate = purchaseDate.toIsoString(),
            endDate = LocalDate(2024, 1, 1).toIsoString(),
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
            assertEquals(expectedResult.purchaseDate.toIsoString(), body.purchaseDate)
            assertEquals(expectedResult.asOf.toIsoString(), body.asOf)
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
        coEvery { useCase.invoke(any(), any(), any(), any(), any()) } throws UnresolvedAddressException()

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

    private fun ApplicationTestBuilder.configureApp(useCase: CalculateEdoValueUseCase) {
        application {
            install(Koin) {
                modules(
                    CoreModule,
                    module { single { useCase } }
                )
            }
            val json: Json = get()
            install(ContentNegotiation) { json(json) }
            routing { edoRoute() }
        }
    }
}
