// player-kit — shared Android video-player kit (Media3) for appblish apps.
//
// This module is the CANONICAL source consumed by both JGallery and CalcVault via `git subtree`
// (home decision: APP-409). It is deliberately SELF-CONTAINED and PORTABLE:
//
//  * Plain plugin IDs (no app-specific convention plugins) applied WITHOUT versions — each consuming
//    root supplies AGP/Kotlin/Compose-compiler from its own pluginManagement. Both apps have these.
//  * Dependencies pinned as literal Maven coordinates (no version-catalog aliases), because a shared
//    library owns its own tested dependency versions and must not depend on either app's catalog.
//    Media3 1.4.1 is the version already shipping in BOTH apps, so this pin is a no-op for them.
//  * Namespace is the neutral `com.appblish.playerkit` — carries neither app's package.
//
// NOTE (architect ruling, APP-411): JGallery's `:core:playerkit` previously applied the
// `jgallery.android.library` convention plugin, which layers the storage-boundary lint. This module
// holds NO file/media/storage APIs (bytes arrive via a caller-supplied Media3 DataSource.Factory), so
// dropping that lint here to gain cross-repo portability is a safe, intentional trade. The boundary
// is still enforced app-side where the concrete source lives (`:core:playback`, CalcVault vault).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.appblish.playerkit"
    compileSdk = 35

    defaultConfig {
        // Lowest common denominator across consumers (CalcVault minSdk 24, JGallery ≥ 24). A library's
        // minSdk must be ≤ every consuming app's minSdk.
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    // The pluggable playback seam IS Media3's own DataSource.Factory; ProgressiveMediaSource is the
    // stable engine the surface hands to ExoPlayer. `api` so consumers see the Media3 types on the seam.
    api("androidx.media3:media3-exoplayer:1.4.1")

    // Compose surface + controls. BOM pinned to the module's tested baseline (both apps ship ≥ this).
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")

    // Surface pauses on lifecycle stop (LifecycleStartEffect) so audio never leaks over other apps.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Pure-JVM math cores (gesture / scale / zoom) — the reuse anchor. 29 tests, no Android deps.
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.2")

    // Instrumented surface test (pinch-zoom + double-tap-seek on the shared gesture modifier).
    // Shared coverage owned by THIS repo (APP-408 authored it in JGallery; APP-411 hoists it to the
    // canonical source so both consumers get it via subtree). Coords pinned literal for portability.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}
