package org.neighborhoodwatchdog.android.domain.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * The `X-App-Version` header the backend reads: `android/<major.minor.patch> (<versionCode>)`.
 * The server decides from it whether this version is still accepted (see backend `app_versions.py`).
 */
object AppVersionHeader {
    const val NAME = "X-App-Version"
    private val SEMANTIC = Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$")

    /** `0.1.0` when [versionName] is exactly `major.minor.patch` digits, otherwise null. */
    fun semanticVersion(versionName: String): String? = versionName.trim().takeIf { SEMANTIC.matches(it) }

    /** Null when the version name is not `major.minor.patch`; the build refuses such names (build.gradle.kts). */
    fun value(versionName: String, versionCode: Int): String? =
        semanticVersion(versionName)?.let { "android/$it ($versionCode)" }
}

/** What the screens show about updates. */
data class UpdateState(
    /** The server refuses this version: the app shows only the "Update required" screen and sends nothing. */
    val required: Boolean = false,
    /** A newer version exists but this one is still accepted. */
    val updateAvailable: Boolean = false,
    val latestVersion: String? = null,
    /** Only an `https://` address from the server, otherwise null (the screens then point to App Tester). */
    val updateUrl: String? = null,
    /** The resident closed the "new version" banner in this process. */
    val bannerDismissed: Boolean = false,
) {
    val showBanner: Boolean get() = updateAvailable && !required && !bannerDismissed
}

/** The update-required flag survives process death, so background work started later stays quiet. */
interface UpdateStateStore {
    /** The app version (`0.1.0 (1)`) that was refused, or null. */
    fun loadRequiredFor(): String?
    fun loadUpdateUrl(): String?
    fun save(requiredFor: String?, updateUrl: String?)
}

/**
 * App-wide answer to "may this app version still talk to the server?". Set by a 426
 * `update_required` from any API call or by the compatibility check; cleared only by a
 * compatibility check that says the version is supported, or by installing another version.
 */
class AppUpdateGate(private val store: UpdateStateStore, private val appVersion: String) {
    private val _state: MutableStateFlow<UpdateState>
    val state: StateFlow<UpdateState> get() = _state

    init {
        val refusedVersion = store.loadRequiredFor()
        val stillThisVersion = refusedVersion != null && refusedVersion == appVersion
        // A different installed version starts from "not required"; the next check decides again.
        if (refusedVersion != null && !stillThisVersion) store.save(null, null)
        _state = MutableStateFlow(UpdateState(required = stillThisVersion, updateUrl = if (stillThisVersion) safeUrl(store.loadUpdateUrl()) else null))
    }

    val isRequired: Boolean get() = _state.value.required

    /** A 426 `update_required` arrived. The URL is kept from an earlier compatibility answer. */
    fun markRequired() {
        if (_state.value.required) return
        _state.update { it.copy(required = true) }
        store.save(appVersion, _state.value.updateUrl)
    }

    /** The answer of `GET /api/v1/app/compatibility`. */
    fun applyCompatibility(supported: Boolean, updateAvailable: Boolean, latestVersion: String?, updateUrl: String?) {
        val url = safeUrl(updateUrl)
        _state.update { current ->
            UpdateState(
                required = !supported,
                updateAvailable = supported && updateAvailable,
                latestVersion = latestVersion,
                updateUrl = url,
                // A banner closed for one version comes back when an even newer one is announced.
                bannerDismissed = current.bannerDismissed && current.latestVersion == latestVersion,
            )
        }
        store.save(if (supported) null else appVersion, if (supported) null else url)
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerDismissed = true) }
    }

    private companion object {
        /** The address is opened with an implicit intent; anything but HTTPS is ignored. */
        fun safeUrl(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") && it.length > "https://".length }
    }
}
