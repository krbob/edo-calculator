package net.bobinski.edocalculator.inflation

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.YearMonth
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import net.bobinski.edocalculator.inflation.api.GusApi
import net.bobinski.edocalculator.inflation.api.GusAttribute
import net.bobinski.edocalculator.inflation.api.GusIndicatorPoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.test.assertFailsWith

class GusInflationProviderTest {

    @Test
    fun `start equals end returns 1`() = runTest {
        val api = FakeApi()
        val provider = GusInflationProvider(api)

        val res = provider.getInflationMultiplier(YearMonth(2023, 5), YearMonth(2023, 5))
        assertEquals(BigDecimal.ONE, res)
    }

    @Test
    fun `start after end returns 1`() = runTest {
        val api = FakeApi()
        val provider = GusInflationProvider(api)

        val res = provider.getInflationMultiplier(YearMonth(2023, 6), YearMonth(2023, 5))
        assertEquals(BigDecimal.ONE, res)
    }

    @Test
    fun `single month multiplies once`() = runTest {
        val api = FakeApi(
            monthly = mapOf(
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
            monthly = mapOf(
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
            monthly = mapOf(
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

    @Test
    fun `yearly multiplier returns expected value`() = runTest {
        val api = FakeApi(
            yearly = mapOf(
                2023 to yearPoints(2023, "105.0", "110.0", "115.0")
            )
        )
        val provider = GusInflationProvider(api)

        val res = provider.getYearlyInflationMultiplier(YearMonth(2023, 2))
        assertEquals(BigDecimal("1.100000").setScale(6, RoundingMode.HALF_EVEN), res)
    }

    @Test
    fun `missing yearly data throws`() = runTest {
        val api = FakeApi(yearly = emptyMap())
        val provider = GusInflationProvider(api)

        assertFailsWith<MissingCpiDataException> {
            provider.getYearlyInflationMultiplier(YearMonth(2023, 1))
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
    private val monthly: Map<Int, List<GusIndicatorPoint>> = emptyMap(),
    private val yearly: Map<Int, List<GusIndicatorPoint>> = emptyMap()
) : GusApi {
    override suspend fun fetchYearInflation(attribute: GusAttribute, year: Int): List<GusIndicatorPoint> =
        when (attribute) {
            GusAttribute.MONTHLY -> monthly[year] ?: emptyList()
            GusAttribute.ANNUAL -> yearly[year] ?: emptyList()
        }
}
