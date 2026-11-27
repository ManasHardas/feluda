package org.neighborhoodwatchdog.android.data.local

import android.content.Context
import android.content.SharedPreferences
import org.neighborhoodwatchdog.android.domain.update.UpdateStateStore

/**
 * The update-required flag in its own app-private SharedPreferences file, so leaving the
 * neighborhood (which clears [LocalState]) does not forget that this version is refused.
 */
class UpdateStatePrefs(context: Context) : UpdateStateStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("watchdog_update", Context.MODE_PRIVATE)

    override fun loadRequiredFor(): String? = prefs.getString(K_REQUIRED_FOR, null)

    override fun loadUpdateUrl(): String? = prefs.getString(K_UPDATE_URL, null)

    override fun save(requiredFor: String?, updateUrl: String?) {
        // commit, not apply: a worker started right after process death must see the flag.
        prefs.edit().putString(K_REQUIRED_FOR, requiredFor).putString(K_UPDATE_URL, updateUrl).commit()
    }

    private companion object {
        const val K_REQUIRED_FOR = "update_required_for_version"
        const val K_UPDATE_URL = "update_url"
    }
}
