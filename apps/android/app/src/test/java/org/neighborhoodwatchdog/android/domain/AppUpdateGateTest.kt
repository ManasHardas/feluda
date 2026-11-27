package org.neighborhoodwatchdog.android.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.neighborhoodwatchdog.android.domain.reporting.EventUploader
import org.neighborhoodwatchdog.android.domain.reporting.FlushResult
import org.neighborhoodwatchdog.android.domain.reporting.SendResult
import org.neighborhoodwatchdog.android.domain.update.AppUpdateGate
import org.neighborhoodwatchdog.android.domain.update.AppVersionHeader
import org.neighborhoodwatchdog.android.domain.update.UpdateStateStore

class InMemoryUpdateStore(var requiredFor: String? = null, var url: String? = null) : UpdateStateStore {
    override fun loadRequiredFor(): String? = requiredFor
    override fun loadUpdateUrl(): String? = url
    override fun save(requiredFor: String?, updateUrl: String?) {
        this.requiredFor = requiredFor
        url = updateUrl
    }
}

class AppUpdateGateTest {
    private val store = InMemoryUpdateStore()

    @Test
    fun headerIsPlatformSemanticVersionAndBuildNumber() {
        assertEquals("android/0.1.0 (1)", AppVersionHeader.value("0.1.0", 1))
        assertEquals("android/12.3.45 (678)", AppVersionHeader.value(" 12.3.45 ", 678))
        // The backend pattern is exactly major.minor.patch; anything else is not sent.
        assertNull(AppVersionHeader.value("0.1.0-debug", 1))
        assertNull(AppVersionHeader.value("1.2", 1))
        assertEquals("0.1.0", AppVersionHeader.semanticVersion("0.1.0"))
    }

    @Test
    fun a426MarksRequiredAndPersistsForThisVersion() {
        val gate = AppUpdateGate(store, "0.1.0 (1)")
        assertFalse(gate.isRequired)
        gate.markRequired()
        assertTrue(gate.isRequired)
        assertEquals("0.1.0 (1)", store.requiredFor)

        // After process death the flag is still there, so background work stays quiet.
        assertTrue(AppUpdateGate(store, "0.1.0 (1)").isRequired)
    }

    @Test
    fun anotherInstalledVersionClearsTheStoredFlag() {
        store.requiredFor = "0.1.0 (1)"
        store.url = "https://example.org/install"
        val gate = AppUpdateGate(store, "0.2.0 (2)")
        assertFalse(gate.isRequired)
        assertNull(store.requiredFor)
        assertNull(store.url)
    }

    @Test
    fun unsupportedAnswerRequiresTheUpdateAndKeepsTheUrl() {
        val gate = AppUpdateGate(store, "0.1.0 (1)")
        gate.applyCompatibility(supported = false, updateAvailable = true, latestVersion = "0.3.0", updateUrl = "https://example.org/install")
        val state = gate.state.value
        assertTrue(state.required)
        assertFalse("no banner on top of the required screen", state.showBanner)
        assertEquals("https://example.org/install", state.updateUrl)
        assertEquals("0.1.0 (1)", store.requiredFor)
        assertEquals("https://example.org/install", AppUpdateGate(store, "0.1.0 (1)").state.value.updateUrl)
    }

    @Test
    fun supportedAnswerClearsRequired() {
        val gate = AppUpdateGate(store, "0.1.0 (1)")
        gate.markRequired()
        gate.applyCompatibility(supported = true, updateAvailable = false, latestVersion = null, updateUrl = null)
        assertFalse(gate.isRequired)
        assertNull(store.requiredFor)
    }

    @Test
    fun updateAvailableShowsADismissableBannerUntilANewerVersionIsAnnounced() {
        val gate = AppUpdateGate(store, "0.1.0 (1)")
        gate.applyCompatibility(supported = true, updateAvailable = true, latestVersion = "0.2.0", updateUrl = null)
        assertTrue(gate.state.value.showBanner)
        assertNull("no URL: the banner points to App Tester", gate.state.value.updateUrl)
        gate.dismissBanner()
        assertFalse(gate.state.value.showBanner)
        gate.applyCompatibility(supported = true, updateAvailable = true, latestVersion = "0.2.0", updateUrl = null)
        assertFalse("same version again: stays closed", gate.state.value.showBanner)
        gate.applyCompatibility(supported = true, updateAvailable = true, latestVersion = "0.3.0", updateUrl = null)
        assertTrue(gate.state.value.showBanner)
    }

    @Test
    fun onlyHttpsUpdateAddressesAreKept() {
        val gate = AppUpdateGate(store, "0.1.0 (1)")
        gate.applyCompatibility(supported = false, updateAvailable = true, latestVersion = "0.2.0", updateUrl = "intent://evil#Intent;end")
        assertNull(gate.state.value.updateUrl)
        gate.applyCompatibility(supported = false, updateAvailable = true, latestVersion = "0.2.0", updateUrl = "http://example.org")
        assertNull(gate.state.value.updateUrl)
    }

    // ---- what the upload and heartbeat workers run (MonitoringController.flushNow -> EventUploader.flush)

    @Test
    fun whileRequiredTheUploaderSendsNothingAndKeepsTheQueue() = runTest {
        val gate = AppUpdateGate(store, "0.1.0 (1)")
        val queue = InMemoryQueueStore()
        val api = FakeApi()
        val events = TestEvents(FakeClock())
        val uploader = EventUploader(queue, InMemorySentLog(), api, FakeClock(), paused = { gate.isRequired })
        uploader.enqueue(events.event())
        gate.markRequired()

        assertEquals(FlushResult.Paused, uploader.flush())
        assertEquals(FlushResult.Paused, uploader.flush())
        assertTrue("no request was made", api.calls.isEmpty())
        assertEquals(1, queue.count())

        gate.applyCompatibility(supported = true, updateAvailable = false, latestVersion = null, updateUrl = null)
        assertEquals(FlushResult.Idle, uploader.flush())
        assertEquals(0, queue.count())
    }

    @Test
    fun a426FromTheEventEndpointPausesWithoutDroppingEvents() = runTest {
        val queue = InMemoryQueueStore()
        val api = FakeApi()
        val events = TestEvents(FakeClock())
        val uploader = EventUploader(queue, InMemorySentLog(), api, FakeClock())
        uploader.enqueue(events.event())
        uploader.enqueue(events.event())
        api.scripted += { SendResult.Rejected(426, "update_required") }

        assertEquals(FlushResult.Paused, uploader.flush())
        assertEquals(1, api.calls.size)
        assertEquals(2, queue.count())
        assertEquals("attempts are not counted against the events", 0, queue.rows.values.sumOf { it.attempts })
    }
}
