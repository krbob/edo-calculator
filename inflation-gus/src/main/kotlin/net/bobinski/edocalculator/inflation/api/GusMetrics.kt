package net.bobinski.edocalculator.inflation.api

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import net.bobinski.edocalculator.client.utils.RetryReason
import java.util.concurrent.atomic.AtomicBoolean

internal interface GusMetrics {
    fun startFetch(attribute: GusAttribute, endpoint: GusEndpoint): GusFetchObservation

    fun recordCacheRequest(attribute: GusAttribute, result: GusCacheResult)

    fun recordRetry(reason: RetryReason)

    companion object {
        val NO_OP: GusMetrics = object : GusMetrics {
            override fun startFetch(attribute: GusAttribute, endpoint: GusEndpoint): GusFetchObservation =
                GusFetchObservation.NO_OP

            override fun recordCacheRequest(attribute: GusAttribute, result: GusCacheResult) = Unit

            override fun recordRetry(reason: RetryReason) = Unit
        }
    }
}

internal fun interface GusFetchObservation {
    fun complete(outcome: GusFetchOutcome)

    companion object {
        val NO_OP = GusFetchObservation {}
    }
}

internal class MicrometerGusMetrics(private val registry: MeterRegistry) : GusMetrics {
    override fun startFetch(attribute: GusAttribute, endpoint: GusEndpoint): GusFetchObservation {
        val sample = Timer.start(registry)
        val completed = AtomicBoolean()

        return GusFetchObservation { outcome ->
            if (completed.compareAndSet(false, true)) {
                sample.stop(
                    Timer.builder(GUS_FETCH_METRIC)
                        .description("Duration and outcome of logical GUS year fetches")
                        .tag(ATTRIBUTE_TAG, attribute.tagValue)
                        .tag(ENDPOINT_TAG, endpoint.tagValue)
                        .tag(OUTCOME_TAG, outcome.tagValue)
                        .register(registry)
                )
            }
        }
    }

    override fun recordCacheRequest(attribute: GusAttribute, result: GusCacheResult) {
        Counter.builder(GUS_CACHE_METRIC)
            .description("GUS cache request outcomes")
            .tag(ATTRIBUTE_TAG, attribute.tagValue)
            .tag(RESULT_TAG, result.tagValue)
            .register(registry)
            .increment()
    }

    override fun recordRetry(reason: RetryReason) {
        Counter.builder(GUS_RETRY_METRIC)
            .description("Additional GUS request attempts scheduled after retryable responses")
            .tag(REASON_TAG, reason.tagValue)
            .register(registry)
            .increment()
    }
}

internal enum class GusEndpoint(val tagValue: String) {
    INDICATOR("indicator"),
    VARIABLE("variable")
}

internal enum class GusFetchOutcome(val tagValue: String) {
    SUCCESS("success"),
    MISSING_DATA("missing_data"),
    PROVIDER_UNAVAILABLE("provider_unavailable"),
    CANCELLED("cancelled"),
    ERROR("error")
}

internal enum class GusCacheResult(val tagValue: String) {
    HIT("hit"),
    LOAD("load"),
    STALE_FALLBACK("stale_fallback"),
    LOAD_ERROR("load_error"),
    CANCELLED("cancelled")
}

private val GusAttribute.tagValue: String
    get() = name.lowercase()

private val RetryReason.tagValue: String
    get() = name.lowercase()

private const val GUS_FETCH_METRIC = "edo.gus.fetch"
private const val GUS_CACHE_METRIC = "edo.gus.cache.requests"
private const val GUS_RETRY_METRIC = "edo.gus.retries"
private const val ATTRIBUTE_TAG = "attribute"
private const val ENDPOINT_TAG = "endpoint"
private const val OUTCOME_TAG = "outcome"
private const val RESULT_TAG = "result"
private const val REASON_TAG = "reason"
