package net.bobinski.edocalculator.domain.usecase

import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import kotlinx.datetime.minusMonth
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.InflationProvider
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class CalculateCumulativeInflationUseCaseTest {

    private val inflationProvider = mockk<InflationProvider>()
    private val currentTimeProvider = mockk<CurrentTimeProvider>()
    private val useCase = CalculateCumulativeInflationUseCase(
        inflationProvider = inflationProvider,
        currentTimeProvider = currentTimeProvider
    )

    @Test
    fun `returns multiplier using current month when data available`() = runTest {
        val start = YearMonth(2020, 3)
        val current = YearMonth(2024, 6)
        val multiplier = BigDecimal("1.234")

        every { currentTimeProvider.yearMonth() } returns current
        coEvery { inflationProvider.getInflationMultiplier(start, current) } returns multiplier

        val result = useCase(start)

        assertEquals(start, result.from)
        assertEquals(current, result.untilExclusive)
        assertEquals(multiplier, result.multiplier)
        coVerify(exactly = 1) { inflationProvider.getInflationMultiplier(start, current) }
    }

    @Test
    fun `uses previous month when inflation data for current month is missing`() = runTest {
        val start = YearMonth(2023, 1)
        val current = YearMonth(2024, 1)
        val fallback = current.minusMonth()
        val multiplier = BigDecimal("1.050")

        every { currentTimeProvider.yearMonth() } returns current
        coEvery { inflationProvider.getInflationMultiplier(start, current) } throws MissingCpiDataException.forMonth(
            current
        )
        coEvery { inflationProvider.getInflationMultiplier(start, fallback) } returns multiplier

        val result = useCase(start)

        assertEquals(start, result.from)
        assertEquals(fallback, result.untilExclusive)
        assertEquals(multiplier, result.multiplier)
        coVerifySequence {
            inflationProvider.getInflationMultiplier(start, current)
            inflationProvider.getInflationMultiplier(start, fallback)
        }
    }

    @Test
    fun `returns multiplier for explicit end when date range valid`() = runTest {
        val start = YearMonth(2022, 4)
        val endExclusive = YearMonth(2023, 7)
        val multiplier = BigDecimal("1.120")

        coEvery { inflationProvider.getInflationMultiplier(start, endExclusive) } returns multiplier

        val result = useCase(start, endExclusive)

        assertEquals(start, result.from)
        assertEquals(endExclusive, result.untilExclusive)
        assertEquals(multiplier, result.multiplier)
        coVerify(exactly = 1) { inflationProvider.getInflationMultiplier(start, endExclusive) }
        verify(exactly = 0) { currentTimeProvider.yearMonth() }
    }

    @Test
    fun `throws when explicit end is not after start`() {
        val start = YearMonth(2024, 5)

        assertThrows<IllegalArgumentException> {
            runTest {
                useCase(start, start)
            }
        }
    }
}
