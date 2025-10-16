package net.bobinski.edocalculator.client.utils

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

@OptIn(ExperimentalTime::class)
class RateLimiter(
    private val limit: Int,
    private val window: Duration
) {
    private val mutex = Mutex()
    private val timestamps = ArrayDeque<Instant>()

    suspend fun <T> limit(block: suspend () -> T): T {
        while (true) {
            var waitTime = Duration.ZERO

            mutex.withLock {
                val now = Clock.System.now()
                val windowStart = now - window
                while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
                    timestamps.removeFirst()
                }
                if (timestamps.size >= limit) {
                    waitTime = timestamps.first() + window - now
                } else {
                    timestamps.addLast(now)
                    return block()
                }
            }

            delay(waitTime)
        }
    }
}