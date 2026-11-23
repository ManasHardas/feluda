package org.neighborhoodwatchdog.android.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.auth.DeviceCredentials

class DeviceCredentialsTest {
    private val store = InMemoryCredentialStore()
    private val credentials = DeviceCredentials(store)
    private var rejections = 0

    init {
        credentials.addRejectedListener { rejections++ }
    }

    @Test
    fun aPhoneIsEnrolledExactlyWhileItHoldsASecret() {
        assertFalse(credentials.isEnrolled)
        assertNull(credentials.token())
        credentials.save("secret-1")
        assertTrue(credentials.isEnrolled)
        assertEquals("secret-1", credentials.token())
        credentials.clear()
        assertFalse(credentials.isEnrolled)
        assertEquals("leaving on purpose is not a rejection", 0, rejections)
    }

    @Test
    fun aRejectedSecretIsRemovedAndTheListenersAreToldOnce() {
        credentials.save("secret-1")
        assertTrue(credentials.rejected("secret-1"))
        assertNull(store.deviceToken)
        assertEquals(1, rejections)
        assertFalse("nothing is left to reject", credentials.rejected("secret-1"))
        assertEquals(1, rejections)
    }

    @Test
    fun aLateAnswerToAnOlderSecretDoesNotRemoveANewEnrolment() {
        credentials.save("secret-2")
        assertFalse(credentials.rejected("secret-1"))
        assertEquals("secret-2", credentials.token())
        assertEquals(0, rejections)
    }
}
