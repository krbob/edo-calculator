package net.bobinski.edocalculator.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.YearMonth
import kotlinx.datetime.atStartOfDayIn
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.inflation.InflationProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class CalculateMonthlyInflationSeriesUseCaseTest {
    private val inflationProvider = mockk<InflationProvider>()
    private val currentTimeProvider = FakeCurrentTimeProvider(
        currentDate = LocalDate(2026, 3, 1),
        currentMonth = YearMonth(2026, 3)
    )
    private val useCase = CalculateMonthlyInflationSeriesUseCase(
        inflationProvider = inflationProvider,
        currentTimeProvider = currentTimeProvider
    )

    @Test
    fun `returns monthly multiplier points for requested range`() = runTest {
        val start = YearMonth(2025, 12)
        val endExclusive = YearMonth(2026, 3)
        coEvery { inflationProvider.getInflationMultiplier(YearMonth(2025, 12), YearMonth(2026, 1)) } returns BigDecimal("1.002")
        coEvery { inflationProvider.getInflationMultiplier(YearMonth(2026, 1), YearMonth(2026, 2)) } returns BigDecimal("1.003")
        coEvery { inflationProvider.getInflationMultiplier(YearMonth(2026, 2), YearMonth(2026, 3)) } returns BigDecimal("1.004")

        val result = useCase(start, endExclusive)

        assertEquals(start, result.from)
        assertEquals(endExclusive, result.untilExclusive)
        assertEquals(
            listOf(YearMonth(2025, 12), YearMonth(2026, 1), YearMonth(2026, 2)),
            result.points.map { it.month }
        )
        assertEquals(
            listOf(BigDecimal("1.002"), BigDecimal("1.003"), BigDecimal("1.004")),
            result.points.map { it.multiplier }
        )
        coVerify(exactly = 1) { inflationProvider.getInflationMultiplier(YearMonth(2025, 12), YearMonth(2026, 1)) }
        coVerify(exactly = 1) { inflationProvider.getInflationMultiplier(YearMonth(2026, 1), YearMonth(2026, 2)) }
        coVerify(exactly = 1) { inflationProvider.getInflationMultiplier(YearMonth(2026, 2), YearMonth(2026, 3)) }
    }

    @Test
    fun `throws when end month is not after start`() = runTest {
        val start = YearMonth(2026, 1)

        val exception = assertThrows<IllegalArgumentException> {
            useCase(start, start)
        }

        assertEquals("End date must be after start date.", exception.message)
    }

    @Test
    fun `throws when range reaches into future`() = runTest {
        val exception = assertThrows<IllegalArgumentException> {
            useCase(YearMonth(2025, 12), YearMonth(2026, 4))
        }

        assertEquals("End month must not be in the future.", exception.message)
    }

    private class FakeCurrentTimeProvider(
        private val currentDate: LocalDate,
        private val currentMonth: YearMonth
    ) : CurrentTimeProvider {
        override fun instant() = currentDate
            .atStartOfDayIn(TimeZone.UTC)

        override fun yearMonth(): YearMonth = currentMonth

        override fun localDate(): LocalDate = currentDate
    }
}
