pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Pre-resolve AGP plugin markers at the settings level so
    // child modules (e.g. `:sample`) can apply `id("com.android.application")`
    // / `id("com.android.library")` without specifying a version. Without this
    // block the `:sample` module fails plugin resolution because the root
    // already has AGP on its classpath (loaded via `alias(libs.plugins.*)`)
    // and Gradle refuses to re-resolve the same plugin id with a version.
    //
    // Versions intentionally hard-coded here — settings.gradle.kts can't read
    // the libs.versions.toml file before pluginManagement runs. Keep these in
    // sync with `[versions] agp` / `kotlin` in gradle/libs.versions.toml.
    plugins {
        id("com.android.application") version "8.2.0"
        id("com.android.library") version "8.2.0"
        id("org.jetbrains.kotlin.android") version "1.9.22"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "appdna-sdk-android"

// sample/example app module. Lives at
// packages/appdna-sdk-android/sample/ and depends on `project(":")` so any
// breaking change in the SDK's public surface fails `./gradlew :sample:assembleDebug`.
// The library's own assembleDebug does NOT depend on this module, so a
// sample-only compile error never blocks publishing.
include(":sample")
