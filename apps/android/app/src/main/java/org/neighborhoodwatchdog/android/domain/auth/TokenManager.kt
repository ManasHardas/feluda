package org.neighborhoodwatchdog.android.domain.auth

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import org.neighborhoodwatchdog.android.domain.model.Clock

@Serializable
data class AuthTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtMs: Long,
    val userId: String? = null,
    val email: String? = null,
)

/** Keystore-encrypted on the device; in memory in tests. */
interface TokenStore {
    fun load(): AuthTokens?
    fun save(tokens: AuthTokens)
    fun clear()
}

sealed interface RefreshOutcome {
    data class Success(val tokens: AuthTokens) : RefreshOutcome

    /** The auth service refused the refresh token: the session is over. */
    data object SessionInvalid : RefreshOutcome

    /** Network or server trouble: keep the tokens and try again later. */
    data object TemporaryFailure : RefreshOutcome
}

fun interface TokenRefresher {
    suspend fun refresh(refreshToken: String): RefreshOutcome
}

/**
 * Hands out a valid access token. Refreshes when fewer than 60 seconds are left and once after a 401.
 * All refreshes run under one mutex: refresh tokens rotate, so two parallel refreshes would
 * invalidate each other.
 */
class TokenManager(
    private val store: TokenStore,
    private val refresher: TokenRefresher,
    private val clock: Clock,
    private val onSessionEnded: () -> Unit = {},
) {
    private val mutex = Mutex()

    fun current(): AuthTokens? = store.load()

    fun isSignedIn(): Boolean = store.load() != null

    fun save(tokens: AuthTokens) = store.save(tokens)

    fun clear() = store.clear()

    /** A token with at least 60 seconds left, refreshing first when needed. Null when signed out. */
    suspend fun validAccessToken(): String? {
        val tokens = store.load() ?: return null
        if (!expiresSoon(tokens)) return tokens.accessToken
        return mutex.withLock {
            val latest = store.load() ?: return@withLock null
            if (!expiresSoon(latest)) return@withLock latest.accessToken
            when (val outcome = refreshLocked(latest)) {
                is RefreshOutcome.Success -> outcome.tokens.accessToken
                RefreshOutcome.SessionInvalid -> null
                // Still present the old token; the server decides whether it is acceptable.
                RefreshOutcome.TemporaryFailure -> latest.accessToken.takeIf { latest.expiresAtMs > clock.nowMs() }
            }
        }
    }

    /**
     * Called after the API answered 401 for [rejectedAccessToken]. Returns a different access token
     * to retry with once, or null. If another caller already refreshed, its token is reused.
     */
    suspend fun refreshAfterUnauthorized(rejectedAccessToken: String?): String? = mutex.withLock {
        val latest = store.load() ?: return@withLock null
        if (rejectedAccessToken != null && latest.accessToken != rejectedAccessToken) {
            return@withLock latest.accessToken
        }
        when (val outcome = refreshLocked(latest)) {
            is RefreshOutcome.Success -> outcome.tokens.accessToken
            else -> null
        }
    }

    private suspend fun refreshLocked(latest: AuthTokens): RefreshOutcome {
        val outcome = refresher.refresh(latest.refreshToken)
        when (outcome) {
            is RefreshOutcome.Success -> store.save(
                // The new refresh token replaces the old one; the old one is discarded.
                outcome.tokens.copy(
                    userId = outcome.tokens.userId ?: latest.userId,
                    email = outcome.tokens.email ?: latest.email,
                ),
            )
            RefreshOutcome.SessionInvalid -> {
                store.clear()
                onSessionEnded()
            }
            RefreshOutcome.TemporaryFailure -> Unit
        }
        return outcome
    }

    private fun expiresSoon(tokens: AuthTokens): Boolean = tokens.expiresAtMs - clock.nowMs() < REFRESH_MARGIN_MS

    companion object {
        const val REFRESH_MARGIN_MS: Long = 60_000
    }
}
