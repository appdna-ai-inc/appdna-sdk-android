plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    // SPEC-070-A J.5 — kotlinx-serialization plugin. Dep is added below; existing
    // org.json-based DTOs are unchanged so future migration can happen incrementally.
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
    id("signing")
    // SPEC-070-0 §3.4 — visual snapshot harness (Android leg).
    // Roborazzi runs Compose snapshot tests on the JVM via Robolectric — no device required.
    // PNG goldens live in src/test/snapshots/ and are committed; reviewed during PR.
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "ai.appdna.sdk"
    // SPEC-070-A finalization Phase 1 — compileSdk/targetSdk 34 → 35 to align with
    // Google Play 2025 policy: new app submissions must target API 35 (Android 15)
    // since Aug 2025; existing apps must reach 35 by Aug 2026.
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // SPEC-070-A Phase A.5 — ship ProGuard/R8 keep rules to consumers
        // so any host enabling minify doesn't silently strip SDK config DTOs.
        consumerProguardFiles("consumer-rules.pro")
    }

    // SPEC-070-A J.14 — explicit debug + release build types.
    // - release: log noise gated by BuildConfig.DEBUG inside Configuration.kt's
    //   `Log` object so verbose logs get suppressed in production builds even
    //   when callers leave LogLevel at DEBUG.
    // - debug: leaves all logs on; LeakCanary auto-installs (J.16).
    buildTypes {
        release {
            isMinifyEnabled = false
            consumerProguardFiles("consumer-rules.pro")
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
        // Expose BuildConfig.DEBUG to runtime (J.14 log gating).
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    // SPEC-419 — make merged Android resources (incl. Material3 library strings) available to the
    // Roborazzi/Robolectric JVM unit tests, so Compose components that call getString (e.g.
    // OutlinedTextField) render instead of throwing Resources$NotFoundException.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // SPEC-419 — modest 1g test-JVM heap. The Canvas snapshot tests (gauges/gears/rings/mockups)
            // accumulate bitmaps and OOM the ~512m default, but 2g starved the Mac and OOM-killed the
            // bridge server. 1g is the balance — the default-heap run completed without killing the
            // server, so 1g (modestly above default) is server-safe while clearing the test OOM.
            all {
                // …and 1g is not enough to VERIFY the whole Roborazzi suite in one JVM: it renders
                // every snapshot and OOMs. The cap protects the Mac BUILD BRIDGE, which is not a
                // factor on a CI runner — so CI passes `-ProborazziHeap=4g` for the verification
                // and local runs keep the server-safe default.
                //
                // Deliberately a PROJECT PROPERTY rather than `System.getenv("CI")`: build scripts
                // are evaluated at CONFIGURATION time, so an already-running daemon reuses its
                // cached configuration and an env var set on a later invocation silently does
                // nothing. I watched that happen — a run launched with CI=1 still OOMed at 1g.
                it.maxHeapSize = (project.findProperty("roborazziHeap") as String?) ?: "1g"

                // SPEC-070-B AC-35 — the shared fixtures live OUTSIDE this module
                // (packages/sdk-shared-fixtures), so Gradle saw no input change when one was edited
                // and marked the test task UP-TO-DATE. Editing a fixture's expected value therefore
                // did NOT re-run the test: it reported the previous run's result and went green.
                // Found by falsification — planting `429: transient=false` (the exact live defect the
                // fixture forbids) still produced BUILD SUCCESSFUL. Declaring the tree as an input
                // makes a fixture edit invalidate the cache, which is what makes these gates real.
                val fixtures = rootProject.file("../sdk-shared-fixtures")
                if (fixtures.isDirectory) {
                    it.inputs.dir(fixtures)
                        .withPropertyName("sharedFixtures")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
    }

    // SPEC-070-A J.3 — emit sources + javadoc jars for Maven Central / Sonatype.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

// Read version from gradle.properties
val sdkVersion: String by project

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                groupId = "ai.appdna"
                artifactId = "sdk-android"
                version = sdkVersion

                pom {
                    name.set("AppDNA Android SDK")
                    description.set("AppDNA SDK for Android — analytics, experiments, paywalls, onboarding, surveys, and more.")
                    url.set("https://github.com/appdna-ai-inc/appdna-sdk-android")

                    licenses {
                        license {
                            name.set("AppDNA SDK Proprietary License")
                            url.set("https://github.com/appdna-ai-inc/appdna-sdk-android/blob/main/LICENSE")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("appdna-ai")
                            name.set("AppDNA")
                            email.set("sdk@appdna.ai")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/appdna-ai-inc/appdna-sdk-android.git")
                        developerConnection.set("scm:git:ssh://github.com/appdna-ai-inc/appdna-sdk-android.git")
                        url.set("https://github.com/appdna-ai-inc/appdna-sdk-android")
                    }
                }
            }
        }

        repositories {
            // Local staging dir — used by sdk-publish CI for artifact inspection.
            maven {
                name = "LocalStaging"
                url = uri(layout.buildDirectory.dir("staging-deploy"))
            }

            // SPEC-070-A J.3 — Sonatype OSSRH (Maven Central) staging repo.
            // Credentials come from env vars so they never live in version control:
            //   OSSRH_USERNAME / OSSRH_PASSWORD (Sonatype Jira account)
            //   GPG_SIGNING_KEY / GPG_SIGNING_PASSWORD (signing block below)
            // Activated by `./gradlew publishReleasePublicationToOSSRHRepository`.
            maven {
                name = "OSSRH"
                val releasesRepoUrl = "https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/"
                val snapshotsRepoUrl = "https://s01.oss.sonatype.org/content/repositories/snapshots/"
                url = uri(if (sdkVersion.endsWith("SNAPSHOT")) snapshotsRepoUrl else releasesRepoUrl)
                credentials {
                    username = System.getenv("OSSRH_USERNAME") ?: ""
                    password = System.getenv("OSSRH_PASSWORD") ?: ""
                }
            }
        }
    }

    signing {
        val signingKey = System.getenv("GPG_SIGNING_KEY")
        val signingPassword = System.getenv("GPG_SIGNING_PASSWORD")
        // SPEC-070-C: only sign when keys are present (the CI publish path). Locally —
        // e.g. `publishToMavenLocal` for on-branch plugin verification of the D10 seams —
        // there are no keys, so skip signing rather than fail on a missing `.asc`.
        if (signingKey != null && signingPassword != null) {
            useInMemoryPgpKeys(signingKey, signingPassword)
            sign(publishing.publications["release"])
        }
    }
}

dependencies {
    // Jetpack Compose — bumped to 2024.02.02 (SPEC-070-A J.13). Compose Compiler
    // 1.5.8 + Kotlin 1.9.22 caps the supported BoM at 2024.02.02; the 2024.09.x
    // line requires Kotlin 1.9.25 + compiler 1.5.15 which is out of scope here.
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.ext)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Firebase Firestore for remote config
    implementation(libs.firebase.firestore)

    // Firebase Cloud Messaging for push notifications
    implementation(libs.firebase.messaging)

    // OkHttp for event ingestion
    implementation(libs.okhttp)

    // Kotlin Coroutines — bumped to 1.8.1 (SPEC-070-A J.13).
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    // SPEC-070-A A.30 — `future { ... }` coroutine builder so suspend public APIs
    // (PushModule.requestPermission, BillingModule.purchase/getProducts/getEntitlements)
    // can expose `CompletableFuture` overloads for Java consumers.
    implementation(libs.kotlinx.coroutines.jdk8)

    // SPEC-070-A J.22 — kotlinx-collections-immutable. Backs the @Immutable
    // annotations added by J.10 on hot-path Compose-consumed config DTOs
    // (OnboardingFlowConfig, StepConfig, PaywallConfig, SurveyConfig,
    // MessageConfig, ContentBlock, …). Without ImmutableList<T>/PersistentMap<K,V>,
    // Compose's stability inference can't prove List<T> won't mutate underneath,
    // so a parent re-emit always recomposes children. Migrating the iterables
    // listed in SPEC-070-A J.22's INCLUDE list lets Compose skip recomposition
    // when contents are structurally equal.
    implementation(libs.kotlinx.collections.immutable)

    // JSON serialization (legacy — most DTOs still use this).
    implementation(libs.org.json)
    // SPEC-070-A J.5 — kotlinx-serialization-json. Future DTOs can opt in by
    // annotating with `@Serializable`; existing DTOs are unchanged in this PR.
    implementation(libs.kotlinx.serialization.json)

    // Google Play Billing Library
    implementation(libs.billing.ktx)

    // Google Play In-App Review
    implementation(libs.play.review.ktx)

    // Google Play Install Referrer (for deferred deep links)
    implementation(libs.installreferrer)

    // SPEC-067: WorkManager for background event upload
    implementation(libs.work.runtime.ktx)

    // SPEC-070-A A.18: ProcessLifecycleOwner observer for app-foreground/background
    // hooks (parity with iOS UIApplication.didEnterBackgroundNotification observer
    // in EventQueue.swift).
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.common.java8)

    // SPEC-070-A G.5: EncryptedSharedPreferences for sensitive on-device storage
    // (anon_id, user_id, user_traits, push token). iOS parity = Keychain.
    implementation(libs.security.crypto)

    // SPEC-070-A A.3: Coil for NetworkImage memory + disk caching.
    // Replaces the BitmapFactory.decodeStream-on-every-recomposition path.
    // Default singleton ImageLoader is configured in core/AppDNAImageLoader.kt
    // with a 25%-of-available-memory cache + 50 MB disk cache at
    // <cacheDir>/appdna_image_cache.
    implementation(libs.coil.compose)

    // SPEC-070-A E.1 — rich media catch-up to iOS SPEC-085 parity.
    // Lottie (Airbnb) for vector animations in onboarding/messages/surveys/push.
    // Rive (state-machine animations) for richer interactive content.
    // ExoPlayer (media3) for inline video playback in messages + onboarding.
    // Coil GIF decoder so animated GIFs render in NetworkImage (otherwise Coil
    // shows the first frame as a static image).
    // AndroidSVG so vector .svg URLs (icon library, hero artwork) decode to
    // bitmaps inside NetworkImage rather than failing silently.
    implementation(libs.lottie.compose)
    implementation(libs.rive.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.coil.gif)
    implementation(libs.androidsvg.aar)

    // RevenueCat (optional — conditionally used).
    // SPEC-070-A A.19 — bumped to 8.x to match the public Purchases API
    // (`Purchases.sharedInstance`, `Purchases.configure(PurchasesConfiguration)`,
    // suspend `awaitOfferings()`, `awaitPurchase()`, `awaitRestore()`,
    // `awaitCustomerInfo()`) we use in integrations/RevenueCatBridge.kt.
    compileOnly(libs.revenuecat.purchases)

    // SPEC-070-A J.16 — LeakCanary auto-installs in debug builds and reports
    // leaks via the Android system notification shade. Excluded from release.
    debugImplementation(libs.leakcanary.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // SPEC-070-0 §3.4 — visual snapshot harness (JVM-only; no device required)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    // Supplies the ComponentActivity declaration that createComposeRule() launches. AGP merges
    // testImplementation manifests into the unit-test manifest because isIncludeAndroidResources=true.
    testImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
