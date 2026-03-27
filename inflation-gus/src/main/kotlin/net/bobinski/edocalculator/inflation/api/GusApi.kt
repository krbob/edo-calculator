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
    private val retry: RetryTooManyRequests = RetryTooManyRequests(times = 3, baseDelay = 100.milliseconds),
    private val currentTimeProvider: CurrentTimeProvider
) : GusApi {

    override suspend fun fetchYearInflation(attribute: GusAttribute, year: Int): List<GusIndicatorPoint> {
        require(year in MIN_SUPPORTED_YEAR..currentYear()) { "Unsupported year: $year" }

        return if (year >= FIRST_COICOP_2018_YEAR) {
            fetchFromVariableEndpoint(attribute, year)
        } else {
            fetchFromIndicatorEndpoint(attribute, year)
        }
    }

    private suspend fun fetchFromIndicatorEndpoint(
        attribute: GusAttribute, year: Int
    ): List<GusIndicatorPoint> {
        return try {
            limiter.limit {
                retry.execute {
                    try {
                        val response: List<GusIndicatorPoint> = client.get {
                            url(buildIndicatorEndpoint(attribute, year))
                            expectSuccess = true
                        }.body()

                        validateAndNormalize(response, year)
                    } catch (e: ClientRequestException) {
                        if (e.response.status == HttpStatusCode.NotFound) {
                            return@execute validateAndNormalize(emptyList(), year)
                        }
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
            val response = limiter.limit {
                retry.execute {
                    try {
                        client.get {
                            url(buildVariableEndpoint(year, periodId, page))
                            expectSuccess = true
                        }.body<GusVariableDataResponse>()
                    } catch (e: ClientRequestException) {
                        if (e.response.status == HttpStatusCode.NotFound) {
                            return@execute null
                        }
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

        normalized.ensureCompleteness(year)
        normalized.ensureNoGaps(year)

        return normalized
    }

    private fun currentYear(): Int = currentTimeProvider.yearMonth().year

    private fun List<GusIndicatorPoint>.ensureCompleteness(year: Int) {
        if (year == currentYear()) return
        if (isAllowedIncompletePreviousYear(year)) return
        if (size != EXPECTED_MONTHS) {
            throw MissingCpiDataException.forIncompleteYear(
                year = year,
                expected = EXPECTED_MONTHS,
                actual = size
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

    private fun List<GusIndicatorPoint>.ensureNoGaps(year: Int) {
        val gaps = asSequence()
            .map { it.periodId }
            .zipWithNext()
            .flatMap { (a, b) ->
                val diff = b - a
                if (diff > 1) ((a + 1) until b) else emptyList()
            }
            .toList()

        if (gaps.isNotEmpty()) {
            throw MissingCpiDataException.forMissingPeriods(year, gaps)
        }
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
        private const val GUS_PERIOD_JANUARY = 247
        private const val GUS_PERIOD_DECEMBER = 258
    }
}

internal enum class GusAttribute(val id: Int, val measureTypeId: Int) {
    MONTHLY(639, 2),
    ANNUAL(1832, 5)
}
