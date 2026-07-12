package net.bobinski.edocalculator.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.Called
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant
import java.math.BigDecimal

class CalculateEdoHistoryUseCaseTest {

    @Test
    fun `returns one point per day and defaults end date to current date`() = runTest {
        val calculateEdoValueUseCase = mockk<CalculateEdoValueUseCase>()
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2025, 1, 3))
        val useCase = CalculateEdoHistoryUseCase(
            calculateEdoValueUseCase = calculateEdoValueUseCase,
            currentTimeProvider = currentTimeProvider
        )

        coEvery {
            calculateEdoValueUseCase.dailyValues(
                purchaseDate = LocalDate(2025, 1, 1),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                from = LocalDate(2025, 1, 1),
                until = LocalDate(2025, 1, 3)
            )
        } returns sequenceOf(
            CalculateEdoValueUseCase.DailyValue(
                LocalDate(2025, 1, 1),
                BigDecimal("100.00"),
                BigDecimal("0.00")
            ),
            CalculateEdoValueUseCase.DailyValue(
                LocalDate(2025, 1, 2),
                BigDecimal("100.02"),
                BigDecimal("0.02")
            ),
            CalculateEdoValueUseCase.DailyValue(
                LocalDate(2025, 1, 3),
                BigDecimal("100.04"),
                BigDecimal("0.04")
            )
        )

        val result = useCase(
            purchaseDate = LocalDate(2025, 1, 1),
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100")
        )

        assertEquals(LocalDate(2025, 1, 1), result.from)
        assertEquals(LocalDate(2025, 1, 3), result.until)
        assertEquals(3, result.points.size)
        assertEquals(LocalDate(2025, 1, 1), result.points.first().date)
        assertEquals(LocalDate(2025, 1, 3), result.points.last().date)
        assertEquals(BigDecimal("100.04"), result.points.last().totalValue)
        coVerify(exactly = 1) {
            calculateEdoValueUseCase.dailyValues(
                purchaseDate = LocalDate(2025, 1, 1),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                from = LocalDate(2025, 1, 1),
                until = LocalDate(2025, 1, 3)
            )
        }
    }

    @Test
    fun `clamps from date to purchase date`() = runTest {
        val calculateEdoValueUseCase = mockk<CalculateEdoValueUseCase>()
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2025, 1, 5))
        val useCase = CalculateEdoHistoryUseCase(
            calculateEdoValueUseCase = calculateEdoValueUseCase,
            currentTimeProvider = currentTimeProvider
        )

        coEvery {
            calculateEdoValueUseCase.dailyValues(
                purchaseDate = LocalDate(2025, 1, 3),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                from = LocalDate(2025, 1, 3),
                until = LocalDate(2025, 1, 4)
            )
        } returns sequenceOf(
            CalculateEdoValueUseCase.DailyValue(
                LocalDate(2025, 1, 3),
                BigDecimal("100.00"),
                BigDecimal("0.00")
            ),
            CalculateEdoValueUseCase.DailyValue(
                LocalDate(2025, 1, 4),
                BigDecimal("100.02"),
                BigDecimal("0.02")
            )
        )

        val result = useCase(
            purchaseDate = LocalDate(2025, 1, 3),
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100"),
            from = LocalDate(2025, 1, 1),
            to = LocalDate(2025, 1, 4)
        )

        assertEquals(LocalDate(2025, 1, 3), result.from)
        assertEquals(LocalDate(2025, 1, 4), result.until)
        assertEquals(2, result.points.size)
    }

    @Test
    fun `loads each annual inflation rate once for an entire daily range`() = runTest {
        val inflationMonth = YearMonth(2023, 11)
        val inflationProvider = mockk<InflationProvider>()
        coEvery { inflationProvider.getYearlyInflationMultiplier(inflationMonth) } returns BigDecimal("1.030")
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2024, 6, 5))
        val valueUseCase = CalculateEdoValueUseCase(inflationProvider, currentTimeProvider)
        val useCase = CalculateEdoHistoryUseCase(valueUseCase, currentTimeProvider)

        val result = useCase(
            purchaseDate = LocalDate(2023, 1, 1),
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100"),
            from = LocalDate(2024, 6, 1),
            to = LocalDate(2024, 6, 5)
        )

        assertEquals(5, result.points.size)
        coVerify(exactly = 1) { inflationProvider.getYearlyInflationMultiplier(inflationMonth) }
    }

    @Test
    fun `daily schedule stays identical to point-in-time valuation across a period boundary`() = runTest {
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2024, 1, 3))
        val valueUseCase = CalculateEdoValueUseCase(
            inflationProvider = FakeInflationProvider(
                mapOf(YearMonth(2023, 11) to BigDecimal("1.031234"))
            ),
            currentTimeProvider = currentTimeProvider
        )
        val useCase = CalculateEdoHistoryUseCase(valueUseCase, currentTimeProvider)
        val purchaseDate = LocalDate(2023, 1, 1)

        val result = useCase(
            purchaseDate = purchaseDate,
            firstPeriodRate = BigDecimal("7.2549"),
            margin = BigDecimal("1.256789"),
            principal = BigDecimal("123.45"),
            from = LocalDate(2023, 12, 30),
            to = LocalDate(2024, 1, 3)
        )

        result.points.forEach { point ->
            val directValue = valueUseCase(
                purchaseDate = purchaseDate,
                firstPeriodRate = BigDecimal("7.2549"),
                margin = BigDecimal("1.256789"),
                principal = BigDecimal("123.45"),
                asOf = point.date
            )
            assertEquals(directValue.edoValue.totalValue, point.totalValue)
            assertEquals(directValue.edoValue.totalAccruedInterest, point.totalAccruedInterest)
        }
    }

    @Test
    fun `daily schedule stays flat after maturity`() = runTest {
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2033, 1, 3))
        val valueUseCase = CalculateEdoValueUseCase(
            inflationProvider = FakeInflationProvider(emptyMap()),
            currentTimeProvider = currentTimeProvider
        )
        val useCase = CalculateEdoHistoryUseCase(valueUseCase, currentTimeProvider)

        val result = useCase(
            purchaseDate = LocalDate(2023, 1, 1),
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            from = LocalDate(2032, 12, 31),
            to = LocalDate(2033, 1, 3)
        )

        val maturityValue = result.points.first { it.date == LocalDate(2033, 1, 1) }
        result.points.filter { it.date >= maturityValue.date }.forEach { point ->
            assertEquals(maturityValue.totalValue, point.totalValue)
            assertEquals(maturityValue.totalAccruedInterest, point.totalAccruedInterest)
        }
    }

    @Test
    fun `rejects history that would exceed response point limit`() = runTest {
        val calculateEdoValueUseCase = mockk<CalculateEdoValueUseCase>(relaxed = true)
        val useCase = CalculateEdoHistoryUseCase(
            calculateEdoValueUseCase = calculateEdoValueUseCase,
            currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2026, 7, 12))
        )

        val exception = org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
            useCase(
                purchaseDate = LocalDate(2010, 1, 1),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100")
            )
        }

        assertEquals("EDO history must not exceed 4000 daily points.", exception.message)
        coVerify { calculateEdoValueUseCase wasNot Called }
    }

    private class FakeCurrentTimeProvider(
        private val date: LocalDate
    ) : CurrentTimeProvider {
        override fun instant(): Instant {
            throw UnsupportedOperationException("instant() is not used in tests")
        }

        override fun yearMonth(): YearMonth = YearMonth(date.year, date.month.number)

        override fun localDate(): LocalDate = date
    }

    private class FakeInflationProvider(
        private val yearlyMultipliers: Map<YearMonth, BigDecimal>
    ) : InflationProvider {
        override suspend fun getInflationMultiplier(start: YearMonth, end: YearMonth): BigDecimal =
            BigDecimal.ONE

        override suspend fun getYearlyInflationMultiplier(month: YearMonth): BigDecimal =
            yearlyMultipliers[month] ?: BigDecimal.ONE
    }
}
