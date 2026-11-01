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
    // compileSdk/targetSdk 34 → 35 (Play 2025 policy); 35 → 36 (Play refused the sample's first
    // upload: "must target at least API level 36").
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.appdna.sample"
        minSdk = 24
        targetSdk = 36
        // Play keeps every version code it has received: 1 is the bundle it refused on Billing 7 / API 35,
        // 2 the first it accepted, 3 the first carrying a production key, 4 the first
        // that buys the free-trial offer it shows.
        versionCode = 4
        versionName = "1.0.3"

        /*
         * A Maps key the sample can be GIVEN, defaulting to empty.
         *
         * The SDK reads `com.google.android.geo.API_KEY` from the MERGED manifest, so a map block
         * renders its interactive tier only when the HOST declares one. The sample declared none at
         * all, which meant the one surface we walk maps on could only ever show the static tier —
         * the gesture behaviour a customer reports was unreachable here.
         *
         * Empty (the default) is read by `hasGoogleMapsManifestKey` as absent, so nothing changes
         * for anyone who does not pass one. The Flutter example does the same thing with the same
         * placeholder name.
         *
         *     ./gradlew :sample:assembleDebug -PappdnaMapsKey=<your key>
         */
        manifestPlaceholders["appdnaMapsKey"] =
            (project.findProperty("appdnaMapsKey") as String?) ?: ""

        /*
         * The base URL a DEVICE WALK points the SDK at, same shape as the maps key above.
         *
         * iOS already has this — `sdk-host-ios.ts --base-url` writes
         * `APPDNA_BASE_URL_OVERRIDE` into the built .app — and Android had no equivalent, so every
         * Android walk patched `AndroidManifest.xml` by hand and `sdk:sync` wiped the patch again
         * (the trap `sdk-sync-wipes-sample-patches` records). A build argument cannot be wiped.
         *
         *     ./gradlew :sample:assembleDebug -PappdnaBaseUrl=https://<tunnel>
         *
         * Empty (the committed default) means the SDK uses the environment's own base URL, which
         * is what a stock sample build does. `BaseUrlOverride` honours it only in a DEBUGGABLE
         * build on SANDBOX, so a release build cannot be pointed anywhere by it.
         */
        manifestPlaceholders["appdnaBaseUrl"] =
            (project.findProperty("appdnaBaseUrl") as String?) ?: ""

        /*
         * The SDK key and the paywall the "Show Paywall" button opens, same shape again.
         *
         * A build installed from a Play testing track is a RELEASE build, so the base-URL override
         * above does not apply to it and it talks to production — which needs a real sandbox key and
         * a real paywall id, not the demo placeholders. They are supplied at build time and never
         * committed; the defaults are the placeholders a stock sample has always shipped.
         *
         *     ./gradlew :sample:bundleRelease -PappdnaSampleApiKey=adn_test_… -PappdnaSamplePaywallId=<id>
         */
        // Empty by default, which `SampleConfig` reads as absent — so a stock build, and a device walk
        // whose `.ai/bridge/patch-sample.py` rewrites the literals in SampleApp/MainActivity, behave
        // exactly as before.
        manifestPlaceholders["appdnaSampleApiKey"] =
            (project.findProperty("appdnaSampleApiKey") as String?) ?: ""
        manifestPlaceholders["appdnaSamplePaywallId"] =
            (project.findProperty("appdnaSamplePaywallId") as String?) ?: ""
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

    // The JVM test runs the sample's own Application, which reads its key from the merged manifest.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
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

    // JVM test of the sample's push forwarding (no device required).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
