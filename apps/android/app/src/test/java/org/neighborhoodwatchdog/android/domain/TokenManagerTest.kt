package org.neighborhoodwatchdog.android.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.auth.AuthTokens
import org.neighborhoodwatchdog.android.domain.auth.RefreshOutcome
import org.neighborhoodwatchdog.android.domain.auth.TokenManager
import org.neighborhoodwatchdog.android.domain.auth.TokenRefresher
import org.neighborhoodwatchdog.android.domain.auth.TokenStore

class InMemoryTokenStore(var tokens: AuthTokens? = null) : TokenStore {
    override fun load(): AuthTokens? = tokens
    override fun save(tokens: AuthTokens) {
        this.tokens = tokens
    }

    override fun clear() {
        tokens = null
    }
}

/** Behaves like the auth service: every refresh token works exactly once (rotation). */
class RotatingRefresher(private val clock: FakeClock, firstRefreshToken: String) : TokenRefresher {
    val seen = mutableListOf<String>()
    var gate: CompletableDeferred<Unit>? = null
    var temporaryFailure = false
    private var valid = firstRefreshToken
    private var counter = 1

    override suspend fun refresh(refreshToken: String): RefreshOutcome {
        seen += refreshToken
        gate?.await()
        if (temporaryFailure) return RefreshOutcome.TemporaryFailure
        if (refreshToken != valid) return RefreshOutcome.SessionInvalid
        counter++
        valid = "refresh-$counter"
        return RefreshOutcome.Success(AuthTokens("access-$counter", valid, clock.now + 3_600_000))
    }
}

class TokenManagerTest {
    private val clock = FakeClock()
    private val store = InMemoryTokenStore(
        AuthTokens("access-1", "refresh-1", clock.now + 3_600_000, userId = "u-1", email = "resident@example.org"),
    )
    private val refresher = RotatingRefresher(clock, "refresh-1")
    private var sessionEnded = 0
    private val manager = TokenManager(store, refresher, clock, onSessionEnded = { sessionEnded++ })

    @Test
    fun freshTokenIsUsedWithoutRefreshing() = runTest {
        assertEquals("access-1", manager.validAccessToken())
        assertTrue(refresher.seen.isEmpty())
    }

    @Test
    fun tokenWithLessThanSixtySecondsLeftIsRefreshedAndRotated() = runTest {
        clock.now += 3_600_000 - 59_000
        assertEquals("access-2", manager.validAccessToken())
        assertEquals(listOf("refresh-1"), refresher.seen)
        assertEquals("the new refresh token replaced the old one", "refresh-2", store.tokens?.refreshToken)
        assertEquals("the user is kept when the refresh answer has none", "resident@example.org", store.tokens?.email)
    }

    @Test
    fun exactlySixtySecondsLeftIsStillValid() = runTest {
        clock.now += 3_600_000 - 60_000
        assertEquals("access-1", manager.validAccessToken())
    }

    @Test
    fun parallelCallersCauseExactlyOneRefresh() = runTest {
        clock.now += 3_600_000 // expired
        val gate = CompletableDeferred<Unit>()
        refresher.gate = gate
        val callers = List(8) { async { manager.validAccessToken() } }
        val after401 = List(4) { async { manager.refreshAfterUnauthorized("access-1") } }
        repeat(20) { yield() } // everyone is now waiting on the mutex or on the refresh call
        assertEquals("only one refresh is in flight", 1, refresher.seen.size)
        gate.complete(Unit)
        val results = (callers + after401).awaitAll()
        assertEquals(List(12) { "access-2" }, results)
        assertEquals("rotation was never raced", listOf("refresh-1"), refresher.seen)
        assertEquals(0, sessionEnded)
    }

    @Test
    fun unauthorizedTriggersOneRefreshAndReturnsTheNewToken() = runTest {
        assertEquals("access-2", manager.refreshAfterUnauthorized("access-1"))
        // A second request that was rejected with the old token reuses the token already obtained.
        assertEquals("access-2", manager.refreshAfterUnauthorized("access-1"))
        assertEquals(listOf("refresh-1"), refresher.seen)
    }

    @Test
    fun refusedRefreshTokenEndsTheSession() = runTest {
        store.tokens = store.tokens!!.copy(refreshToken = "already-used")
        assertNull(manager.refreshAfterUnauthorized("access-1"))
        assertNull(store.tokens)
        assertFalse(manager.isSignedIn())
        assertEquals(1, sessionEnded)
        assertNull(manager.validAccessToken())
    }

    @Test
    fun temporaryFailureKeepsTheSession() = runTest {
        refresher.temporaryFailure = true
        clock.now += 3_600_000 - 30_000 // 30 s left
        assertEquals("the old token is still valid for 30 s", "access-1", manager.validAccessToken())
        assertNull(manager.refreshAfterUnauthorized("access-1"))
        assertEquals("refresh-1", store.tokens?.refreshToken)
        assertEquals(0, sessionEnded)

        clock.now += 31_000 // now really expired and still no network
        assertNull(manager.validAccessToken())
        assertTrue("still signed in; a later refresh can succeed", manager.isSignedIn())
        refresher.temporaryFailure = false
        assertEquals("access-2", manager.validAccessToken())
    }

    @Test
    fun signedOutHasNoToken() = runTest {
        manager.clear()
        assertNull(manager.validAccessToken())
        assertNull(manager.refreshAfterUnauthorized(null))
        assertTrue(refresher.seen.isEmpty())
    }
}
