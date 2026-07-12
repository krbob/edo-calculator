package net.bobinski.edocalculator.route

import io.ktor.server.application.ApplicationCall
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class RouteExtTest {

    @Test
    fun `request cancellation is propagated`() = runTest {
        val call = mockk<ApplicationCall>()
        val cancellation = CancellationException("client disconnected")

        try {
            call.handleUseCaseCall<Unit> { throw cancellation }
        } catch (e: CancellationException) {
            assertSame(cancellation, e)
            return@runTest
        }

        fail("Expected request cancellation to be propagated")
    }
}
