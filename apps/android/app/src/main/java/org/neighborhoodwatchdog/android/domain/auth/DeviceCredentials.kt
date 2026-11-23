package org.neighborhoodwatchdog.android.domain.auth

/** Where the device secret is kept: Keystore-encrypted on the phone, in memory in tests. */
interface CredentialStore {
    fun load(): String?
    fun save(deviceToken: String)
    fun clear()
}

/**
 * The one secret this phone received when it was enrolled with an invitation link. It is sent as
 * the bearer token of every request. It does not expire and there is nothing to refresh: it ends
 * when the phone leaves or is removed, and then the server answers 401.
 */
class DeviceCredentials(private val store: CredentialStore) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    val isEnrolled: Boolean get() = store.load() != null

    fun token(): String? = store.load()

    fun save(deviceToken: String) = store.save(deviceToken)

    fun clear() = store.clear()

    /** Called after the secret was removed because the server no longer accepts it. */
    fun addRejectedListener(listener: () -> Unit) {
        listeners += listener
    }

    /**
     * The server answered 401 `unauthorized` to a request that carried [sentToken]. The stored
     * secret is removed only if it is still that one, so a late answer to an old request cannot
     * remove a newer enrolment. Returns true when the secret was removed.
     */
    fun rejected(sentToken: String): Boolean {
        synchronized(this) {
            if (store.load() != sentToken) return false
            store.clear()
        }
        listeners.forEach { it() }
        return true
    }
}
