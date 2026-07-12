package net.bobinski.edocalculator.inflation.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.number
import net.bobinski.edocalculator.client.utils.RateLimiter
import net.bobinski.edocalculator.client.utils.RetryTooManyRequests
import net.bobinski.edocalculator.core.time.CurrentTimeProvider
import net.bobinski.edocalculator.domain.error.CpiProviderUnavailableException
import net.bobinski.edocalculator.domain.error.MissingCpiDataException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.time.Duration.Companion.milliseconds

internal const val MIN_SUPPORTED_YEAR = 2010

internal interface GusApi {
    suspend fun fetchYearInflation(attribute: GusAttribute, year: Int): List<GusIndicatorPoint>
}

internal class GusApiImpl(
    private val client: HttpClient,
    private val limiter: RateLimiter = RateLimiter(requestsPerSecond = 5, maxConcurrency = 5),
    private val metrics: GusMetrics = GusMetrics.NO_OP,
    private val retry: RetryTooManyRequests = RetryTooManyRequests(
        times = 3,
        baseDelay = 100.milliseconds,
        onRetry = metrics::recordRetry
    ),
    private val currentTimeProvider: CurrentTimeProvider
) : GusApi {

    override suspend fun fetchYearInflation(attribute: GusAttribute, year: Int): List<GusIndicatorPoint> {
        val endpoint = if (year >= FIRST_COICOP_2018_YEAR) GusEndpoint.VARIABLE else GusEndpoint.INDICATOR
        val observation = metrics.startFetch(attribute, endpoint)

        return try {
            require(year in MIN_SUPPORTED_YEAR..currentYear()) { "Unsupported year: $year" }
            val result = when (endpoint) {
                GusEndpoint.INDICATOR -> fetchFromIndicatorEndpoint(attribute, year)
                GusEndpoint.VARIABLE -> fetchFromVariableEndpoint(attribute, year)
            }
            observation.complete(GusFetchOutcome.SUCCESS)
            result
        } catch (e: CancellationException) {
            observation.complete(GusFetchOutcome.CANCELLED)
            throw e
        } catch (e: MissingCpiDataException) {
            observation.complete(GusFetchOutcome.MISSING_DATA)
            throw e
        } catch (e: CpiProviderUnavailableException) {
            observation.complete(GusFetchOutcome.PROVIDER_UNAVAILABLE)
            throw e
        } catch (e: Exception) {
            observation.complete(GusFetchOutcome.ERROR)
            throw e
        }
    }

    private suspend fun fetchFromIndicatorEndpoint(
        attribute: GusAttribute, year: Int
    ): List<GusIndicatorPoint> {
        return try {
            executeRequest {
                try {
                    val response: List<GusIndicatorPoint> = client.get {
                        url(buildIndicatorEndpoint(attribute, year))
                        expectSuccess = true
                    }.body()

                    validateAndNormalize(response, year)
                } catch (e: ClientRequestException) {
                    if (e.response.status == HttpStatusCode.NotFound) {
                        validateAndNormalize(emptyList(), year)
                    } else {
                        throw e
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: MissingCpiDataException) {
            throw e
        } catch (e: CpiProviderUnavailableException) {
            throw e
        } catch (e: UnresolvedAddressException) {
            throw CpiProviderUnavailableException(cause = e)
        } catch (e: HttpRequestTimeoutException) {
            throw CpiProviderUnavailableException(cause = e)
        } catch (e: IOException) {
            throw CpiProviderUnavailableException(cause = e)
        } catch (e: ResponseException) {
            throw CpiProviderUnavailableException(cause = e)
        }
    }

    private suspend fun fetchFromVariableEndpoint(
        attribute: GusAttribute, year: Int
    ): List<GusIndicatorPoint> {
        return try {
            val points = coroutineScope {
                (GUS_PERIOD_JANUARY..GUS_PERIOD_DECEMBER).map { periodId ->
                    async { fetchVariableDataPoint(attribute, year, periodId) }
                }.awaitAll().filterNotNull()
            }
            validateAndNormalize(points, year)
        } catch (e: CancellationException) {
            throw e
        } catch (e: MissingCpiDataException) {
            throw e
        } catch (e: CpiProviderUnavailableException) {
            throw e
        } catch (e: UnresolvedAddressException) {
            throw CpiProviderUnavailableException(cause = e)
        } catch (e: HttpRequestTimeoutException) {
            throw CpiProviderUnavailableException(cause = e)
        } catch (e: IOException) {
            throw CpiProviderUnavailableException(cause = e)
        } catch (e: ResponseException) {
            throw CpiProviderUnavailableException(cause = e)
        }
    }

    private suspend fun fetchVariableDataPoint(
        attribute: GusAttribute, year: Int, periodId: Int
    ): GusIndicatorPoint? {
        var page = 1
        while (true) {
            val response = executeRequest {
                try {
                    client.get {
                        url(buildVariableEndpoint(year, periodId, page))
                        expectSuccess = true
                    }.body<GusVariableDataResponse>()
                } catch (e: ClientRequestException) {
                    if (e.response.status == HttpStatusCode.NotFound) {
                        null
                    } else {
                        throw e
                    }
                }
            } ?: return null

            val match = response.data.find {
                it.coicop2018Position == COICOP_2018_TOTAL_POSITION &&
                    it.householdPosition == HOUSEHOLD_TOTAL_POSITION &&
                    it.measureType == attribute.measureTypeId
            }

            if (match != null) {
                return GusIndicatorPoint(
                    year = match.year,
                    periodId = match.periodId,
                    value = match.value
                )
            }

            if (response.data.size < VARIABLE_PAGE_SIZE) return null
            page++
        }
    }

    private suspend fun <T> executeRequest(block: suspend () -> T): T = retry.execute {
        limiter.limit(block)
    }

    private fun buildIndicatorEndpoint(attribute: GusAttribute, year: Int): Url = URLBuilder(BASE_URL).apply {
        encodedPath = "/api/1.1.0/indicators/indicator-data-indicator"
        parameters.append("id-wskaznik", attribute.id.toString())
        parameters.append("id-rok", year.toString())
        parameters.append("lang", LANG)
    }.build()

    private fun buildVariableEndpoint(year: Int, periodId: Int, page: Int): Url = URLBuilder(BASE_URL).apply {
        encodedPath = "/api/1.1.0/variable/variable-data-section"
        parameters.append("id-zmienna", CPI_VARIABLE_ID.toString())
        parameters.append("id-przekroj", COICOP_2018_SECTION_ID.toString())
        parameters.append("id-rok", year.toString())
        parameters.append("id-okres", periodId.toString())
        parameters.append("page-size", VARIABLE_PAGE_SIZE.toString())
        parameters.append("page", page.toString())
        parameters.append("lang", LANG)
    }.build()

    private fun validateAndNormalize(
        points: List<GusIndicatorPoint>, year: Int
    ): List<GusIndicatorPoint> {
        val normalized = points
            .distinctBy { it.periodId }
            .sortedBy { it.periodId }

        normalized.ensureExpectedPeriodCoverage(year)

        return normalized
    }

    private fun currentYear(): Int = currentTimeProvider.yearMonth().year

    private fun List<GusIndicatorPoint>.ensureExpectedPeriodCoverage(year: Int) {
        if (size > EXPECTED_MONTHS) {
            throw MissingCpiDataException.forIncompleteYear(
                year = year,
                expected = EXPECTED_MONTHS,
                actual = size
            )
        }

        val expectedCount = when {
            year == currentYear() -> size
            isAllowedIncompletePreviousYear(year) -> EXPECTED_MONTHS - 1
            size == EXPECTED_MONTHS -> EXPECTED_MONTHS
            else -> throw MissingCpiDataException.forIncompleteYear(
                year = year,
                expected = EXPECTED_MONTHS,
                actual = size
            )
        }
        val expectedPeriods = GUS_MONTHLY_PERIOD_IDS.take(expectedCount)
        val actualPeriods = map { it.periodId }

        if (actualPeriods != expectedPeriods || any { it.year != year }) {
            throw MissingCpiDataException.forPeriodMismatch(
                year = year,
                expected = expectedPeriods,
                actual = actualPeriods
            )
        }
    }

    private fun List<GusIndicatorPoint>.isAllowedIncompletePreviousYear(year: Int): Boolean {
        val now = currentTimeProvider.localDate()
        val isPreviousYear = year == now.year - 1
        if (!isPreviousYear) return false
        if (now.month.number != GRACE_PERIOD_MONTH) return false
        return size == EXPECTED_MONTHS - 1
    }

    companion object {
        private const val BASE_URL = "https://api-sdp.stat.gov.pl"
        private const val LANG = "pl"
        private const val EXPECTED_MONTHS = 12
        private const val GRACE_PERIOD_MONTH = 1
        internal const val FIRST_COICOP_2018_YEAR = 2026
        private const val CPI_VARIABLE_ID = 305
        private const val COICOP_2018_SECTION_ID = 1698
        private const val COICOP_2018_TOTAL_POSITION = 14916914
        private const val HOUSEHOLD_TOTAL_POSITION = 6902025
        private const val VARIABLE_PAGE_SIZE = 5000
    }
}

internal enum class GusAttribute(val id: Int, val measureTypeId: Int) {
    MONTHLY(639, 2),
    ANNUAL(1832, 5)
}
