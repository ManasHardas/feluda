package org.neighborhoodwatchdog.android.config

import android.os.Build
import org.neighborhoodwatchdog.android.BuildConfig

/** Build-time configuration. None of these values is a secret. Blank means "not configured". */
data class AppConfig(
    val apiBaseUrl: String,
    val sourceRepoUrl: String,
    val privacyUrl: String,
    val securityContact: String,
    val securityReviewUrl: String,
    val internetProbeUrl: String,
    val firebaseProjectId: String,
    val firebaseApplicationId: String,
    val firebaseApiKey: String,
    val firebaseSenderId: String,
    val versionName: String,
    val versionCode: Int,
    val debug: Boolean,
) {
    val apiRoot: String get() = apiBaseUrl.trimEnd('/') + "/api/v1/"
    val healthUrl: String get() = apiRoot + "monitoring/health"

    /** Push needs all four Firebase values. Without them the app says push is not configured. */
    val pushConfigured: Boolean
        get() = firebaseProjectId.isNotBlank() && firebaseApplicationId.isNotBlank() &&
            firebaseApiKey.isNotBlank() && firebaseSenderId.isNotBlank()

    /** Matches the API's VersionString pattern (letters, digits, dot, space, brackets, plus, minus). */
    val appVersion: String get() = sanitizeVersion("$versionName ($versionCode)")
    val osVersion: String get() = sanitizeVersion("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")

    companion object {
        fun fromBuildConfig(): AppConfig = AppConfig(
            apiBaseUrl = BuildConfig.API_BASE_URL,
            sourceRepoUrl = BuildConfig.SOURCE_REPO_URL,
            privacyUrl = BuildConfig.PRIVACY_URL,
            securityContact = BuildConfig.SECURITY_CONTACT,
            securityReviewUrl = BuildConfig.SECURITY_REVIEW_URL,
            internetProbeUrl = BuildConfig.INTERNET_PROBE_URL,
            firebaseProjectId = BuildConfig.FIREBASE_PROJECT_ID,
            firebaseApplicationId = BuildConfig.FIREBASE_APPLICATION_ID,
            firebaseApiKey = BuildConfig.FIREBASE_API_KEY,
            firebaseSenderId = BuildConfig.FIREBASE_SENDER_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            debug = BuildConfig.DEBUG,
        )

        fun sanitizeVersion(raw: String): String {
            val cleaned = raw.replace(Regex("[^A-Za-z0-9._ ()+-]"), "-").take(32)
            return cleaned.ifBlank { "unknown" }
        }
    }
}
