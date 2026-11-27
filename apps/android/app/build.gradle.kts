import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/** Reads a build setting from a Gradle property first, then an environment variable. */
fun setting(name: String): String? =
    providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull?.trim()?.takeIf { it.isNotEmpty() }

fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Release builds only accept HTTPS origins. An unset value becomes a clearly invalid HTTPS host. */
fun releaseUrl(name: String, fallback: String): String {
    val value = setting(name) ?: return fallback
    if (!value.startsWith("https://")) {
        throw GradleException("$name must start with https:// for release builds (got '$value').")
    }
    return value
}

val appId = setting("APPLICATION_ID") ?: "app.feluda"
val appVersionCode = (setting("VERSION_CODE") ?: "1").toInt()
val appVersionName = setting("VERSION_NAME") ?: "0.1.0"

// The backend reads the version from `X-App-Version: android/<major.minor.patch> (<code>)` and refuses
// versions below its minimum. A name in any other form could never be told to update, so it is refused here.
if (!Regex("^\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}$").matches(appVersionName)) {
    throw GradleException("VERSION_NAME must be major.minor.patch digits, for example 1.2.3 (got '$appVersionName').")
}

// Release signing. The keystore and its passwords are never in the repository.
val keystorePath = setting("ANDROID_KEYSTORE_PATH")
val keystorePassword = setting("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = setting("ANDROID_KEY_ALIAS")
val releaseKeyPassword = setting("ANDROID_KEY_PASSWORD")
val hasReleaseSigning =
    keystorePath != null && keystorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "org.neighborhoodwatchdog.android"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // Public, non-secret build configuration. Blank means "not configured" and the app says so.
        buildConfigField("String", "SOURCE_REPO_URL", quoted(setting("SOURCE_REPO_URL") ?: ""))
        buildConfigField("String", "PRIVACY_URL", quoted(setting("PRIVACY_URL") ?: ""))
        buildConfigField("String", "SECURITY_CONTACT", quoted(setting("SECURITY_CONTACT") ?: ""))
        buildConfigField("String", "SECURITY_REVIEW_URL", quoted(setting("SECURITY_REVIEW_URL") ?: ""))
        buildConfigField(
            "String",
            "INTERNET_PROBE_URL",
            quoted(setting("INTERNET_PROBE_URL") ?: "https://connectivitycheck.gstatic.com/generate_204"),
        )
        buildConfigField("String", "FIREBASE_PROJECT_ID", quoted(setting("FIREBASE_PROJECT_ID") ?: ""))
        buildConfigField("String", "FIREBASE_APPLICATION_ID", quoted(setting("FIREBASE_APPLICATION_ID") ?: ""))
        buildConfigField("String", "FIREBASE_API_KEY", quoted(setting("FIREBASE_API_KEY") ?: ""))
        buildConfigField("String", "FIREBASE_SENDER_ID", quoted(setting("FIREBASE_SENDER_ID") ?: ""))
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", quoted(setting("API_BASE_URL") ?: "http://10.0.2.2:8000"))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField(
                "String",
                "API_BASE_URL",
                quoted(releaseUrl("API_BASE_URL", "https://api-not-configured.invalid")),
            )
            // Unsigned when the signing settings are absent, so `assembleRelease` always works.
            signingConfig = if (hasReleaseSigning) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)

    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
