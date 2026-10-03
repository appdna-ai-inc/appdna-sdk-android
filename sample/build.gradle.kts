// sample/example app for the AppDNA Android SDK.
//
// This module is a thin Android Application that exercises the SDK's public
// surface: configure on launch, identify/track/setSessionData, present
// onboarding/paywall/survey, FCM token forwarding, and deep-link handoff.
// It deliberately depends on `project(":")` (the root SDK library module) so
// any breaking change in the public API surfaces here at compile time.
//
// The sample is NOT part of the default `assembleDebug` for the library and
// is published only when callers explicitly include `:sample` in the build.
plugins {
    // The root library module already pulls AGP + the Kotlin plugin onto the
    // build's classpath via its own `alias(libs.plugins.android.library)` and
    // `alias(libs.plugins.kotlin.android)`. Re-applying the same plugins here
    // with a version (which is what `alias(...)` does under the hood) makes
    // Gradle complain that "the plugin is already on the classpath with an
    // unknown version", so we apply by id with no version.
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.appdna.sample"
    // compileSdk/targetSdk 34 → 35 (Play 2025 policy).
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.appdna.sample"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            isMinifyEnabled = false
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    // Depend on the root library module so the sample exercises the same
    // public API external consumers see via Maven Central.
    implementation(project(":"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // FCM — sample forwards onNewToken / onMessageReceived to the SDK.
    implementation(libs.firebase.messaging)

    // Material Components — `themes.xml` parents from
    // `Theme.Material3.DayNight.NoActionBar`, which lives in this artifact.
    // Compose Material3 (above) is the runtime UI; this is just for the XML
    // theme the manifest references on Activity launch.
    implementation("com.google.android.material:material:1.11.0")
}
