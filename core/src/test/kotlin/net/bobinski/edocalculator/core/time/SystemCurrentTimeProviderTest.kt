package net.bobinski.edocalculator.core.time

import kotlinx.datetime.LocalDate
import kotlinx.datetime.YearMonth
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Instant

class SystemCurrentTimeProviderTest {

    @Test
    fun `calendar values use Warsaw business time at the UTC year boundary`() {
        val instant = Instant.parse("2025-12-31T23:30:00Z")
        val provider = SystemCurrentTimeProvider(now = { instant })

        assertEquals(instant, provider.instant())
        assertEquals(LocalDate(2026, 1, 1), provider.localDate())
        assertEquals(YearMonth(2026, 1), provider.yearMonth())
    }
}
