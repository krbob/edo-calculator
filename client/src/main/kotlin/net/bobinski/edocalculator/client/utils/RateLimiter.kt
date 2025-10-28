package net.bobinski.edocalculator.client.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RateLimiter(
    requestsPerSecond: Int,
    maxConcurrency: Int = 1,
    private val now: () -> Duration = defaultNow()
) {
    init {
        require(requestsPerSecond > 0) { "requestsPerSecond must be greater than zero" }
        require(maxConcurrency > 0) { "maxConcurrency must be greater than zero" }
    }

    private val gate = Semaphore(maxConcurrency)
    private val mutex = Mutex()
    private val interval: Duration = 1.seconds / requestsPerSecond
    private var nextAllowedOffset: Duration = Duration.ZERO

    suspend fun <T> limit(block: suspend () -> T): T = gate.withPermit {
        val waitFor = reserveSlot()
        if (waitFor.isPositive()) delay(waitFor)
        block()
    }

    private suspend fun reserveSlot(): Duration = mutex.withLock {
        val currentOffset = now()
        val waitDuration = (nextAllowedOffset - currentOffset).coerceAtLeast(Duration.ZERO)
        nextAllowedOffset = maxOf(nextAllowedOffset, currentOffset) + interval
        waitDuration
    }

    companion object {
        private fun defaultNow(): () -> Duration {
            val origin = TimeSource.Monotonic.markNow()
            return { origin.elapsedNow() }
        }
    }
}