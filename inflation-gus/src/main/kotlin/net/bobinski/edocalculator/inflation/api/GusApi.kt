package net.bobinski.edocalculator.inflation.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import net.bobinski.edocalculator.client.utils.RateLimiter
import net.bobinski.edocalculator.client.utils.RetryTooManyRequests
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

internal interface GusApi {
    suspend fun fetchYearInflation(year: Int): List<GusIndicatorPoint>
}

@OptIn(ExperimentalTime::class)
internal class GusApiImpl(
    private val client: HttpClient,
    private val limiter: RateLimiter = RateLimiter(requestsPerSecond = 5, maxConcurrency = 5),
    private val retry: RetryTooManyRequests = RetryTooManyRequests(times = 3, baseDelay = 100.milliseconds),
    private val clock: Clock = Clock.System
) : GusApi {

    override suspend fun fetchYearInflation(year: Int): List<GusIndicatorPoint> {
        require(year in MIN_SUPPORTED_YEAR..currentYear()) { "Unsupported year: $year" }

        return limiter.limit {
            retry.execute {
                val response: List<GusIndicatorPoint> = client.get {
                    url(buildEndpoint(year))
                    expectSuccess = true
                }.body()

                validateAndNormalize(response, year)
            }
        }
    }

    private fun buildEndpoint(year: Int): Url = URLBuilder(BASE_URL).apply {
        encodedPath = "/api/1.1.0/indicators/indicator-data-indicator"
        parameters.append("id-wskaznik", INDICATOR_ID.toString())
        parameters.append("id-rok", year.toString())
        parameters.append("lang", LANG)
    }.build()

    private fun validateAndNormalize(
        points: List<GusIndicatorPoint>, year: Int
    ): List<GusIndicatorPoint> {
        val normalized = points
            .distinctBy { it.periodId }
            .sortedBy { it.periodId }

        val gaps: List<Int> = normalized
            .map { it.periodId }
            .zipWithNext()
            .flatMap { (a, b) ->
                val diff = b - a
                if (diff > 1) ((a + 1) until b).toList() else emptyList()
            }

        if (year != currentYear()) {
            if (normalized.size != 12) {
                throw MissingCpiDataException("Missing CPI data for $year: expected 12 items, got ${normalized.size}")
            }
        }
        if (gaps.isNotEmpty()) {
            throw MissingCpiDataException("Missing CPI period data for $year: ${gaps.joinToString(",")}")
        }

        return normalized
    }

    private fun currentYear(): Int = clock.now().toLocalDateTime(TimeZone.UTC).year

    companion object {
        private const val BASE_URL = "https://api-sdp.stat.gov.pl"
        private const val INDICATOR_ID: Int = 639
        private const val LANG = "pl"
        private const val MIN_SUPPORTED_YEAR = 2010
    }
}