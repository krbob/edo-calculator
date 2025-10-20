package net.bobinski.edocalculator.inflation

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusIndicatorPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertFailsWith

class GusInflationProviderTest {

    @Test
    fun `start equals end returns 1`() = runTest {
        val api = FakeApi(emptyMap())
        val provider = GusInflationProvider(api)

        val res = provider.getInflationMultiplier(YearMonth(2023, 5), YearMonth(2023, 5))
        assertEquals(BigDecimal.ONE, res)
    }

    @Test
    fun `start after end returns 1`() = runTest {
        val api = FakeApi(mapOf())
        val provider = GusInflationProvider(api)

        val res = provider.getInflationMultiplier(YearMonth(2023, 6), YearMonth(2023, 5))
        assertEquals(BigDecimal.ONE, res)
    }

    @Test
    fun `single month multiplies once`() = runTest {
        val api = FakeApi(
            mapOf(
                2023 to yearPoints(2023, "100.0", "100.0", "100.0", "100.0", "102.5")
            )
        )
        val provider = GusInflationProvider(api)

        val res = provider.getInflationMultiplier(YearMonth(2023, 5), YearMonth(2023, 6))
        assertEquals(BigDecimal("1.025000").setScale(6, RoundingMode.HALF_EVEN), res)
    }

    @Test
    fun `cross year range multiplies over both years`() = runTest {
        val api = FakeApi(
            mapOf(
                2023 to yearPoints(
                    2023,
                    "100.0", "100.0", "100.0", "100.0", "100.0", "100.0",
                    "100.0", "100.0", "100.0", "100.0", "100.0", "101.0"
                ),
                2024 to yearPoints(2024, "99.6", "100.0", "100.0")
            )
        )
        val provider = GusInflationProvider(api)

        val res = provider.getInflationMultiplier(YearMonth(2023, 12), YearMonth(2024, 2))
        assertEquals(BigDecimal("1.005960").setScale(6, RoundingMode.HALF_EVEN), res)
    }

    @Test
    fun `missing month throws`() = runTest {
        val api = FakeApi(
            mapOf(
                2023 to listOf(
                    GusIndicatorPoint(year = 2023, periodId = 247, value = BigDecimal("101.0"))
                )
            )
        )
        val provider = GusInflationProvider(api)

        assertFailsWith<MissingCpiDataException> {
            provider.getInflationMultiplier(YearMonth(2023, 1), YearMonth(2023, 3))
        }
    }
}

private fun yearPoints(year: Int, vararg values: String): List<GusIndicatorPoint> =
    values.mapIndexed { i, v ->
        GusIndicatorPoint(
            year = year,
            periodId = 240 + i + 1,
            value = BigDecimal(v)
        )
    }

private class FakeApi(
    private val data: Map<Int, List<GusIndicatorPoint>>
) : GusApi {
    override suspend fun fetchYearInflation(year: Int): List<GusIndicatorPoint> = data[year] ?: emptyList()
}