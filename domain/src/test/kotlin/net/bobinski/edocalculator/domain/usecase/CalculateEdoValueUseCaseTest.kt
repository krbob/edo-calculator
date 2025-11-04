package net.bobinski.edocalculator.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class CalculateEdoValueUseCaseTest {

    @Test
    fun `returns interest accrued up to previous day`() = runTest {
        val inflationProvider = FakeInflationProvider(
            mapOf(
                YearMonth(2023, 11) to BigDecimal("1.066"),
                YearMonth(2024, 11) to BigDecimal("1.047")
            )
        )
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2025, 11, 4))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        val result = useCase(
            purchaseDate = LocalDate(2023, 1, 1),
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100")
        )

        assertEquals(BigDecimal("21.46"), result.edoValue.totalAccruedInterest)
        assertEquals(BigDecimal("121.46"), result.edoValue.totalValue)
        assertEquals(307, result.edoValue.periods.last().daysElapsed)
    }

    @Test
    fun `treats deflation as zero inflation when calculating subsequent periods`() = runTest {
        val inflationProvider = FakeInflationProvider(
            mapOf(YearMonth(2023, 11) to BigDecimal("0.970"))
        )
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2024, 6, 1))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        val result = useCase(
            purchaseDate = LocalDate(2023, 1, 1),
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100")
        )

        val secondPeriod = result.edoValue.periods.first { it.index == 2 }
        assertEquals(BigDecimal("0.00"), secondPeriod.inflationPercent)
        assertEquals(BigDecimal("1.25"), secondPeriod.ratePercent)
    }

    @Test
    fun `queries yearly inflation multiplier once per period`() = runTest {
        val inflationMonth = YearMonth(2023, 11)
        val inflationProvider = mockk<InflationProvider>(relaxed = true)
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2024, 6, 1))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        coEvery { inflationProvider.getYearlyInflationMultiplier(inflationMonth) } returns BigDecimal("1.030")

        useCase(
            purchaseDate = LocalDate(2023, 1, 1),
            firstPeriodRate = BigDecimal("7.00"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100")
        )

        coVerify(exactly = 1) { inflationProvider.getYearlyInflationMultiplier(inflationMonth) }
    }

    @Test
    fun `throws when margin is negative`() {
        val inflationProvider = FakeInflationProvider(emptyMap())
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2024, 1, 1))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        val exception = assertThrows<IllegalArgumentException> {
            runTest {
                useCase(
                    purchaseDate = LocalDate(2023, 1, 1),
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("-0.01"),
                    principal = BigDecimal("100")
                )
            }
        }

        assertEquals("Margin must not be negative.", exception.message)
    }

    @Test
    fun `throws when purchase date is in the future`() {
        val inflationProvider = FakeInflationProvider(emptyMap())
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2023, 12, 31))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        val exception = assertThrows<IllegalArgumentException> {
            runTest {
                useCase(
                    purchaseDate = LocalDate(2024, 1, 1),
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("1.25"),
                    principal = BigDecimal("100")
                )
            }
        }

        assertEquals("As-of date must be on or after purchase date.", exception.message)
    }

    @Test
    fun `throws when provided as-of date is in the future`() {
        val inflationProvider = FakeInflationProvider(emptyMap())
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2024, 1, 1))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        val exception = assertThrows<IllegalArgumentException> {
            runTest {
                useCase(
                    purchaseDate = LocalDate(2023, 1, 1),
                    firstPeriodRate = BigDecimal("7.25"),
                    margin = BigDecimal("1.25"),
                    principal = BigDecimal("100"),
                    asOf = LocalDate(2024, 1, 2)
                )
            }
        }

        assertEquals("As-of date must not be in the future.", exception.message)
    }

    @Test
    fun `uses provided as-of date when calculating value`() = runTest {
        val inflationProvider = FakeInflationProvider(emptyMap())
        val currentTimeProvider = FakeCurrentTimeProvider(LocalDate(2025, 1, 1))
        val useCase = CalculateEdoValueUseCase(
            inflationProvider = inflationProvider,
            currentTimeProvider = currentTimeProvider
        )

        val purchaseDate = LocalDate(2023, 1, 1)
        val asOf = purchaseDate

        val result = useCase(
            purchaseDate = purchaseDate,
            firstPeriodRate = BigDecimal("7.25"),
            margin = BigDecimal("1.25"),
            principal = BigDecimal("100"),
            asOf = asOf
        )

        assertEquals(asOf, result.asOf)
        assertEquals(BigDecimal("0.00"), result.edoValue.totalAccruedInterest)
        assertEquals(1, result.edoValue.periods.size)
        assertEquals(0, result.edoValue.periods.single().daysElapsed)
    }

    private class FakeInflationProvider(
        private val yearlyMultipliers: Map<YearMonth, BigDecimal>
    ) : InflationProvider {
        override suspend fun getInflationMultiplier(start: YearMonth, end: YearMonth): BigDecimal {
            return BigDecimal.ONE
        }

        override suspend fun getYearlyInflationMultiplier(month: YearMonth): BigDecimal {
            return yearlyMultipliers[month] ?: BigDecimal.ONE
        }
    }

    @OptIn(ExperimentalTime::class)
    private class FakeCurrentTimeProvider(
        private val date: LocalDate
    ) : CurrentTimeProvider {
        override fun instant(): Instant {
            throw UnsupportedOperationException("instant() is not used in tests")
        }

        override fun yearMonth(): YearMonth = YearMonth(date.year, date.month.ordinal + 1)

        override fun localDate(): LocalDate {
            return date
        }
    }
}
