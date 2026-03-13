package net.bobinski.edocalculator.domain.usecase

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import kotlinx.datetime.number
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.edo.EdoValue
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
            calculateEdoValueUseCase(
                purchaseDate = LocalDate(2025, 1, 1),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                asOf = any()
            )
        } answers {
            val asOf = arg<LocalDate>(4)
            CalculateEdoValueUseCase.Result(
                purchaseDate = LocalDate(2025, 1, 1),
                asOf = asOf,
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100.00"),
                edoValue = EdoValue(
                    totalValue = BigDecimal("100.00"),
                    totalAccruedInterest = BigDecimal("0.00"),
                    periods = emptyList()
                )
            )
        }

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
        coVerify(exactly = 3) {
            calculateEdoValueUseCase(
                purchaseDate = LocalDate(2025, 1, 1),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                asOf = any()
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
            calculateEdoValueUseCase(
                purchaseDate = LocalDate(2025, 1, 3),
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100"),
                asOf = any()
            )
        } answers {
            val asOf = arg<LocalDate>(4)
            CalculateEdoValueUseCase.Result(
                purchaseDate = LocalDate(2025, 1, 3),
                asOf = asOf,
                firstPeriodRate = BigDecimal("7.25"),
                margin = BigDecimal("1.25"),
                principal = BigDecimal("100.00"),
                edoValue = EdoValue(
                    totalValue = BigDecimal("100.00"),
                    totalAccruedInterest = BigDecimal("0.00"),
                    periods = emptyList()
                )
            )
        }

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

    private class FakeCurrentTimeProvider(
        private val date: LocalDate
    ) : CurrentTimeProvider {
        override fun instant(): Instant {
            throw UnsupportedOperationException("instant() is not used in tests")
        }

        override fun yearMonth(): YearMonth = YearMonth(date.year, date.month.number)

        override fun localDate(): LocalDate = date
    }
}
