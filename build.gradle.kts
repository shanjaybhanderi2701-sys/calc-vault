// Top-level build file. Plugins are declared here (apply false) and applied per-module.
plugins {
    alias(libs.plugins.android.application) apply false
    // Applied by the vendored :playerkit library module (subtree from player-kit, APP-413).
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ktlint) apply false
}
